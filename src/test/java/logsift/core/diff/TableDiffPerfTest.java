package logsift.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two CSV files of a million rows each, the second shuffled with some rows changed, added and
 * removed: how long the comparison by key takes and how much memory it keeps. Slow, so not part
 * of {@code ./gradlew test}: run it with {@code ./gradlew perfTest}.
 */
@Tag("perf")
class TableDiffPerfTest {
    @TempDir
    Path dir;

    @Test
    void millionRowsByKey() throws IOException {
        int n = Integer.getInteger("logsift.perf.rows", 1_000_000);
        Path a = dir.resolve("a.csv");
        Path b = dir.resolve("b.csv");
        Random rnd = new Random(42);
        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        int changed = 0;
        int removed = 0;
        try (BufferedWriter w = Files.newBufferedWriter(a)) {
            w.write("order_id,customer,region,qty,price,status\n");
            for (int i = 0; i < n; i++) {
                w.write(row(i, false));
            }
        }
        try (BufferedWriter w = Files.newBufferedWriter(b)) {
            w.write("order_id,customer,region,qty,price,status\n");
            for (int k = 0; k < n; k++) {
                int i = order[k];
                if (i % 100 == 7) {
                    removed++;
                    continue;
                }
                boolean change = i % 100 == 3;
                changed += change ? 1 : 0;
                w.write(row(i, change));
            }
            for (int i = n; i < n + n / 100; i++) {
                w.write(row(i, false));
            }
        }
        System.gc();
        long before = used();
        resetPeaks();
        long t0 = System.nanoTime();
        TableDiffResult r;
        try (CsvFile fa = CsvFile.open(a, null); CsvFile fb = CsvFile.open(b, null)) {
            ColumnMap m = ColumnMap.byName(fa.header(), fb.header());
            KeySuggester.Suggestion s = KeySuggester.suggest(fa, fb, m, null);
            long tSuggest = System.nanoTime();
            assertEquals(List.of(0), s.pairs(), s.reason());
            r = TableDiff.compare(fa, fb, new TableDiff.Spec(m, s.pairs(), Set.of(), DiffOptions.DEFAULT), null, null);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long peak = peak() - before;
            System.gc();
            long kept = used() - before;
            assertEquals(changed, r.count(DiffStatus.CHANGED));
            assertEquals(removed, r.count(DiffStatus.REMOVED));
            assertEquals(n / 100, r.count(DiffStatus.ADDED));
            assertEquals(n - changed - removed, r.count(DiffStatus.SAME));
            String report = String.format(Locale.ROOT,
                    "%,d + %,d rows (%,d MB + %,d MB): key suggested in %,d ms, compared in %,d ms (%,d ms in all);"
                    + " heap kept %,d MB, peak %,d MB above the start", n, r.rightRows(), Files.size(a) >> 20,
                    Files.size(b) >> 20, (tSuggest - t0) / 1_000_000, r.millis(), ms, kept >> 20, peak >> 20);
            System.out.println(report);
            Files.writeString(Path.of(System.getProperty("logsift.home", "build")).resolve("perf-table-diff.txt"),
                    report + "\n");
        }
    }

    private static String row(int i, boolean change) {
        return "ORD-" + i + ",customer " + (i % 5000) + "," + (i % 7 == 0 ? "eu" : "us") + "," + (i % 13) + ","
                + (change ? (i % 1000) + ".99" : (i % 1000) + ".50") + "," + (i % 3 == 0 ? "paid" : "open") + "\n";
    }

    private static long used() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static void resetPeaks() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                p.resetPeakUsage();
            }
        }
    }

    private static long peak() {
        long sum = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP) {
                sum += p.getPeakUsage().getUsed();
            }
        }
        return sum;
    }
}
