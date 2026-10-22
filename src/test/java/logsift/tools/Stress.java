package logsift.tools;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import logsift.core.ColumnStats;
import logsift.core.FilterEngine;
import logsift.core.Formats;
import logsift.core.Formula;
import logsift.core.LogDocument;
import logsift.core.SearchQuery;
import logsift.core.SearchSession;
import logsift.core.TimeIndex;

/**
 * Stress test for very large logs (not part of the normal tests: it writes many gigabytes).
 * {@code ./gradlew stress -PstressGb=10 -PstressDir=/tmp/stress} writes a log of that size, a
 * file with one huge line and a file with very many short lines, then measures opening,
 * indexing, jumping to the end, searching, filtering, the timeline, the tree of loggers and
 * memory. Files are deleted afterwards unless {@code -Dstress.keep=true}.
 */
public final class Stress {
    private Stress() {}

    public static void main(String[] args) throws Exception {
        double gb = Double.parseDouble(args.length > 0 ? args[0] : "10");
        Path dir = Path.of(args.length > 1 ? args[1] : "build/stress");
        Files.createDirectories(dir);
        boolean keep = Boolean.getBoolean("stress.keep");
        System.out.printf("Logsift stress test: %.1f GB, %d CPUs, max heap %d MB%n%n", gb,
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20);

        Path big = dir.resolve("stress-" + gb + "gb.log");
        if (!Files.exists(big) || Files.size(big) < (long) (gb * (1L << 30)) * 99 / 100) {
            long t = System.nanoTime();
            var s = LogGenerator.generate(big, (long) (gb * (1L << 30)), 1);
            System.out.printf("wrote %s: %,d lines in %.0f s%n", big, s.lines(), secs(t));
        }
        try {
            bigLog(big);
        } finally {
            if (!keep) {
                Files.deleteIfExists(big);
            }
        }

        // one 200 MB line (a JSON dump, minified)
        Path oneLine = dir.resolve("one-long-line.log");
        writeOneLongLine(oneLine, 200L << 20);
        try {
            longLine(oneLine);
        } finally {
            Files.deleteIfExists(oneLine);
        }

        // 300 million very short lines (line count, not bytes, is the load)
        Path many = dir.resolve("many-short-lines.log");
        writeShortLines(many, 300_000_000L);
        try {
            manyLines(many);
        } finally {
            Files.deleteIfExists(many);
        }
    }

    private static void bigLog(Path f) throws Exception {
        System.out.printf("%n== %s (%,d MB)%n", f.getFileName(), Files.size(f) >> 20);
        long t = System.nanoTime();
        LogDocument doc = LogDocument.open(f);
        String[] first = new String[50];
        doc.readLines(0, 50, first);
        report("first lines readable", t);
        waitIndexed(doc);
        report(String.format("indexed: %,d lines", doc.lineCount()), t);
        heap("after indexing");

        t = System.nanoTime();
        String[] last = new String[50];
        doc.readLines(doc.lineCount() - 50, 50, last);
        report("read the last 50 lines", t);
        t = System.nanoTime();
        String[] mid = new String[50];
        for (int i = 0; i < 20; i++) {
            doc.readLines((long) (Math.random() * (doc.lineCount() - 50)), 50, mid);
        }
        report("20 random jumps (50 lines each)", t);

        search(doc, new SearchQuery("Payment failed", true, false, false), "Find All: rare text");
        search(doc, new SearchQuery("ERROR", true, false, true), "Find All: common word");
        search(doc, new SearchQuery("user=u\\d+7 ", true, true, false), "Find All: regex");
        heap("after searches");

        t = System.nanoTime();
        FilterEngine fe = new FilterEngine(doc);
        fe.configure(List.of(new FilterEngine.Spec('A', new SearchQuery("ERROR", true, false, true)),
                new FilterEngine.Spec('B', new SearchQuery("timeout", false, false, false))),
                Formula.parse("A AND NOT B"));
        fe.awaitIdle(Long.MAX_VALUE);
        while (fe.isRunning()) {
            Thread.sleep(20);
        }
        report(String.format("filter ERROR AND NOT timeout: %,d lines", fe.result().matchCount()), t);
        fe.close();

        String[] sample = new String[2000];
        doc.readLines(0, sample.length, sample);
        Formats.Detected d = Formats.detect(List.of(sample), List.of(), f.getFileName().toString(), null, 2026);
        System.out.printf("   layout: %s, entries: %s, times: %s%n", d.columns(), d.entries(), d.times());
        if (d.times() != null) {
            t = System.nanoTime();
            TimeIndex ti = new TimeIndex(doc, d.times()).start();
            ti.awaitIdle(Long.MAX_VALUE);
            report(String.format("timeline: %,d samples", ti.samples().n()), t);
            ti.close();
        }
        if (d.columns() != null && d.columns().columns().contains("thread")) {
            String col = d.columns().columns().contains("logger") ? "logger" : "thread";
            t = System.nanoTime();
            ColumnStats.Result r = ColumnStats.count(doc, d.columns(), col, () -> false);
            report(String.format("tree of %s: %,d values over %,d lines", col, r.values().size(), r.lines()), t);
        }
        heap("at the end");
        doc.close();
    }

    private static void longLine(Path f) throws Exception {
        System.out.printf("%n== %s (%,d MB in one line)%n", f.getFileName(), Files.size(f) >> 20);
        long t = System.nanoTime();
        LogDocument doc = LogDocument.open(f);
        waitIndexed(doc);
        report(String.format("indexed: %,d lines", doc.lineCount()), t);
        t = System.nanoTime();
        String[] l = new String[1];
        doc.readLines(0, 1, l);
        report(String.format("read the line: %,d chars", l[0] == null ? 0 : l[0].length()), t);
        search(doc, new SearchQuery("needle-at-the-end", true, false, false), "Find All in the long line");
        heap("long line");
        doc.close();
    }

    private static void manyLines(Path f) throws Exception {
        System.out.printf("%n== %s (%,d MB)%n", f.getFileName(), Files.size(f) >> 20);
        long t = System.nanoTime();
        LogDocument doc = LogDocument.open(f);
        waitIndexed(doc);
        report(String.format("indexed: %,d lines", doc.lineCount()), t);
        heap("after indexing");
        t = System.nanoTime();
        String[] last = new String[50];
        doc.readLines(doc.lineCount() - 50, 50, last);
        report("read the last 50 lines: \"" + last[49] + "\"", t);
        search(doc, new SearchQuery("x7777", true, false, false), "Find All");
        doc.close();
    }

    // ---------------------------------------------------------------- helpers

    private static void search(LogDocument doc, SearchQuery q, String what) throws InterruptedException {
        long t = System.nanoTime();
        SearchSession s = new SearchSession(doc, q).start();
        s.awaitIdle(Long.MAX_VALUE);
        report(String.format("%s: %,d hits%s", what, s.hits().size(), s.isLimitReached() ? " (limit)" : ""), t);
        s.close();
    }

    private static void waitIndexed(LogDocument doc) throws InterruptedException {
        while (doc.isIndexing()) {
            Thread.sleep(10);
        }
    }

    private static void report(String what, long startNanos) {
        System.out.printf("   %-60s %8.2f s%n", what, secs(startNanos));
    }

    private static double secs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    private static void heap(String when) {
        System.gc();
        Runtime r = Runtime.getRuntime();
        long used = (r.totalMemory() - r.freeMemory()) >> 20;
        long rss = -1;
        try {
            var os = ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
            rss = os.getCommittedVirtualMemorySize() >> 20;
        } catch (RuntimeException e) {
            // not available
        }
        System.out.printf("   memory %-52s heap %,d MB (virtual %,d MB)%n", when + ":", used, rss);
    }

    private static void writeOneLongLine(Path f, long bytes) throws IOException {
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(f), 1 << 20)) {
            o.write("2026-10-21 10:00:00.000 [main] INFO  dump - ".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = "{\"id\":12345,\"name\":\"item\",\"tags\":[\"a\",\"b\"],\"ok\":true},"
                    .getBytes(StandardCharsets.US_ASCII);
            for (long n = 0; n < bytes; n += chunk.length) {
                o.write(chunk);
            }
            o.write("needle-at-the-end\n".getBytes(StandardCharsets.US_ASCII));
            o.write("2026-10-21 10:00:01.000 [main] INFO  dump - done\n".getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void writeShortLines(Path f, long lines) throws IOException {
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(f), 1 << 22)) {
            byte[] b = new byte[16];
            for (long i = 0; i < lines; i++) {
                int n = 0;
                b[n++] = 'x';
                long v = i;
                int start = n;
                do {
                    b[n++] = (byte) ('0' + v % 10);
                    v /= 10;
                } while (v > 0);
                for (int a = start, z = n - 1; a < z; a++, z--) {
                    byte tmp = b[a];
                    b[a] = b[z];
                    b[z] = tmp;
                }
                b[n++] = '\n';
                o.write(b, 0, n);
            }
        }
    }
}
