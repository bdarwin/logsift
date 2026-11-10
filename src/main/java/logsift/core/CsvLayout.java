package logsift.core;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;

/**
 * Where a table's header is and which rows aren't rows, as chosen by hand: an export that starts
 * with a title or a blank row, or has a totals row under the header. Rows are known by a hash of
 * their text (line breaks inside a value as {@code \n}, without {@code \r}), so the choice holds
 * wherever the row is and however the file is read (the table, filters, Pivot, Query, Compare).
 *
 * @param headerLine the header's line in the file (0-based), or -1: the first line that reads as one
 * @param ignored hashes ({@link #hash}) of the rows that aren't rows, sorted
 */
public record CsvLayout(long headerLine, long[] ignored) {
    /** Nothing chosen: the header found as usual, every row a row. */
    public static final CsvLayout NONE = new CsvLayout(-1, new long[0]);

    /** At most this many rows ignored by hand. */
    public static final int MAX_IGNORED = 400;

    public CsvLayout {
        ignored = ignored == null ? new long[0] : ignored.clone();
        Arrays.sort(ignored);
    }

    /** Whether anything was chosen. */
    public boolean isSet() {
        return headerLine >= 0 || ignored.length > 0;
    }

    public CsvLayout withHeaderLine(long line) {
        return new CsvLayout(line, ignored);
    }

    /** With this row ignored (by its text), or not ignored any more. */
    public CsvLayout withIgnored(String row, boolean on) {
        long h = hash(row);
        boolean has = Arrays.binarySearch(ignored, h) >= 0;
        if (has == on) {
            return this;
        }
        long[] next;
        if (on) {
            next = Arrays.copyOf(ignored, ignored.length + 1);
            next[ignored.length] = h;
        } else {
            next = Arrays.stream(ignored).filter(x -> x != h).toArray();
        }
        return new CsvLayout(headerLine, next);
    }

    /** Whether this row (its text) is one ignored by hand. */
    public boolean ignores(String row) {
        return ignored.length > 0 && row != null && Arrays.binarySearch(ignored, hash(row)) >= 0;
    }

    /**
     * A row's hash: its text without a byte order mark or {@code \r}, so a row hashes the same
     * read as a line, as a row over several lines, or from a Windows file.
     */
    public static long hash(String row) {
        long h = 0xcbf29ce484222325L;
        int from = row.startsWith("﻿") ? 1 : 0;
        int to = row.length();
        while (to > from && (row.charAt(to - 1) == '\r' || row.charAt(to - 1) == '\n')) {
            to--;
        }
        int n = 0;
        for (int i = from; i < to; i++) {
            char c = row.charAt(i);
            if (c == '\r') {
                continue;
            }
            h ^= c;
            h *= 0x100000001b3L;
            n++;
        }
        h ^= n;
        return h * 0x9E3779B97F4A7C15L;
    }

    /** As text to keep: "h=3;i=1a2b,ff00" (empty for {@link #NONE}). */
    public String encode() {
        if (!isSet()) {
            return "";
        }
        StringBuilder b = new StringBuilder("h=").append(headerLine);
        if (ignored.length > 0) {
            b.append(";i=");
            for (int k = 0; k < ignored.length; k++) {
                b.append(k == 0 ? "" : ",").append(Long.toHexString(ignored[k]));
            }
        }
        return b.toString();
    }

    /** Reads {@link #encode}'s text back; {@link #NONE} for anything else. */
    public static CsvLayout decode(String text) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        long header = -1;
        long[] ign = new long[0];
        try {
            for (String part : text.split(";")) {
                if (part.startsWith("h=")) {
                    header = Long.parseLong(part.substring(2).strip());
                } else if (part.startsWith("i=") && part.length() > 2) {
                    ign = Arrays.stream(part.substring(2).split(",")).mapToLong(x -> Long.parseUnsignedLong(x.strip(), 16))
                            .toArray();
                }
            }
        } catch (NumberFormatException e) {
            return NONE;
        }
        return new CsvLayout(Math.max(-1, header), ign);
    }

    /** A short key for caches made with this layout ("" for none). */
    public String key() {
        return isSet() ? Integer.toHexString(encode().hashCode()).toUpperCase(Locale.ROOT) : "";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CsvLayout l && l.headerLine == headerLine && Arrays.equals(l.ignored, ignored);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(headerLine) * 31 + Arrays.hashCode(ignored);
    }

    @Override
    public String toString() {
        return encode();
    }

    // ---------------------------------------------------------------- the files' layouts

    private static volatile Function<Path, CsvLayout> store = p -> NONE;

    /** Where layouts chosen by hand are kept (the app sets it; by default none are). */
    public static void setStore(Function<Path, CsvLayout> s) {
        store = s == null ? p -> NONE : s;
    }

    /** The layout chosen for a file, or {@link #NONE}. */
    public static CsvLayout of(Path file) {
        if (file == null) {
            return NONE;
        }
        try {
            CsvLayout l = store.apply(file.toAbsolutePath().normalize());
            return l == null ? NONE : l;
        } catch (RuntimeException e) {
            return NONE;
        }
    }
}
