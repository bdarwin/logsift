package logmaster.core;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A timestamp format found in a log, and a fast parser for it working on raw bytes.
 *
 * <p>Times are "wall clock" milliseconds: the date and time as written, counted as if they were
 * UTC. Time zones in the text are ignored, so a log shows the times it contains, and logs that
 * were written in the same zone line up. Display code formats them back in UTC.
 */
public final class TimeFormat {
    /** No timestamp on this line. */
    public static final long NONE = Long.MIN_VALUE;
    /** How far into a line a timestamp is searched for, for formats that are not at the start. */
    private static final int SEARCH_BYTES = 200;

    private enum Kind {
        ISO,        // 2026-10-21 10:02:13.123 (also with T, or anywhere near the start: JSON)
        YMD_SLASH,  // 2026/10/21 10:02:13
        DMY,        // 21-10-2026 10:02:13, 21/10/2026, 21.10.2026
        DMON,       // 21-Oct-2026 10:02:13
        SYSLOG,     // Oct 21 10:02:13
        APACHE,     // [21/Oct/2026:10:02:13 +0000]
        TIME,       // 10:02:13.123
        EPOCH,      // 1729500000 or 1729500000123
        JSON_EPOCH  // {"ts":1729500000123, ...} (a JSON field holding epoch seconds or millis)
    }

    private final Kind kind;
    private final String name;
    private final boolean anywhere;
    private final int defaultYear;

    private TimeFormat(Kind kind, String name, boolean anywhere, int defaultYear) {
        this.kind = kind;
        this.name = name;
        this.anywhere = anywhere;
        this.defaultYear = defaultYear;
    }

    public String name() {
        return name;
    }

    /** Candidates, most specific first. {@code year} is used for formats without one. */
    static List<TimeFormat> candidates(int year) {
        return List.of(
                new TimeFormat(Kind.ISO, "2026-10-21 10:02:13", false, year),
                new TimeFormat(Kind.YMD_SLASH, "2026/10/21 10:02:13", false, year),
                new TimeFormat(Kind.DMON, "21-Oct-2026 10:02:13", false, year),
                new TimeFormat(Kind.DMY, "21-10-2026 10:02:13", false, year),
                new TimeFormat(Kind.SYSLOG, "Oct 21 10:02:13", false, year),
                new TimeFormat(Kind.APACHE, "[21/Oct/2026:10:02:13]", true, year),
                new TimeFormat(Kind.ISO, "2026-10-21T10:02:13 (inside the line)", true, year),
                new TimeFormat(Kind.TIME, "10:02:13", false, year),
                new TimeFormat(Kind.EPOCH, "epoch 1729500000", false, year),
                new TimeFormat(Kind.JSON_EPOCH, "JSON epoch {\"ts\":1729500000123}", true, year));
    }

    /**
     * The format most lines of the sample have, or null if none fits at least 30% of the
     * non-blank lines.
     *
     * @param year the year for formats without one (syslog), usually the file's
     */
    public static TimeFormat detect(List<String> sample, int year) {
        int nonBlank = 0;
        for (String s : sample) {
            if (s != null && !s.isBlank()) {
                nonBlank++;
            }
        }
        if (nonBlank < 3) {
            return null;
        }
        TimeFormat best = null;
        double bestShare = 0.3;
        for (TimeFormat f : candidates(year)) {
            int hits = 0;
            for (String s : sample) {
                if (s != null && !s.isBlank() && f.parse(s) != NONE) {
                    hits++;
                }
            }
            double share = (double) hits / nonBlank;
            if (share > bestShare + 1e-9) {
                best = f;
                bestShare = share;
            }
        }
        return best;
    }

    /** The time of a line, or {@link #NONE}. */
    public long parse(String line) {
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        return parse(b, 0, b.length);
    }

    /** The time of the line in {@code b[start, end)}, or {@link #NONE}. */
    public long parse(byte[] b, int start, int end) {
        start = EntryFormat.skipSourceTag(b, start, end); // merged logs: "<node1> 2026-..."
        if (!anywhere) {
            int p = start;
            if (p < end && b[p] == '[') {
                p++; // "[2026-10-21 ..." and "[10:02:13 ..."
            }
            return parseAt(b, p, end);
        }
        int limit = Math.min(end, start + SEARCH_BYTES);
        if (kind == Kind.JSON_EPOCH) {
            return jsonEpoch(b, start, limit, end);
        }
        for (int p = start; p < limit; p++) {
            byte c = b[p];
            if (kind == Kind.APACHE ? c == '[' : isDigit(c)) {
                long t = parseAt(b, kind == Kind.APACHE ? p + 1 : p, end);
                if (t != NONE) {
                    return t;
                }
            }
        }
        return NONE;
    }

    private long parseAt(byte[] b, int p, int end) {
        return switch (kind) {
            case ISO -> ymd(b, p, end, (byte) '-');
            case YMD_SLASH -> ymd(b, p, end, (byte) '/');
            case DMY -> dmy(b, p, end);
            case DMON -> dmon(b, p, end, (byte) '-', (byte) ' ');
            case APACHE -> dmon(b, p, end, (byte) '/', (byte) ':');
            case SYSLOG -> syslog(b, p, end);
            case TIME -> time(b, p, end);
            case EPOCH -> epoch(b, p, end);
            case JSON_EPOCH -> jsonEpoch(b, p, Math.min(end, p + SEARCH_BYTES), end);
        };
    }

    // yyyy-MM-dd[ T]HH:mm:ss[.SSS]
    private long ymd(byte[] b, int p, int end, byte sep) {
        if (end - p < 19 || b[p + 4] != sep || b[p + 7] != sep) {
            return NONE;
        }
        int y = num(b, p, 4);
        int mo = num(b, p + 5, 2);
        int d = num(b, p + 8, 2);
        byte t = b[p + 10];
        if (y < 0 || mo < 1 || mo > 12 || d < 1 || d > 31 || (t != ' ' && t != 'T')) {
            return NONE;
        }
        return dateTime(y, mo, d, b, p + 11, end);
    }

    // dd-MM-yyyy, dd/MM/yyyy or dd.MM.yyyy, then a time
    private long dmy(byte[] b, int p, int end) {
        if (end - p < 19) {
            return NONE;
        }
        byte sep = b[p + 2];
        if ((sep != '-' && sep != '/' && sep != '.') || b[p + 5] != sep || b[p + 10] != ' ') {
            return NONE;
        }
        int d = num(b, p, 2);
        int mo = num(b, p + 3, 2);
        int y = num(b, p + 6, 4);
        if (y < 0 || mo < 1 || mo > 12 || d < 1 || d > 31) {
            return NONE;
        }
        return dateTime(y, mo, d, b, p + 11, end);
    }

    // dd-Mon-yyyy HH:mm:ss (sep '-', then ' ') or dd/Mon/yyyy:HH:mm:ss (Apache)
    private long dmon(byte[] b, int p, int end, byte sep, byte beforeTime) {
        if (end - p < 20 || b[p + 2] != sep || b[p + 6] != sep || b[p + 11] != beforeTime) {
            return NONE;
        }
        int d = num(b, p, 2);
        int mo = month(b, p + 3);
        int y = num(b, p + 7, 4);
        if (d < 1 || d > 31 || mo < 0 || y < 0) {
            return NONE;
        }
        return dateTime(y, mo, d, b, p + 12, end);
    }

    // Mon dd HH:mm:ss (day may be space-padded)
    private long syslog(byte[] b, int p, int end) {
        if (end - p < 15 || b[p + 3] != ' ' || b[p + 6] != ' ') {
            return NONE;
        }
        int mo = month(b, p);
        int d = b[p + 4] == ' ' ? num(b, p + 5, 1) : num(b, p + 4, 2);
        if (mo < 0 || d < 1 || d > 31) {
            return NONE;
        }
        return dateTime(defaultYear, mo, d, b, p + 7, end);
    }

    private long epoch(byte[] b, int p, int end) {
        int n = 0;
        while (p + n < end && n < 14 && isDigit(b[p + n])) {
            n++;
        }
        if (n == 10) {
            long s = numLong(b, p, 10);
            long ms = 0;
            if (p + 14 <= end && b[p + 10] == '.') {
                int frac = num(b, p + 11, 3);
                ms = frac < 0 ? 0 : frac;
            }
            return s * 1000 + ms;
        }
        if (n == 13) {
            return numLong(b, p, 13);
        }
        return NONE;
    }

    /** The first {@code ":} followed by 10 or 13 digits (optionally quoted): epoch s or ms. */
    private static long jsonEpoch(byte[] b, int start, int limit, int end) {
        for (int p = start; p + 1 < limit; p++) {
            if (b[p] == '"' && b[p + 1] == ':') {
                int q = p + 2;
                while (q < end && (b[q] == ' ' || b[q] == '"')) {
                    q++;
                }
                int n = 0;
                while (q + n < end && isDigit(b[q + n]) && n < 14) {
                    n++;
                }
                if (n == 13) {
                    return numLong(b, q, 13);
                }
                if (n == 10 && (q + 10 >= end || b[q + 10] != '.')) {
                    return numLong(b, q, 10) * 1000;
                }
            }
        }
        return NONE;
    }

    private long dateTime(int y, int mo, int d, byte[] b, int p, int end) {
        long t = time(b, p, end);
        if (t == NONE) {
            return NONE;
        }
        return daysFromCivil(y, mo, d) * 86_400_000L + t;
    }

    // HH:mm:ss[.SSS or ,SSS] -> millis of the day
    private static long time(byte[] b, int p, int end) {
        if (end - p < 8 || b[p + 2] != ':' || b[p + 5] != ':') {
            return NONE;
        }
        int h = num(b, p, 2);
        int mi = num(b, p + 3, 2);
        int s = num(b, p + 6, 2);
        if (h < 0 || h > 23 || mi < 0 || mi > 59 || s < 0 || s > 60) {
            return NONE;
        }
        int ms = 0;
        int q = p + 8;
        if (q < end && (b[q] == '.' || b[q] == ',')) {
            int digits = 0;
            int frac = 0;
            q++;
            while (q < end && isDigit(b[q]) && digits < 9) {
                if (digits < 3) {
                    frac = frac * 10 + (b[q] - '0');
                }
                digits++;
                q++;
            }
            for (int i = digits; i < 3; i++) {
                frac *= 10;
            }
            ms = digits == 0 ? 0 : frac;
        }
        return ((h * 60L + mi) * 60L + s) * 1000L + ms;
    }

    private static final String MONTHS = "JanFebMarAprMayJunJulAugSepOctNovDec";

    private static int month(byte[] b, int p) {
        for (int m = 0; m < 12; m++) {
            if (b[p] == MONTHS.charAt(m * 3) && b[p + 1] == MONTHS.charAt(m * 3 + 1)
                    && b[p + 2] == MONTHS.charAt(m * 3 + 2)) {
                return m + 1;
            }
        }
        return -1;
    }

    private static boolean isDigit(byte c) {
        return c >= '0' && c <= '9';
    }

    private static int num(byte[] b, int p, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) {
            byte c = b[p + i];
            if (!isDigit(c)) {
                return -1;
            }
            v = v * 10 + (c - '0');
        }
        return v;
    }

    private static long numLong(byte[] b, int p, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = v * 10 + (b[p + i] - '0');
        }
        return v;
    }

    /** Days since 1970-01-01 for a date (Howard Hinnant's algorithm). */
    static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    @Override
    public String toString() {
        return name;
    }
}
