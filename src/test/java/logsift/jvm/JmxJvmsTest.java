package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/** A JVM reached through its JMX port: a {@link FixtureApp} started with one. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JmxJvmsTest {
    private Process app;
    private int port;

    static int freePort() throws IOException {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static String[] jmxArgs(int port, boolean auth) {
        return new String[] {"-Dcom.sun.management.jmxremote.port=" + port,
            "-Dcom.sun.management.jmxremote.rmi.port=" + port, "-Djava.rmi.server.hostname=127.0.0.1",
            "-Dcom.sun.management.jmxremote.authenticate=" + auth, "-Dcom.sun.management.jmxremote.ssl=false"};
    }

    @BeforeAll
    void start() throws Exception {
        port = freePort();
        app = FixtureApp.start(true, null, jmxArgs(port, false));
    }

    @AfterAll
    void stop() throws Exception {
        FixtureApp.stop(app);
    }

    private JmxJvms.Session connect() throws IOException {
        return JmxJvms.connect(new JmxJvms.Address("127.0.0.1", port, "", null, false), 30);
    }

    @Test
    void describesTheJvmAndTakesDumps() throws Exception {
        try (JmxJvms.Session s = connect()) {
            assertEquals(app.pid(), s.process().pid());
            assertEquals("logsift.jvm.FixtureApp", s.process().name());
            assertEquals("logsift.jvm.FixtureApp (" + app.pid() + ") on 127.0.0.1:" + port, s.label());
            assertTrue(s.isConnected());
            ThreadDump d = s.dump();
            Set<Set<String>> deadlocks = ThreadAnalysis.deadlocks(d).stream()
                    .map(c -> c.stream().map(JThread::name).collect(Collectors.toSet())).collect(Collectors.toSet());
            assertEquals(Set.of(Set.of("worker-A", "worker-B"), Set.of("lock-1", "lock-2")), deadlocks);
            HeapHistogram h = HeapHistogram.parse(s.diagnostic("GC.class_histogram"), System.currentTimeMillis());
            assertFalse(h.rows().isEmpty());
            IOException e = assertThrows(IOException.class, () -> s.diagnostic("GC.heap_dump", "/tmp/x.hprof"));
            assertTrue(e.getMessage().contains("SSH"), e.getMessage());
        }
    }

    @Test
    void profilesWithFlightRecorderStreamedBack() throws Exception {
        try (JmxJvms.Session s = connect()) {
            Profiler.Target t = Profiler.jmx(s);
            assertEquals(List.of(Profiler.Engine.JFR), t.engines());
            assertNotNull(t.noHeapDump());
            assertThrows(IOException.class, () -> t.run(Profiler.Request.of(Profiler.Engine.ASYNC_PROFILER,
                    Profiler.Mode.CPU, 1), null, () -> false));
            List<Profile> views = t.run(Profiler.Request.of(Profiler.Engine.JFR, Profiler.Mode.ALL, 3), null, () -> false);
            assertNotNull(views);
            assertFalse(views.isEmpty());
            assertTrue(views.stream().anyMatch(p -> p.total() > 0), "something was recorded: " + views);
            assertTrue(views.get(0).title().contains("on 127.0.0.1:" + port), views.get(0).title());
            // cancelled: nothing comes back, and the JVM keeps no recording
            assertEquals(null, t.run(Profiler.Request.of(Profiler.Engine.JFR, Profiler.Mode.CPU, 30), null, () -> true));
            assertFalse(s.diagnostic("JFR.check").contains("logsift-"), s.diagnostic("JFR.check"));
        }
    }

    @Test
    void saysWhyItCannotConnect() throws Exception {
        int closed = freePort();
        IOException e = assertThrows(IOException.class,
                () -> JmxJvms.connect(new JmxJvms.Address("127.0.0.1", closed, "", null, false), 30));
        assertTrue(e.getMessage().contains("nothing answers"), e.getMessage());
    }

    @Test
    void withAPassword(@TempDir Path dir) throws Exception {
        assumeFalse(LocalJvms.isWindows(), "the password file must be private, which Windows checks differently");
        Path pw = dir.resolve("jmx.password");
        Path access = dir.resolve("jmx.access");
        Files.writeString(pw, "ops secret\n");
        Files.writeString(access, "ops readonly\n");
        Files.setPosixFilePermissions(pw, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        int p = freePort();
        String[] args = jmxArgs(p, true);
        String[] all = java.util.Arrays.copyOf(args, args.length + 2);
        all[args.length] = "-Dcom.sun.management.jmxremote.password.file=" + pw;
        all[args.length + 1] = "-Dcom.sun.management.jmxremote.access.file=" + access;
        Process secured = FixtureApp.start(false, null, all);
        try {
            IOException e = assertThrows(IOException.class,
                    () -> JmxJvms.connect(new JmxJvms.Address("127.0.0.1", p, "ops", "wrong", false), 30));
            assertTrue(e.getMessage().contains("refused"), e.getMessage());
            try (JmxJvms.Session s = JmxJvms.connect(new JmxJvms.Address("127.0.0.1", p, "ops", "secret", false), 30)) {
                assertEquals(secured.pid(), s.process().pid());
                // read-only users can't run diagnostic commands: say so
                IOException d = assertThrows(IOException.class, s::dump);
                assertNotNull(d.getMessage());
            }
        } finally {
            FixtureApp.stop(secured);
        }
    }
}
