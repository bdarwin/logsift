package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import logsift.core.RemoteMirror;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * JVMs over SSH, against an SSH server in the test that runs commands on this computer: the
 * listing ({@code ps}), dumps with the JDK's jcmd, with async-profiler (copied over SFTP) where
 * there is no jcmd, and sudo for another user's JVM (a stand-in sudo that wants a password).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SshJvmsTest {
    @TempDir
    static Path dir;

    private SshServer server;
    private Process app;
    private SshJvms ssh;

    @BeforeAll
    void start() throws Exception {
        assumeFalse(LocalJvms.isWindows(), "needs a Unix shell");
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path sudo = bin.resolve("sudo");
        Files.writeString(sudo, """
                #!/bin/sh
                # a stand-in sudo: -n fails (a password is needed), -S reads it from the input
                if [ "$1" = "-n" ]; then echo "sudo: a password is required" >&2; exit 1; fi
                read pw
                if [ "$pw" != "secret" ]; then echo "Sorry, try again." >&2; exit 1; fi
                shift 3
                if [ "$1" = "-u" ]; then shift 2; fi
                exec "$@"
                """);
        sudo.toFile().setExecutable(true);
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser")));
        server.setPasswordAuthenticator((user, password, session) -> user.equals("ops") && password.equals("pw"));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setCommandFactory((channel, command) -> new ProcessShellFactory(command, "/bin/sh", "-c",
                "PATH=" + bin + ":/usr/bin:/bin; export PATH; " + command).createShell(channel));
        server.start();
        app = FixtureApp.start(false, dir.resolve("app.log"));
        ssh = SshJvms.connect(new RemoteMirror.Target("127.0.0.1", server.getPort(), "ops", "", "pw", null, null),
                null, h -> true);
    }

    @AfterAll
    void stop() throws Exception {
        if (ssh != null) {
            ssh.close();
        }
        if (app != null) {
            app.getOutputStream().close();
            app.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            app.destroyForcibly();
        }
        if (server != null) {
            server.stop(true);
        }
        // what was copied to /tmp
        Path copied = Path.of("/tmp/logsift-ops-async-profiler-" + Agents.VERSION);
        if (Files.exists(copied)) {
            try (var walk = Files.walk(copied)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    private JvmProcess fixture() throws IOException {
        List<JvmProcess> all = ssh.list();
        return all.stream().filter(j -> j.pid() == app.pid()).findFirst()
                .orElseThrow(() -> new AssertionError("fixture not listed: " + all));
    }

    private static void checkDump(ThreadDump d) {
        Set<Set<String>> deadlocks = ThreadAnalysis.deadlocks(d).stream()
                .map(c -> c.stream().map(JThread::name).collect(Collectors.toSet())).collect(Collectors.toSet());
        assertEquals(Set.of(Set.of("worker-A", "worker-B"), Set.of("lock-1", "lock-2")), deadlocks);
    }

    @Test
    void findsTheJvmThatHasALogOpen() throws Exception {
        assumeTrue(Files.isDirectory(Path.of("/proc/self/fd")), "Linux servers say which process has a file open");
        List<Long> pids = ssh.list().stream().map(JvmProcess::pid).toList();
        assertEquals(Set.of(app.pid()), LogOrigin.openedBy(ssh, dir.resolve("app.log").toString(), pids));
        assertEquals(Set.of(), LogOrigin.openedBy(ssh, dir.resolve("other.log").toString(), pids));
    }

    @Test
    void listsJvms() throws Exception {
        JvmProcess p = fixture();
        assertEquals(FixtureApp.class.getName(), p.name());
        assertEquals(System.getProperty("user.name"), p.user());
        assertTrue(p.command().contains("FixtureApp"));
        assertTrue(!p.container());
    }

    @Test
    void dumpsWithTheJdkGiven() throws Exception {
        JvmProcess p = fixture();
        checkDump(ssh.dump(p, new SshJvms.Options(System.getProperty("java.home"), false, null)));
    }

    @Test
    void dumpsWithAsyncProfilerWhenThereIsNoJdk() throws Exception {
        assumeFalse(!Agents.has(ssh.platform()), "async-profiler for " + ssh.platform());
        JvmProcess p = fixture();
        checkDump(ssh.dump(p, new SshJvms.Options("/no/such/jdk", false, null)));
        // copied once, reused after
        assertTrue(ssh.asprof().endsWith("/bin/asprof"));
        assertTrue(Files.isExecutable(Path.of(ssh.asprof())));
    }

    @Test
    void usesSudoForAnotherUsersJvm() throws Exception {
        JvmProcess real = fixture();
        JvmProcess tomcats = new JvmProcess(real.pid(), real.name(), real.command(), "tomcat", "", false);
        String jdk = System.getProperty("java.home");
        assertThrows(SshJvms.SudoPasswordNeeded.class,
                () -> ssh.dump(tomcats, new SshJvms.Options(jdk, true, null)), "sudo -n needs a password");
        assertThrows(SshJvms.SudoPasswordNeeded.class,
                () -> ssh.dump(tomcats, new SshJvms.Options(jdk, true, "wrong")));
        checkDump(ssh.dump(tomcats, new SshJvms.Options(jdk, true, "secret")));
    }

    @Test
    void profilesOverSsh() throws Exception {
        JvmProcess p = fixture();
        SshJvms.Options o = new SshJvms.Options(System.getProperty("java.home"), false, null);
        Profiler.Target t = Profiler.remote(p, ssh, o);
        if (Agents.has(ssh.platform())) {
            assertEquals(Profiler.Engine.ASYNC_PROFILER, t.engines().get(0));
            ProfilerTest.checkHotLoop(t.run(Profiler.Request.of(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.CPU, 2),
                    null, () -> false).get(0));
            // a recording written by the JVM on the server, copied back
            List<Profile> all = t.run(Profiler.Request.of(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.ALL, 2),
                    null, () -> false);
            assertTrue(all.size() > 1, "several views: " + all.stream().map(Profile::title).toList());
        }
        // Flight Recorder: recorded on the server, copied back over SFTP
        ProfilerTest.checkFixtureSampled(t.run(Profiler.Request.of(Profiler.Engine.JFR, Profiler.Mode.CPU, 2), null,
                () -> false).get(0));
        // the heap: a histogram, and a heap dump copied back
        assertTrue(HeapHistogram.parse(t.diagnostic("GC.class_histogram"), 0).rows().size() > 100);
        Path hprof = dir.resolve("remote.hprof");
        double[] progress = {0};
        t.heapDump(hprof, f -> progress[0] = f, () -> false);
        assertTrue(Files.size(hprof) > 1_000_000);
        assertEquals(1.0, progress[0], 0.001);
    }

    @Test
    void words() {
        assertEquals("'it'\\''s'", SshJvms.q("it's"));
        assertEquals("2 d 3 h", SshJvms.uptime("2-03:04:05"));
        assertEquals("27 h 4 min", SshJvms.uptime("1-03:04:05"));
        assertEquals("4 min 5 s", SshJvms.uptime("04:05"));
        assertEquals("linux-x64", Agents.platform("Linux", "x86_64"));
        assertEquals("linux-arm64", Agents.platform("Linux", "aarch64"));
        assertEquals("macos", Agents.platform("Darwin", "arm64"));
        assertEquals(null, Agents.platform("Linux", "s390x"));
    }
}
