package logsift.core;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A CSV file's rows, where they differ from its lines: a quoted value may hold line breaks
 * ({@code "line one\nline two"}), so one row goes over several lines. Lines that continue the
 * row above are found once, by scanning the file's bytes for quotes (new lines are scanned as
 * the file grows), and rows are read whole, however long they are.
 *
 * <p>{@link #read} works like {@link LogDocument#readLines} for code that splits rows into
 * cells: a row's first line gets the whole row (its lines joined with {@code \n}), the lines
 * that continue it get null, as lines that are no rows.
 */
public final class CsvRows implements ColumnProfile.Lines {
    /** A quoted value over more lines than this is a quote left open by mistake. */
    public static final int MAX_SPAN = 1000;
    /** The longest row read (a longer one is cut). */
    public static final int MAX_ROW_BYTES = 16 * 1024 * 1024;

    private static final int FIELD_START = 0;
    private static final int UNQUOTED = 1;
    private static final int QUOTED = 2;
    private static final int QUOTE = 3; // a quote inside a quoted value: its end, or "" for a quote
    private static final int AFTER = 4;

    private final LogDocument doc;
    private final char delimiter;

    // the scan, under the lock
    private volatile int gen = Integer.MIN_VALUE;
    private volatile long reached; // = scannedLine, for a look without the lock
    private long scannedLine;  // lines looked at
    private long scannedByte;  // where the next line starts
    private int state = FIELD_START;
    private long openLine = -1; // the line whose row is still open (a quote not closed yet)
    private long[] marks = new long[16];
    private int count;

    // what readers see: the lines that continue the row above, and how far that is known
    private volatile long[] published = new long[0];
    private volatile long settled; // lines below this are known for sure

    CsvRows(LogDocument doc, char delimiter) {
        this.doc = doc;
        this.delimiter = delimiter;
    }

    /** The rows of a CSV document (one per document and delimiter); for other formats, null. */
    public static CsvRows of(LogDocument doc, LogFormat format) {
        return doc == null || format == null || !format.isCsv() ? null : doc.csvRows(format.delimiter());
    }

    /**
     * What reads rows to split into cells: whole rows of a CSV, and the lines (cut for display)
     * of other logs.
     */
    public static ColumnProfile.Lines reader(LogDocument doc, LogFormat format) {
        CsvRows r = of(doc, format);
        return r != null ? r : doc::readLines;
    }

    /** One row's text by its first line, as {@link #reader} gives it. */
    public static java.util.function.LongFunction<String> rowText(LogDocument doc, LogFormat format) {
        ColumnProfile.Lines r = reader(doc, format);
        return line -> {
            String[] out = new String[1];
            r.read(line, 1, out);
            return out[0];
        };
    }

    // ---------------------------------------------------------------- the scan

    /** Looks at the lines added since the last time (all of them the first time). */
    public void update(BooleanSupplier stop) {
        scan(Long.MAX_VALUE, stop);
    }

    /** Whether rows are known up to {@code line} (without scanning). */
    public boolean knownUpTo(long line) {
        return settled > line && gen == doc.generation();
    }

    /** Whether some row goes over several lines (as far as scanned). */
    public boolean anyBreaks() {
        return published.length > 0;
    }

    private void ensure(long line) {
        long want = line + MAX_SPAN + 1;
        if (gen == doc.generation() && (settled >= want || reached >= doc.snapshot().completeLineCount())) {
            return;
        }
        scan(want, () -> false);
    }

    private synchronized void scan(long upTo, BooleanSupplier stop) {
        if (gen != doc.generation()) {
            gen = doc.generation();
            scannedLine = 0;
            scannedByte = 0;
            state = FIELD_START;
            openLine = -1;
            count = 0;
            published = new long[0];
            settled = 0;
            reached = 0;
        }
        LineIndex.Snapshot s = doc.snapshot();
        long complete = s.completeLineCount();
        if (scannedLine >= complete || settled >= upTo) {
            return;
        }
        byte[] buf = new byte[1 << 20];
        byte d = (byte) delimiter;
        boolean spaces = delimiter != ' ';
        outer:
        while (scannedLine < complete && (settled < upTo || openLine >= 0)) {
            if (stop.getAsBoolean()) {
                break;
            }
            int n;
            try {
                n = doc.source().read(scannedByte, buf, 0, buf.length);
            } catch (IOException | RuntimeException e) {
                break; // the file went away or changed: its rows stay as they were
            }
            if (n <= 0) {
                break;
            }
            int st = state;
            for (int i = 0; i < n; i++) {
                byte b = buf[i];
                if (b == '\n') {
                    long line = scannedLine;
                    if (st == QUOTED) {
                        if (openLine < 0) {
                            openLine = line;
                        }
                        if (line + 1 - openLine >= MAX_SPAN) {
                            // a stray quote: its line is a row of its own, read on after it
                            dropMarksAfter(openLine);
                            scannedLine = openLine + 1;
                            scannedByte = doc.lineStart(doc.snapshot(), scannedLine);
                            openLine = -1;
                            state = FIELD_START;
                            publish();
                            continue outer;
                        }
                        mark(line + 1);
                    } else {
                        openLine = -1;
                        st = FIELD_START;
                    }
                    scannedLine = line + 1;
                    if (openLine < 0) {
                        settled = scannedLine;
                    }
                    if (scannedLine >= complete || (openLine < 0 && settled >= upTo)) {
                        scannedByte += i + 1;
                        state = st;
                        break outer;
                    }
                    continue;
                }
                switch (st) {
                    case FIELD_START -> {
                        if (b == '"') {
                            st = QUOTED;
                        } else if (b != d && (b != ' ' || !spaces)) {
                            st = UNQUOTED;
                        }
                    }
                    case UNQUOTED, AFTER -> {
                        if (b == d) {
                            st = FIELD_START;
                        }
                    }
                    case QUOTED -> {
                        if (b == '"') {
                            st = QUOTE;
                        }
                    }
                    default -> { // QUOTE
                        if (b == '"') {
                            st = QUOTED;
                        } else if (b == d) {
                            st = FIELD_START;
                        } else if (b != '\r') {
                            st = AFTER;
                        }
                    }
                }
            }
            scannedByte += n;
            state = st;
            publish();
        }
        reached = scannedLine;
        publish();
    }

    private void mark(long line) {
        if (count == marks.length) {
            marks = Arrays.copyOf(marks, count * 2);
        }
        marks[count++] = line;
    }

    private void dropMarksAfter(long line) {
        while (count > 0 && marks[count - 1] > line) {
            count--;
        }
    }

    private void publish() {
        // only rows that are complete: an open one may still turn out to be a stray quote
        int k = count;
        if (openLine >= 0) {
            while (k > 0 && marks[k - 1] > openLine) {
                k--;
            }
        }
        if (k != published.length) {
            published = Arrays.copyOf(marks, k);
        }
    }

    // ---------------------------------------------------------------- reading

    /** Whether {@code line} continues the row above (a quoted value went on over a line break). */
    public boolean continues(long line) {
        ensure(line);
        return Arrays.binarySearch(published, line) >= 0;
    }

    /** Like {@link #continues}, without scanning: false where it isn't known yet. */
    public boolean continuesIfKnown(long line) {
        return Arrays.binarySearch(published, line) >= 0;
    }

    /** How many lines the row starting at {@code line} takes (1 for most). */
    public int span(long line) {
        ensure(line);
        return spanOf(published, line);
    }

    private static int spanOf(long[] p, long line) {
        int i = Arrays.binarySearch(p, line + 1);
        if (i < 0) {
            return 1;
        }
        int k = 1;
        while (i < p.length && p[i] == line + k) {
            k++;
            i++;
        }
        return k;
    }

    /** The row starting at {@code line}, whole; null past the end. */
    public String row(long line) {
        String[] out = new String[1];
        read(line, 1, out);
        return out[0];
    }

    /**
     * Rows by their first line: {@code out[i]} is the row starting at line {@code first + i},
     * whole, or null where that line continues the row above.
     */
    @Override
    public void read(long first, int count, String[] out) {
        read(first, count, out, true);
    }

    /**
     * The row starting at {@code line} as far as rows are known, without scanning (for painting):
     * whole where it is known, else the line alone, whole.
     */
    public String rowIfKnown(long line) {
        String[] out = new String[1];
        read(line, 1, out, false);
        return out[0];
    }

    private void read(long first, int count, String[] out, boolean scan) {
        if (count <= 0) {
            return;
        }
        if (scan) {
            ensure(first + count);
        }
        doc.readFullLines(first, count, out, MAX_ROW_BYTES);
        long[] p = published;
        if (p.length == 0) {
            return;
        }
        int from = Arrays.binarySearch(p, first);
        if (from < 0) {
            from = -from - 1;
        }
        if (from >= p.length || p[from] > first + count) {
            return; // no row breaks here
        }
        for (int i = 0; i < count; i++) {
            long line = first + i;
            if (out[i] == null) {
                continue;
            }
            if (Arrays.binarySearch(p, line) >= 0) {
                out[i] = null;
                continue;
            }
            int k = spanOf(p, line);
            if (k > 1) {
                StringBuilder b = new StringBuilder(out[i]);
                String[] more = new String[k - 1];
                int inRange = Math.min(k - 1, count - 1 - i);
                System.arraycopy(out, i + 1, more, 0, inRange);
                if (inRange < k - 1) {
                    String[] rest = new String[k - 1 - inRange];
                    doc.readFullLines(line + 1 + inRange, rest.length, rest, MAX_ROW_BYTES);
                    System.arraycopy(rest, 0, more, inRange, rest.length);
                }
                for (String m : more) {
                    b.append('\n').append(m == null ? "" : m);
                }
                out[i] = b.toString();
            }
        }
    }

    /**
     * The line where the {@code k}-th row (0-based, the header being row 0) starts: rows that
     * go over several lines push the ones after them down.
     */
    public long lineOfRow(long k) {
        long[] p = published;
        // the line is k + j, j being the lines before it that continue a row: the first j whose
        // continuation line comes after k + j (p[j] - j only grows)
        int lo = 0;
        int hi = p.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (p[mid] - mid > k) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return k + lo;
    }

    // ---------------------------------------------------------------- lines in memory

    /**
     * Joins lines into rows where a quoted value goes over a line break (for the first lines of
     * a file, before it is known as CSV): each item of the result is one row.
     */
    public static List<String> join(List<String> lines, char delimiter) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder row = null;
        int spanned = 0;
        for (String l : lines) {
            if (l == null) {
                continue;
            }
            if (row != null) {
                row.append('\n').append(l);
                spanned++;
            } else {
                row = new StringBuilder(l);
                spanned = 1;
            }
            if (!open(row, delimiter) || spanned >= MAX_SPAN) {
                out.add(row.toString());
                row = null;
            }
        }
        if (row != null) {
            out.add(row.toString());
        }
        return out;
    }

    /** Whether text ends inside a quoted value. */
    static boolean open(CharSequence text, char delimiter) {
        int st = FIELD_START;
        boolean spaces = delimiter != ' ';
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' && st != QUOTED) {
                st = FIELD_START;
                continue;
            }
            switch (st) {
                case FIELD_START -> {
                    if (c == '"') {
                        st = QUOTED;
                    } else if (c != delimiter && (c != ' ' || !spaces)) {
                        st = UNQUOTED;
                    }
                }
                case UNQUOTED, AFTER -> {
                    if (c == delimiter) {
                        st = FIELD_START;
                    }
                }
                case QUOTED -> {
                    if (c == '"') {
                        st = QUOTE;
                    }
                }
                default -> {
                    if (c == '"') {
                        st = QUOTED;
                    } else if (c == delimiter) {
                        st = FIELD_START;
                    } else if (c != '\r') {
                        st = AFTER;
                    }
                }
            }
        }
        return st == QUOTED;
    }
}
