package logsift.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Writes chosen lines of a log to a file, copying the original bytes (line endings, encoding
 * and all) in runs of consecutive lines, so exporting "everything except DEBUG" from a 10 GB
 * log is mostly a few large block copies.
 */
public final class LineExporter {
    private LineExporter() {}

    /** Lines to export, as runs. */
    public interface Runs {
        void forEach(FilterResult.RunConsumer out) throws IOException;
    }

    /** Exports every run of lines; returns the number of lines written. */
    public static long export(LogDocument doc, Runs runs, Path out, DoubleConsumer progress,
                              BooleanSupplier cancelled) throws IOException {
        LineIndex.Snapshot s = doc.snapshot();
        long[] lines = {0};
        try (FileChannel dst = FileChannel.open(out, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
             FileChannel src = FileChannel.open(doc.path(), StandardOpenOption.READ)) {
            long total = Math.max(1, s.lineCount);
            runs.forEach((from, to) -> {
                if (cancelled.getAsBoolean()) {
                    throw new IOException("cancelled");
                }
                to = Math.min(to, s.lineCount);
                if (to <= from) {
                    return;
                }
                long a = doc.lineStart(s, from);
                long b = doc.lineStart(s, to);
                long pos = a;
                while (pos < b) {
                    long n = src.transferTo(pos, b - pos, dst);
                    if (n <= 0) {
                        break;
                    }
                    pos += n;
                }
                // the last line of the file may have no newline; end the exported line anyway
                if (to == s.lineCount && !s.endsWithNewline) {
                    dst.write(ByteBuffer.wrap(new byte[] {'\n'}));
                }
                lines[0] += to - from;
                if (progress != null) {
                    progress.accept((double) to / total);
                }
            });
        }
        return lines[0];
    }

    /** Runs for a sorted list of line numbers (for example Find All hits). */
    public static Runs of(HitList hits) {
        return out -> {
            int n = hits.size();
            int i = 0;
            while (i < n) {
                long a = hits.get(i);
                int j = i;
                while (j + 1 < n && hits.get(j + 1) == hits.get(j) + 1) {
                    j++;
                }
                out.run(a, hits.get(j) + 1);
                i = j + 1;
            }
        };
    }
}
