package logsift.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import logsift.jvm.FixtureApp;

/**
 * Starts JVMs to try Logsift's JVM tools on by hand, and stops them on Enter (or when this
 * program ends): {@code ./gradlew testJvms}.
 *
 * <ul>
 * <li>"threads": two deadlocks, five threads queueing for a lock, an idle pool and one thread
 * busy on the CPU; JMX on 127.0.0.1 only, without a password; it writes a Spring Boot style log.
 * <li>"memory": the same, plus threads that leak heap and native memory, and a method to profile.
 * </ul>
 */
public final class TestJvms {
    private TestJvms() {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "test-data").toAbsolutePath();
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9010;
        Path log = Files.createDirectories(dir.resolve("logs")).resolve("live-spring-boot.log");
        Files.deleteIfExists(log);
        List<Process> started = new ArrayList<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> started.forEach(TestJvms::stop)));
        Process threads = FixtureApp.start(false, log, "-Dcom.sun.management.jmxremote.port=" + port,
                "-Dcom.sun.management.jmxremote.rmi.port=" + port, "-Dcom.sun.management.jmxremote.host=127.0.0.1",
                "-Djava.rmi.server.hostname=127.0.0.1", "-Dcom.sun.management.jmxremote.authenticate=false",
                "-Dcom.sun.management.jmxremote.ssl=false");
        started.add(threads);
        Process memory = FixtureApp.start(true);
        started.add(memory);
        System.out.println();
        System.out.println("Test JVMs running (each keeps one CPU core busy):");
        System.out.println("  " + threads.pid() + "  FixtureApp threads: deadlocks, lock queue, busy thread; JMX 127.0.0.1:" + port);
        System.out.println("                 its log: " + log);
        System.out.println("  " + memory.pid() + "  FixtureApp memory: the same, plus heap and native memory leaks");
        System.out.println("Press Enter to stop them.");
        System.out.flush();
        while (true) {
            int c = System.in.read();
            if (c < 0 || c == '\n') {
                break;
            }
        }
        started.forEach(TestJvms::stop);
        started.clear();
        System.out.println("Stopped.");
    }

    private static void stop(Process p) {
        try {
            FixtureApp.stop(p);
        } catch (Exception e) {
            p.destroyForcibly();
        }
    }
}
