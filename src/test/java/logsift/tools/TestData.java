package logsift.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a folder of test data to try Logsift by hand: logs (big ones too), CSVs, crash reports
 * and Windows crash dumps, GC logs, thread dumps and heap dumps. Nothing here is checked in:
 * {@code ./gradlew testData} writes it to {@code test-data/}, which git ignores.
 *
 * <pre>
 *   TestData &lt;dir&gt; [--big=20,200] [--quick] [--fresh]
 * </pre>
 *
 * {@code --big} lists the sizes (MB) of the big logs; {@code --quick} skips what needs a JVM of
 * its own (heap dumps, GC logs and thread dumps of live programs); {@code --fresh} writes every
 * file again (otherwise files already there are kept, so a second run is quick).
 */
public final class TestData {
    private final Path dir;
    private final boolean fresh;
    private final List<String> made = new ArrayList<>();

    private TestData(Path dir, boolean fresh) {
        this.dir = dir;
        this.fresh = fresh;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: TestData <dir> [--big=20,200] [--quick] [--fresh]");
            System.exit(2);
        }
        List<Long> big = List.of(20L, 200L);
        boolean quick = false;
        boolean fresh = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--big=")) {
                big = new ArrayList<>();
                for (String s : a.substring(6).split(",")) {
                    if (!s.isBlank()) {
                        big.add(Long.parseLong(s.trim()));
                    }
                }
            } else if (a.equals("--quick")) {
                quick = true;
            } else if (a.equals("--fresh")) {
                fresh = true;
            } else {
                throw new IllegalArgumentException("unknown option " + a);
            }
        }
        write(Path.of(args[0]), big, quick, fresh);
    }

    /** Writes everything into {@code dir}; gives the files written this time (relative paths). */
    public static List<String> write(Path dir, List<Long> bigMb, boolean quick, boolean fresh) throws Exception {
        TestData t = new TestData(dir.toAbsolutePath(), fresh);
        Files.createDirectories(t.dir);
        t.logs(bigMb);
        t.csvs();
        t.crashes();
        t.gcLogs(quick);
        t.threads(quick);
        t.heaps(quick);
        t.readme(bigMb, quick);
        System.out.println(t.made.isEmpty() ? "Nothing new: everything was already in " + t.dir
                : "Wrote " + t.made.size() + " files into " + t.dir);
        return t.made;
    }

    // ---- logs ----

    private void logs(List<Long> bigMb) throws Exception {
        Path logs = folder("logs");
        if (wanted(logs.resolve("orders-service.log"))) {
            LogGenerator.generate(logs.resolve("orders-service.log"), 2L << 20, 1);
            done(logs.resolve("orders-service.log"));
        }
        if (wanted(logs.resolve("windows-crlf.log"))) {
            new LogGenerator(2, LogGenerator.DEFAULT_TOKENS, true).write(logs.resolve("windows-crlf.log"), 1L << 20, false);
            done(logs.resolve("windows-crlf.log"));
        }
        for (long mb : bigMb) {
            Path f = logs.resolve("big-" + mb + "mb.log");
            if (wanted(f)) {
                long t0 = System.nanoTime();
                LogGenerator.Stats s = LogGenerator.generate(f, mb << 20, 42);
                System.out.printf("  %s: %,d lines in %d s%n", f.getFileName(), s.lines(), (System.nanoTime() - t0) / 1_000_000_000L);
                done(f);
            }
        }
        String text = "2026-10-21 10:00:00 INFO [main] Grüße aus München, 東京 キャッシュ ready\n"
                + "2026-10-21 10:00:01 ERROR [main] Bestellung fehlgeschlagen: Größe ungültig\n";
        text(logs.resolve("windows-utf16.log"), "﻿" + text, StandardCharsets.UTF_16LE);
        text(logs.resolve("windows-1252.log"), text.replace(", 東京 キャッシュ", ""), Charset.forName("windows-1252"));
        text(logs.resolve("app.json.log"), jsonLog(new Random(3), 2000), StandardCharsets.UTF_8);
        text(logs.resolve("access.log"), accessLog(new Random(4), 3000), StandardCharsets.UTF_8);
        text(logs.resolve("python-worker.log"), pythonLog(), StandardCharsets.UTF_8);
        text(logs.resolve("long-lines.log"), longLines(new Random(5)), StandardCharsets.UTF_8);
        // rotated: the newest is app.log, then app.log.1, then an old one zipped
        Path rotated = folder("logs/rotated");
        for (int i = 0; i < 3; i++) {
            Path f = rotated.resolve(i == 0 ? "app.log" : i == 1 ? "app.log.1" : "app.log.2.gz");
            if (wanted(f)) {
                Path plain = i == 2 ? rotated.resolve("app.log.2.tmp") : f;
                new LogGenerator(10 + i, LogGenerator.DEFAULT_TOKENS, false).write(plain, 300_000, false);
                if (i == 2) {
                    try (var out = new GZIPOutputStream(Files.newOutputStream(f))) {
                        Files.copy(plain, out);
                    }
                    Files.delete(plain);
                }
                done(f);
            }
        }
    }

    private static String jsonLog(Random r, int n) {
        String[] levels = {"INFO", "INFO", "INFO", "DEBUG", "WARN", "ERROR"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String level = levels[r.nextInt(levels.length)];
            sb.append(String.format(Locale.ROOT,
                    "{\"@timestamp\":\"2026-10-21T10:%02d:%02d.%03dZ\",\"level\":\"%s\",\"logger\":\"c.a.cart.CartService\","
                            + "\"thread\":\"http-%d\",\"traceId\":\"%016x\",\"message\":\"%s\",\"durationMs\":%d}%n",
                    i / 60 % 60, i % 60, r.nextInt(1000), level, r.nextInt(8), r.nextLong(),
                    level.equals("ERROR") ? "Checkout failed for cart " + r.nextInt(9000) : "Cart " + r.nextInt(9000) + " updated",
                    r.nextInt(level.equals("WARN") ? 5000 : 300)));
        }
        return sb.toString();
    }

    private static String accessLog(Random r, int n) {
        String[] paths = {"/api/orders", "/api/orders/42", "/api/products?page=2", "/login", "/static/app.js", "/health"};
        int[] codes = {200, 200, 200, 200, 201, 304, 404, 500, 503};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.ROOT,
                    "10.0.%d.%d - - [21/Oct/2026:10:%02d:%02d +0000] \"%s %s HTTP/1.1\" %d %d \"-\" \"Mozilla/5.0\" %.3f%n",
                    r.nextInt(4), r.nextInt(255), i / 60 % 60, i % 60, r.nextInt(5) == 0 ? "POST" : "GET",
                    paths[r.nextInt(paths.length)], codes[r.nextInt(codes.length)], r.nextInt(50_000), r.nextDouble() * 2));
        }
        return sb.toString();
    }

    private static String pythonLog() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append(String.format(Locale.ROOT, "2026-10-21 10:%02d:%02d,%03d INFO celery.worker: Task orders.sync[%d] succeeded in 0.%02ds%n",
                    i / 60, i % 60, i * 7 % 1000, 1000 + i, i % 100));
            if (i % 50 == 49) {
                sb.append(String.format(Locale.ROOT, "2026-10-21 10:%02d:%02d,500 ERROR celery.worker: Task orders.sync[%d] raised%n",
                        i / 60, i % 60, 1000 + i));
                sb.append("Traceback (most recent call last):\n")
                        .append("  File \"/app/orders/tasks.py\", line 41, in sync\n    total = order.total()\n")
                        .append("  File \"/app/orders/models.py\", line 88, in total\n    return sum(l.price for l in self.lines)\n")
                        .append("TypeError: unsupported operand type(s) for +: 'int' and 'NoneType'\n");
            }
        }
        return sb.toString();
    }

    private static String longLines(Random r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("2026-10-21 10:00:").append(String.format("%02d", i)).append(" INFO [main] payload=");
            int len = i % 5 == 0 ? 200_000 : 2_000;
            for (int k = 0; k < len; k++) {
                sb.append((char) ('a' + r.nextInt(26)));
            }
            sb.append(i == 10 ? " ERROR hidden near the end" : "").append('\n');
        }
        return sb.toString();
    }

    // ---- CSVs ----

    private void csvs() throws IOException {
        Path csv = folder("csv");
        Path sales = csv.resolve("sales.csv");
        if (wanted(sales)) {
            Random r = new Random(7);
            String[] regions = {"North", "South", "East", "West"};
            String[] products = {"Widget", "Gadget", "Doohickey"};
            StringBuilder sb = new StringBuilder("region,product,amount,quantity,time\n");
            for (int i = 0; i < 2000; i++) {
                sb.append(String.format(Locale.ROOT, "%s,%s,%.2f,%d,2026-%02d-%02d %02d:15:00%n", regions[r.nextInt(4)],
                        products[r.nextInt(3)], 10 + r.nextDouble() * 990, 1 + r.nextInt(5), 1 + i % 3, 1 + i % 28, i % 24));
            }
            Files.writeString(sales, sb);
            done(sales);
        }
        // two exports of the same table a day apart, to compare by the key "id"
        Path left = csv.resolve("orders-monday.csv");
        Path right = csv.resolve("orders-tuesday.csv");
        if (wanted(left) || wanted(right)) {
            Random r = new Random(8);
            StringBuilder a = new StringBuilder("id,customer,status,amount\n");
            StringBuilder b = new StringBuilder("id,customer,status,amount\n");
            String[] status = {"NEW", "PAID", "SHIPPED"};
            for (int i = 1; i <= 5000; i++) {
                String row = String.format(Locale.ROOT, "%d,customer-%d,%s,%.2f%n", i, r.nextInt(800), status[r.nextInt(3)],
                        r.nextDouble() * 500);
                a.append(row);
                if (i % 97 == 0) {
                    continue; // gone the next day
                }
                b.append(i % 41 == 0 ? row.replace("NEW", "CANCELLED").replace("PAID", "REFUNDED") : row);
            }
            for (int i = 5001; i <= 5040; i++) {
                b.append(String.format(Locale.ROOT, "%d,customer-%d,NEW,%.2f%n", i, r.nextInt(800), r.nextDouble() * 500));
            }
            Files.writeString(left, a);
            Files.writeString(right, b);
            done(left);
            done(right);
        }
        wide(csv.resolve("wide-2000.csv"), 2000, 300, false);
        // over 64 KB of header; each line ends with an extra comma and CRLF, as some Windows exports do
        wide(csv.resolve("wide-6000-5000-comma-crlf.csv"), 6000, 5000, true);
        Path big = csv.resolve("big-1m-rows.csv");
        if (wanted(big)) {
            Random r = new Random(9);
            try (Writer w = Files.newBufferedWriter(big)) {
                w.write("time,host,service,level,latency_ms,bytes,user\n");
                for (int i = 0; i < 1_000_000; i++) {
                    w.write(String.format(Locale.ROOT, "2026-10-21 %02d:%02d:%02d.%03d,web-%d,%s,%s,%d,%d,u%d%n",
                            i / 3_600_00 % 24, i / 6000 % 60, i / 100 % 60, i % 100 * 10, r.nextInt(12),
                            r.nextBoolean() ? "orders" : "payments", r.nextInt(50) == 0 ? "ERROR" : "INFO",
                            r.nextInt(2000), r.nextInt(100_000), r.nextInt(5000)));
                }
            }
            done(big);
        }
    }

    private void wide(Path f, int columns, int rows, boolean commaCrlf) throws IOException {
        if (!wanted(f)) {
            return;
        }
        Random r = new Random(columns + rows);
        String eol = commaCrlf ? ",\r\n" : "\n";
        try (Writer w = Files.newBufferedWriter(f)) {
            StringBuilder head = new StringBuilder("timestamp,host");
            for (int c = 0; c < columns; c++) {
                head.append(",metric_").append(String.format("%04d", c));
            }
            w.write(head.append(eol).toString());
            for (int i = 0; i < rows; i++) {
                StringBuilder row = new StringBuilder(String.format("2026-10-28 10:%02d:%02d,web-%d", i / 60 % 60, i % 60, i % 4));
                for (int c = 0; c < columns; c++) {
                    if (c % 1500 == 7) {
                        row.append(",status ").append(r.nextInt(5) == 0 ? "degraded" : "ok");
                    } else {
                        row.append(',').append(r.nextInt(1000));
                    }
                }
                w.write(row.append(eol).toString());
            }
        }
        done(f);
    }

    // ---- crashes ----

    private void crashes() throws IOException {
        Path crashes = folder("crashes");
        for (String name : List.of("hs_err_internal_guarantee.log", "hs_err_jdk8_vmthread_agent.log", "hs_err_linux_c2.log",
                "hs_err_linux_compiled_java.log", "hs_err_linux_oom_container.log", "hs_err_linux_rocksdb.log",
                "hs_err_linux_sigbus_mapped.log", "hs_err_mac_gc.log", "hs_err_mac_jni.log", "hs_err_mac_unsafe.log",
                "hs_err_windows_sqlite.log")) {
            resource("/logsift/crash/" + name, crashes.resolve(name), false);
        }
        resource("/visual/hs_err_pid31337.log", crashes.resolve("hs_err_pid31337.log"), false);
        Path dmp = crashes.resolve("orders-service.dmp");
        if (wanted(dmp)) {
            Path made = logsift.crash.TestDumps.crashDump(crashes);
            if (!made.equals(dmp)) {
                Files.move(made, dmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            done(dmp);
        }
    }

    // ---- GC logs ----

    private void gcLogs(boolean quick) throws Exception {
        Path gc = folder("gc");
        for (String name : List.of("j8-cms", "j8-g1", "j8-parallel", "j8-serial", "j11-g1", "j17-g1-time",
                "j17-serial-gconly", "j17-zgc", "j25-g1", "j25-parallel", "j25-shen", "j25-zgc")) {
            resource("/gc/" + name + ".log.gz", gc.resolve(name + ".log"), true);
        }
        if (quick) {
            return;
        }
        Path leaky = gc.resolve("live-g1-leaking.log");
        Path fine = gc.resolve("live-g1-healthy.log");
        if (wanted(leaky) || wanted(fine)) {
            System.out.println("  running two JVMs for GC logs (about 10 s)");
            Process a = gcRun(leaky, "-Xmx128m", "8", "leak");
            Process b = gcRun(fine, "-Xmx512m", "6");
            check(a, "GcLoadApp (leak)");
            check(b, "GcLoadApp");
            done(leaky);
            done(fine);
        }
    }

    private static Process gcRun(Path log, String heap, String... args) throws IOException {
        Files.deleteIfExists(log);
        List<String> cmd = new ArrayList<>(List.of(java(), heap, "-XX:+UseG1GC",
                "-Xlog:gc*:file=" + log + ":time,uptime,level,tags", "-cp", System.getProperty("java.class.path"),
                logsift.jvm.GcLoadApp.class.getName()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectErrorStream(true).start();
    }

    // ---- thread dumps ----

    private void threads(boolean quick) throws Exception {
        Path threads = folder("threads");
        for (String name : List.of("dump-21-plain.txt", "dump-21.json", "dump-25-plain.txt", "dump-25.json",
                "faulthandler.txt", "histogram-25.txt", "histogram-8.txt", "jstack-11.txt", "jstack-17.txt",
                "jstack-21.txt", "jstack-25.txt", "jstack-8.txt", "py-spy-celery.txt", "virt-21.json", "virt-21.txt",
                "virt-25.json", "virt-25.txt")) {
            resource("/threaddumps/" + name, threads.resolve(name), false);
        }
        if (quick) {
            return;
        }
        Path dump = threads.resolve("live-deadlocks.txt");
        Path hist = threads.resolve("live-histogram.txt");
        if (wanted(dump) || wanted(hist)) {
            Path jcmd = Path.of(System.getProperty("java.home"), "bin", windows() ? "jcmd.exe" : "jcmd");
            if (!Files.isExecutable(jcmd)) {
                System.out.println("  no jcmd in " + jcmd.getParent() + ": skipping the live thread dump");
                return;
            }
            Process app = logsift.jvm.FixtureApp.start(true);
            try {
                Thread.sleep(1500); // the deadlocks need a moment to form
                jcmd(jcmd, app.pid(), "Thread.print", dump);
                jcmd(jcmd, app.pid(), "GC.class_histogram", hist);
            } finally {
                logsift.jvm.FixtureApp.stop(app);
            }
            done(dump);
            done(hist);
        }
    }

    private static void jcmd(Path jcmd, long pid, String what, Path out) throws Exception {
        Process p = new ProcessBuilder(jcmd.toString(), Long.toString(pid), what).redirectErrorStream(true)
                .redirectOutput(out.toFile()).start();
        check(p, "jcmd " + what);
    }

    // ---- heap dumps ----

    private void heaps(boolean quick) throws Exception {
        if (quick) {
            return;
        }
        Path heap = folder("heap");
        Path later = heap.resolve("shop-leak.hprof");
        if (wanted(later)) {
            System.out.println("  running a JVM for a heap dump");
            logsift.heap.LeakApp.run(later, heap.resolve("shop-leak-histogram.txt"));
            done(later);
        }
        // the same program earlier, with fewer sessions: compare the two to see what grew
        Path early = heap.resolve("shop-early.hprof");
        if (wanted(early)) {
            logsift.heap.LeakApp.run(early, heap.resolve("shop-early-histogram.txt"), 5_000);
            done(early);
        }
    }

    // ---- README ----

    private void readme(List<Long> bigMb, boolean quick) throws IOException {
        StringBuilder big = new StringBuilder();
        bigMb.forEach(mb -> big.append(big.isEmpty() ? "" : ", ").append("big-").append(mb).append("mb.log"));
        String s = """
                # Logsift test data

                Made by `./gradlew testData`; nothing here is checked in. Run it again to make what's
                missing, or with `-Pfresh` to write everything again.

                | Folder | What's in it |
                |---|---|
                | logs/ | orders-service.log, %s, windows-crlf.log, windows-utf16.log, windows-1252.log, app.json.log, access.log, python-worker.log, long-lines.log, rotated/ (app.log, app.log.1, app.log.2.gz) |
                | csv/ | sales.csv (pivot), orders-monday.csv + orders-tuesday.csv (compare by id), wide-2000.csv, wide-6000-5000-comma-crlf.csv, big-1m-rows.csv |
                | crashes/ | hs_err_*.log from Linux, macOS and Windows; orders-service.dmp (a Windows crash dump) |
                | gc/ | GC logs of Java 8 to 25 (G1, CMS, Parallel, Serial, ZGC, Shenandoah)%s |
                | threads/ | jstack and JSON thread dumps of Java 8 to 25, virtual threads, histograms, py-spy, faulthandler%s |
                %s
                Programs to attach to: `./gradlew testJvms` starts JVMs with deadlocks, a busy thread,
                leaks and a JMX port, and writes their log to logs/live-spring-boot.log; Enter stops them.

                Open them with test settings, not your own: `./gradlew run -PtestSettings --args=test-data/logs/orders-service.log`
                """.formatted(big, quick ? "" : "; live-g1-leaking.log and live-g1-healthy.log from programs run here",
                quick ? "" : "; live-deadlocks.txt and live-histogram.txt from a program run here",
                quick ? "" : "| heap/ | shop-leak.hprof (a leaking cache), shop-early.hprof (the same, earlier), their histograms |\n");
        Files.writeString(dir.resolve("README.md"), s);
    }

    // ---- helpers ----

    private Path folder(String name) throws IOException {
        return Files.createDirectories(dir.resolve(name));
    }

    private boolean wanted(Path f) {
        return fresh || !Files.exists(f);
    }

    private void done(Path f) {
        made.add(dir.relativize(f).toString().replace('\\', '/'));
    }

    private void text(Path f, String s, Charset cs) throws IOException {
        if (wanted(f)) {
            Files.write(f, s.getBytes(cs));
            done(f);
        }
    }

    private void resource(String name, Path to, boolean gunzip) throws IOException {
        if (!wanted(to)) {
            return;
        }
        try (InputStream raw = TestData.class.getResourceAsStream(name)) {
            if (raw == null) {
                throw new IOException("missing test resource " + name);
            }
            try (InputStream in = gunzip ? new GZIPInputStream(raw) : raw) {
                Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        done(to);
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", windows() ? "java.exe" : "java").toString();
    }

    private static void check(Process p, String what) throws Exception {
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(what + " failed:\n" + out);
        }
    }
}
