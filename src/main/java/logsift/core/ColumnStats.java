package logsift.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

/**
 * Counts the values of one column (thread, logger, level, host, status...) over a log, most
 * frequent first. Lines are split with the log's {@link LogFormat}, in parallel chunks.
 */
public final class ColumnStats {
    private ColumnStats() {}

    public record Value(String value, long count, long firstLine) {}

    public record Result(String column, List<Value> values, long lines, long millis,
                         boolean truncated) {}

    private static final int MAX_VALUES = 100_000;

    public static Result count(LogDocument doc, LogFormat format, String column,
                               BooleanSupplier cancelled) {
        long start = System.nanoTime();
        LineIndex.Snapshot s = doc.snapshot();
        long to = s.completeLineCount();
        Map<String, long[]> total = new HashMap<>(); // value -> {count, firstLine}
        long lines = 0;
        boolean truncated = false;
        int maxInFlight = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        ArrayDeque<Future<Local>> inFlight = new ArrayDeque<>();
        int chunk = 0;
        long lo = 0;
        try {
            while (lo < to || !inFlight.isEmpty()) {
                while (lo < to && inFlight.size() < maxInFlight && chunk < s.chunkCount()) {
                    LineIndex.Chunk c = s.chunk(chunk++);
                    long hi = Math.min(to, c.endLine());
                    if (hi > lo) {
                        long a = lo;
                        long sb = doc.lineStart(s, a);
                        long eb = doc.lineStart(s, hi);
                        inFlight.add(LogDocument.WORKERS.submit(
                                () -> scan(doc, format, column, a, sb, eb, cancelled)));
                        lo = hi;
                    }
                }
                if (inFlight.isEmpty()) {
                    break;
                }
                if (cancelled.getAsBoolean()) {
                    inFlight.forEach(f -> f.cancel(false));
                    break;
                }
                Local l = inFlight.removeFirst().get();
                lines += l.lines;
                for (var e : l.counts.entrySet()) {
                    long[] t = total.get(e.getKey());
                    if (t == null) {
                        if (total.size() >= MAX_VALUES) {
                            truncated = true;
                            continue;
                        }
                        total.put(e.getKey(), e.getValue());
                    } else {
                        t[0] += e.getValue()[0];
                        t[1] = Math.min(t[1], e.getValue()[1]);
                    }
                }
                truncated |= l.truncated;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(c);
        }
        List<Value> values = new ArrayList<>(total.size());
        total.forEach((k, v) -> values.add(new Value(k, v[0], v[1])));
        values.sort(Comparator.comparingLong((Value v) -> -v.count()).thenComparing(Value::value));
        return new Result(column, values, lines, (System.nanoTime() - start) / 1_000_000, truncated);
    }

    private record Local(Map<String, long[]> counts, long lines, boolean truncated) {}

    private static final ThreadLocal<byte[]> BUFFERS = ThreadLocal.withInitial(() -> new byte[0]);

    private static Local scan(LogDocument doc, LogFormat format, String column, long firstLine,
                              long startByte, long endByte, BooleanSupplier cancelled) {
        CsvRows rows = CsvRows.of(doc, format);
        int len = (int) Math.min(endByte - startByte, 256L * 1024 * 1024);
        byte[] buf = BUFFERS.get();
        if (buf.length < len) {
            buf = new byte[Math.max(len, LineIndex.DEFAULT_CHUNK_SIZE + 64 * 1024)];
            BUFFERS.set(buf);
        }
        int n;
        try {
            n = doc.source().read(startByte, buf, 0, len);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, long[]> counts = new HashMap<>();
        int col = format.columns().indexOf(column);
        long line = firstLine;
        long lines = 0;
        boolean truncated = false;
        int pos = 0;
        while (pos < n) {
            int nl = ByteScan.indexOf(buf, pos, n, (byte) '\n');
            int end = nl < 0 ? n : nl;
            if (end > pos && buf[end - 1] == '\r') {
                end--;
            }
            String[] cells;
            if (rows == null) {
                cells = format.split(new String(buf, pos, Math.min(end - pos, 4000), doc.charset()), null);
            } else if (rows.continues(line)) {
                cells = null; // part of the row above (a quoted value over a line break)
            } else {
                // a table's row, whole: a wide one is long, and one may go over several lines
                String text = rows.span(line) > 1 ? rows.row(line) : new String(buf, pos, end - pos, doc.charset());
                cells = text == null ? null : format.split(text, null);
            }
            if (cells != null) {
                String v = cells[col];
                if (v != null) {
                    long[] c = counts.get(v);
                    if (c != null) {
                        c[0]++;
                    } else if (counts.size() < MAX_VALUES) {
                        counts.put(v, new long[] {1, line});
                    } else {
                        truncated = true;
                    }
                    lines++;
                }
            }
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
            line++;
            if ((line & 8191) == 0 && cancelled.getAsBoolean()) {
                break;
            }
        }
        return new Local(counts, lines, truncated);
    }
}
