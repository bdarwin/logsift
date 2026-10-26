package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import logsift.core.RemoteMirror;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dump copied from one "server" to another (two SSH servers in the test, running commands
 * here): directly when the second can sign in to the first with a key, else through this
 * computer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteCopyTest {
    @TempDir
    static Path dir;

    private SshServer prod;
    private SshServer other;
    private SshJvms toProd;
    private SshJvms toOther;
    private Path dump;
    private byte[] data;
    private Path otherHome;

    private SshServer server(String name, Path home, boolean keys) throws Exception {
        SshServer s = SshServer.setUpDefaultServer();
        s.setHost("127.0.0.1");
        s.setPort(0);
        s.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve(name + "-hostkey.ser")));
        s.setPasswordAuthenticator((u, pw, sess) -> u.equals("ops") && pw.equals("pw"));
        if (keys) {
            s.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        }
        s.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        Path bin = home.resolve("bin");
        s.setCommandFactory((channel, command) -> new ProcessShellFactory(command, "/bin/sh", "-c",
                "HOME=" + home + "; PATH=" + bin + ":/usr/bin:/bin; export HOME PATH; " + command)
                .createShell(channel));
        s.start();
        return s;
    }

    @BeforeAll
    void start() throws Exception {
        assumeFalse(LocalJvms.isWindows(), "needs a Unix shell");
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/ssh")) && Files.isExecutable(Path.of("/usr/bin/ssh-keygen")),
                "needs OpenSSH");
        Path prodHome = Files.createDirectories(dir.resolve("prod"));
        otherHome = Files.createDirectories(dir.resolve("other"));
        Files.createDirectories(prodHome.resolve("bin"));
        Path ssh = Files.createDirectories(otherHome.resolve(".ssh"));
        // the other machine's ssh: its own key and known hosts (OpenSSH reads ~ from the user database,
        // not HOME, so the wrapper names them)
        Files.createDirectories(otherHome.resolve("bin"));
        Path wrapper = otherHome.resolve("bin/ssh");
        Files.writeString(wrapper, "#!/bin/sh\nexec /usr/bin/ssh -F /dev/null -o UserKnownHostsFile=" + ssh
                + "/known_hosts -o IdentitiesOnly=yes -o IdentityAgent=none -i " + ssh + "/id_test \"$@\"\n");
        wrapper.toFile().setExecutable(true);
        dump = prodHome.resolve("app.hprof");
        data = new byte[3 << 20];
        new Random(7).nextBytes(data);
        Files.write(dump, data);
        prod = server("prod", prodHome, true);
        other = server("other", otherHome, false);
        toProd = SshJvms.connect(target(prod), null, h -> true);
        toOther = SshJvms.connect(target(other), null, h -> true);
    }

    private static RemoteMirror.Target target(SshServer s) {
        return new RemoteMirror.Target("127.0.0.1", s.getPort(), "ops", "", "pw", null, null);
    }

    @AfterAll
    void stop() throws Exception {
        for (SshJvms s : new SshJvms[] {toProd, toOther}) {
            if (s != null) {
                s.close();
            }
        }
        for (SshServer s : new SshServer[] {prod, other}) {
            if (s != null) {
                s.stop(true);
            }
        }
    }

    @Test
    void withoutAKeyItIsCopiedThroughHere() throws Exception {
        Files.deleteIfExists(otherHome.resolve(".ssh/id_test"));
        Path dest = otherHome.resolve("copy1.hprof");
        assertThrows(RemoteCopy.NoDirectWay.class, () -> RemoteCopy.direct(toOther, target(prod), dump.toString(),
                dest.toString(), data.length, f -> { }, () -> false));
        assertTrue(!Files.exists(dest) && !Files.exists(Path.of(dest + ".part")), "a failed try writes nothing");
        double[] seen = {0};
        RemoteCopy.throughHere(toProd, toOther, dump.toString(), dest.toString(), data.length, f -> seen[0] = f,
                () -> false);
        assertArrayEquals(data, Files.readAllBytes(dest));
        assertEquals(1.0, seen[0], 0.001);
    }

    @Test
    void withAKeyTheOtherMachineFetchesIt() throws Exception {
        Path key = otherHome.resolve(".ssh/id_test");
        Files.deleteIfExists(key);
        Process p = new ProcessBuilder("/usr/bin/ssh-keygen", "-q", "-t", "rsa", "-b", "2048", "-N", "", "-f", key.toString())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        Path dest = otherHome.resolve("copy2.hprof");
        Process scan = new ProcessBuilder("/usr/bin/ssh-keyscan", "-p", String.valueOf(prod.getPort()), "127.0.0.1")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        byte[] known = scan.getInputStream().readAllBytes();
        scan.waitFor();
        Files.write(otherHome.resolve(".ssh/known_hosts"), known);
        RemoteCopy.direct(toOther, target(prod), dump.toString(), dest.toString(), data.length, f -> { }, () -> false);
        assertArrayEquals(data, Files.readAllBytes(dest));
        assertTrue(!Files.exists(Path.of(dest + ".part")));
        // nothing is overwritten: the same name again is refused
        IOException taken = assertThrows(IOException.class, () -> RemoteCopy.direct(toOther, target(prod),
                dump.toString(), dest.toString(), data.length, f -> { }, () -> false));
        assertTrue(taken.getMessage().contains("already"), taken.getMessage());
        assertArrayEquals(data, Files.readAllBytes(dest), "still the same");
        assertTrue(RemoteCopy.free(toOther, otherHome.toString()) > 0);
    }

    @Test
    void cancellingThroughHereLeavesItsPart() throws Exception {
        Path dest = otherHome.resolve("copy3.hprof");
        var stopped = assertThrows(java.util.concurrent.CancellationException.class, () -> RemoteCopy.throughHere(
                toProd, toOther, dump.toString(), dest.toString(), data.length, f -> { }, () -> true));
        assertTrue(stopped.getMessage().contains(dest + ".part"), "says where the part is: " + stopped.getMessage());
        assertTrue(!Files.exists(dest) && Files.exists(Path.of(dest + ".part")), "nothing on a server is removed");
    }
}
