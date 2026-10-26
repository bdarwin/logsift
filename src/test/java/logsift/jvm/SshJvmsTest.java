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
    void aCopyFromTheServerStopsWhenCancelled() throws Exception {
        Path remote = dir.resolve("big.hprof");
        byte[] block = new byte[1 << 20];
        try (var out = Files.newOutputStream(remote)) {
            for (int i = 0; i < 64; i++) {
                out.write(block);
            }
        }
        Path local = Files.createTempDirectory("logsift-copy").resolve("big.hprof");
        double[] seen = {0};
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> ssh.download(remote.toString(), local, f -> seen[0] = f, () -> seen[0] > 0.1));
        assertTrue(seen[0] < 0.9, "stopped early, at " + seen[0]);
        assertTrue(!Files.exists(local), "the part copied is removed");
        ssh.download(remote.toString(), local, null, () -> false);
        assertEquals(64L << 20, Files.size(local));
        Files.delete(local);
        Files.delete(remote);
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
            cleanUpLeft(all.get(0).warnings());
        }
        // Flight Recorder: recorded on the server, copied back over SFTP (and left there, as it says)
        Profile jfr = t.run(Profiler.Request.of(Profiler.Engine.JFR, Profiler.Mode.CPU, 2), null, () -> false).get(0);
        ProfilerTest.checkFixtureSampled(jfr);
        assertTrue(jfr.warnings().stream().anyMatch(w -> w.contains("stays at /tmp/logsift-")), jfr.warnings().toString());
        cleanUpLeft(jfr.warnings());
        // the heap: a histogram, and a heap dump copied back
        assertTrue(HeapHistogram.parse(t.diagnostic("GC.class_histogram"), 0).rows().size() > 100);
        Path hprof = dir.resolve("remote.hprof");
        double[] progress = {0};
        String note = t.heapDump(hprof, f -> progress[0] = f, () -> false);
        assertTrue(Files.size(hprof) > 1_000_000);
        assertEquals(1.0, progress[0], 0.001);
        assertTrue(note != null && note.contains("stays at /tmp/logsift-"), "the dump stays on the server: " + note);
        cleanUpLeft(List.of(note));
        // no room, or a folder in memory: refused before anything is written
        Path full = Files.createDirectories(dir.resolve("full"));
        Path mem = Files.createDirectories(dir.resolve("memdisk"));
        Path df = dir.resolve("bin/df");
        Files.writeString(df, "#!/bin/sh\ncase \"$*\" in\n"
                + "  *full*) printf 'Filesystem 1024-blocks Used Available Capacity Mounted\\n/dev/x 100 99 1 99%% /x\\n' ;;\n"
                + "  *memdisk*) printf 'Filesystem 1024-blocks Used Available Capacity Mounted\\ntmpfs 9999999 1 9999998 1%% /m\\n' ;;\n"
                + "  *) exec /bin/df \"$@\" ;;\nesac\n");
        df.toFile().setExecutable(true);
        try {
            t.setDumpFolder(full.toString());
            Profiler.NoRoom noRoom = org.junit.jupiter.api.Assertions.assertThrows(Profiler.NoRoom.class,
                    () -> t.heapDump(dir.resolve("x.hprof"), null, () -> false));
            assertTrue(noRoom.need() > 0 && noRoom.free() == 1024 && !noRoom.inMemory(), noRoom.getMessage());
            try (var left = Files.list(full)) {
                assertEquals(0, left.count(), "nothing written");
            }
            t.setDumpFolder(mem.toString());
            assertTrue(org.junit.jupiter.api.Assertions.assertThrows(Profiler.NoRoom.class,
                    () -> t.heapDump(dir.resolve("y.hprof"), null, () -> false)).inMemory());
            t.setDumpFolder(null);
        } finally {
            Files.delete(df);
        }
    }

    /** The "server" of these tests is this computer: the test removes what Logsift left there (Logsift doesn't). */
    private static void cleanUpLeft(List<String> notes) throws java.io.IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(/tmp/logsift-[0-9a-f]+\\.(?:hprof|jfr))").matcher(
                String.join(" ", notes));
        while (m.find()) {
            Files.deleteIfExists(Path.of(m.group(1)));
        }
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
