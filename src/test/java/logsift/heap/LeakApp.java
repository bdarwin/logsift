package logsift.heap;

import java.lang.management.ManagementFactory;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.management.ObjectName;

/**
 * A program with a known leak, for heap dump tests (run in a JVM of its own): 20,000 sessions in a
 * static map, each holding 512 bytes; 1,000 orders in a local variable of main; 5 MB behind a soft
 * reference. Waste: each session has its own copy of the text "ACTIVE" and an empty list; a map
 * once filled and then emptied keeps its big table. Writes the JVM's class histogram, then a heap
 * dump, and ends.
 */
public final class LeakApp {
    static final int SESSIONS = 20_000;
    static final int ORDERS = 1_000;
    static final Map<String, Session> CACHE = new HashMap<>();
    static final Map<Integer, String> EMPTIED = new HashMap<>();
    static SoftReference<byte[]> soft;

    static final class Session {
        final String id;
        final byte[] payload = new byte[512];
        @SuppressWarnings("StringOperationCanBeSimplified")
        final String status = new String("ACTIVE");
        final List<String> tags = new ArrayList<>(10);
        final long created;
        int hits;

        Session(String id, long created) {
            this.id = id;
            this.created = created;
        }
    }

    record Order(int number, String customer) {}

    private LeakApp() {
    }

    public static void main(String[] args) throws Exception {
        Path dump = Path.of(args[0]);
        Path histogram = Path.of(args[1]);
        for (int i = 0; i < SESSIONS; i++) {
            CACHE.put("session-" + i, new Session("session-" + i, i));
        }
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < ORDERS; i++) {
            orders.add(new Order(i, "customer-" + (i % 50)));
        }
        for (int i = 0; i < 1000; i++) {
            EMPTIED.put(i, "x");
        }
        for (int i = 10; i < 1000; i++) {
            EMPTIED.remove(i);
        }
        soft = new SoftReference<>(new byte[5 << 20]);
        Object hist = ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                new Object[] {new String[0]}, new String[] {String[].class.getName()});
        Files.writeString(histogram, String.valueOf(hist));
        com.sun.management.HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(
                com.sun.management.HotSpotDiagnosticMXBean.class);
        bean.dumpHeap(dump.toString(), true);
        System.out.println("orders " + orders.size()); // keeps the local alive until after the dump
    }

    /** Runs this program and waits for its dump; returns the histogram text. */
    public static String run(Path dump, Path histogram) throws Exception {
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path javaExe = Path.of(System.getProperty("java.home"), "bin", exe);
        Files.deleteIfExists(dump);
        Process p = new ProcessBuilder(javaExe.toString(), "-Xmx256m", "-cp", System.getProperty("java.class.path"),
                LeakApp.class.getName(), dump.toString(), histogram.toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.waitFor() != 0 || !Files.exists(dump)) {
            throw new IllegalStateException("LeakApp failed:\n" + out);
        }
        return Files.readString(histogram);
    }
}
