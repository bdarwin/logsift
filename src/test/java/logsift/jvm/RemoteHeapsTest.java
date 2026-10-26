package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import logsift.core.RemoteMirror;
import logsift.heap.HeapDump;
import logsift.heap.LeakApp;
import logsift.heap.RemoteHeap;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * A heap dump read on a "server" (an SSH server in the test that runs commands on this computer,
 * with its own HOME): the reader is copied over, started, reached through SSH, survives the app
 * disconnecting, and ends when told to.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteHeapsTest {
    @TempDir
    static Path dir;

    private SshServer server;
    private SshJvms ssh;
    private Path home;
    private Path dump;

    @BeforeAll
    void start() throws Exception {
        assumeFalse(LocalJvms.isWindows(), "needs a Unix shell");
        home = Files.createDirectories(dir.resolve("home"));
        dump = dir.resolve("app.hprof");
        LeakApp.run(dump, dir.resolve("histogram.txt"));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser")));
        server.setPasswordAuthenticator((user, password, session) -> user.equals("ops") && password.equals("pw"));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        String java = Path.of(System.getProperty("java.home"), "bin").toString();
        server.setCommandFactory((channel, command) -> new ProcessShellFactory(command, "/bin/sh", "-c",
                "HOME=" + home + "; PATH=" + java + ":/usr/bin:/bin; export HOME PATH; " + command)
                .createShell(channel));
        server.start();
        ssh = SshJvms.connect(new RemoteMirror.Target("127.0.0.1", server.getPort(), "ops", "", "pw", null, null),
                null, h -> true);
    }

    @AfterAll
    void stop() throws Exception {
        if (ssh != null) {
            ssh.close();
        }
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    void readOnTheServerReconnectedAndStopped() throws Exception {
        RemoteHeaps.Plan plan = RemoteHeaps.plan(ssh, dump.toString(), null);
        assertEquals(Files.size(dump), plan.dumpBytes());
        assertNotNull(plan.java(), "a Java found");
        assertTrue(plan.javaVersion() >= 8, "version " + plan.javaVersion());
        assertTrue(plan.freeBytes() > 0, "free disk " + plan.freeBytes());
        assertTrue(plan.stateDir().startsWith(home.toString()), plan.stateDir());
        assertNull(plan.running());
        assertNull(plan.problem());

        RemoteHeap r = RemoteHeaps.open(ssh, plan, null);
        r.awaitReady(null, null);
        try (HeapDump local = HeapDump.open(dump, null, null, null)) {
            assertEquals(local.reachableBytes(), r.reachableBytes());
            assertEquals(local.findings(), r.findings());
        }
        assertTrue(Files.exists(home.resolve(".logsift/heap")), "its files are in the server user's home");
        r.close(); // the app goes away: the reader stays

        RemoteHeaps.Plan again = RemoteHeaps.plan(ssh, dump.toString(), null);
        assertNotNull(again.running(), "still running");
        RemoteHeap back = RemoteHeaps.open(ssh, again, null);
        back.awaitReady(null, null);
        assertTrue(back.findings().get(0).title().contains("LeakApp.CACHE"), back.findings().get(0).title());
        back.stopServer();
        back.close();
        long until = System.currentTimeMillis() + 30_000;
        while (RemoteHeaps.plan(ssh, dump.toString(), null).running() != null && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        assertNull(RemoteHeaps.plan(ssh, dump.toString(), null).running(), "stopped");
        assertTrue(!Files.exists(Path.of(plan.workDir())), "the index is removed");
    }

    @Test
    void notEnoughDiskIsSaidBeforeStarting() throws Exception {
        RemoteHeaps.Plan p = RemoteHeaps.plan(ssh, dump.toString(), null);
        RemoteHeaps.Plan tight = new RemoteHeaps.Plan(p.host(), p.dump(), 40L << 30, p.stateDir(), p.workDir(),
                p.java(), p.javaVersion(), 10L << 30, p.memoryBytes(), false, null);
        assertTrue(tight.problem().contains("only 10.0 GB free"), tight.problem());
        RemoteHeaps.Plan noJava = new RemoteHeaps.Plan(p.host(), p.dump(), p.dumpBytes(), p.stateDir(), p.workDir(),
                null, 0, p.freeBytes(), p.memoryBytes(), false, null);
        assertTrue(noJava.problem().startsWith("There is no Java"), noJava.problem());
    }

    @Test
    void aDumpThatIsNotThereIsSaid() {
        Exception e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> RemoteHeaps.plan(ssh, dir.resolve("missing.hprof").toString(), null));
        assertTrue(e.getMessage().contains("cannot read"), e.getMessage());
    }
}
