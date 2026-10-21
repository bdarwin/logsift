package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteMirrorTest {
    @TempDir
    Path dir;

    private SshServer server;
    private Path remoteRoot;

    @BeforeEach
    void startServer() throws IOException {
        remoteRoot = Files.createDirectories(dir.resolve("remote"));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser")));
        server.setPasswordAuthenticator((user, password, session) -> user.equals("ops") && password.equals("secret"));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.stop(true);
    }

    private RemoteMirror.Target target(String password) {
        return new RemoteMirror.Target("127.0.0.1", server.getPort(), "ops", "/var/app.log", password, null, null);
    }

    @Test
    void mirrorsAndFollowsARemoteLog() throws Exception {
        Path remote = Files.createDirectories(remoteRoot.resolve("var")).resolve("app.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("2026-10-21 10:00:00 INFO line ").append(i).append('\n');
        }
        Files.writeString(remote, sb);
        Path copy = dir.resolve("copy.log");
        try (RemoteMirror m = RemoteMirror.open(target("secret"), copy, 0, null, h -> true, null, () -> false)) {
            assertEquals(sb.toString(), Files.readString(copy), "whole file");
            // appended in two pieces, the first ending mid-line
            Files.writeString(remote, "2026-10-21 10:00:01 ERROR half", StandardOpenOption.APPEND);
            m.update();
            assertEquals(sb.toString(), Files.readString(copy), "an incomplete line waits");
            Files.writeString(remote, " a line\n2026-10-21 10:00:02 INFO next\n", StandardOpenOption.APPEND);
            m.update();
            assertEquals(sb + "2026-10-21 10:00:01 ERROR half a line\n2026-10-21 10:00:02 INFO next\n",
                    Files.readString(copy), "appended lines arrive whole");
            // truncated (rotated in place): the copy starts over
            Files.writeString(remote, "fresh start\n");
            m.update();
            assertEquals("fresh start\n", Files.readString(copy, StandardCharsets.UTF_8));
        }
    }

    @Test
    void lastBytesStartAtALine() throws Exception {
        Path remote = Files.createDirectories(remoteRoot.resolve("var")).resolve("app.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append("line ").append(i).append(" some text to make it longer\n");
        }
        Files.writeString(remote, sb);
        Path copy = dir.resolve("tail.log");
        try (RemoteMirror m = RemoteMirror.open(target("secret"), copy, 100_000, null, h -> true, null, () -> false)) {
            String got = Files.readString(copy);
            assertTrue(got.length() <= 100_000 && got.length() > 99_000 - 64, "about the last 100 KB: " + got.length());
            assertTrue(sb.toString().endsWith(got), "the end of the file");
            assertTrue(got.startsWith("line "), "starts at a whole line: " + got.substring(0, 20));
            assertTrue(!m.isComplete());
        }
    }

    @Test
    void browsesFolders() throws Exception {
        Files.createDirectories(remoteRoot.resolve("var/log/app"));
        Files.createDirectories(remoteRoot.resolve("var/log/Zeta"));
        Files.writeString(remoteRoot.resolve("var/log/syslog"), "x\n".repeat(10));
        Files.writeString(remoteRoot.resolve("var/log/auth.log"), "y\n");
        try (RemoteBrowser b = RemoteBrowser.connect(target("secret"), null, h -> true)) {
            assertEquals("/", b.home(), "the virtual file system's root is the home here");
            assertEquals("/var/log", b.resolve("/var/log/app/.."));
            assertEquals("/var/log", b.resolve("var/log"), "relative to home");
            assertTrue(b.isDirectory("/var/log/app"));
            List<RemoteBrowser.Entry> e = b.list("/var/log");
            assertEquals(List.of("app", "Zeta", "auth.log", "syslog"), e.stream().map(RemoteBrowser.Entry::name).toList(),
                    "folders first, then files, by name");
            assertTrue(e.get(0).dir() && !e.get(2).dir());
            assertEquals(20, e.get(3).size());
            assertEquals("/var", RemoteBrowser.parent("/var/log"));
            assertEquals("/", RemoteBrowser.parent("/var"));
            assertThrows(IOException.class, () -> b.list("/nope"));
        }
    }

    @Test
    void wrongPasswordIsAClearError() throws Exception {
        Files.createDirectories(remoteRoot.resolve("var"));
        Files.writeString(remoteRoot.resolve("var/app.log"), "x\n");
        IOException e = assertThrows(IOException.class, () -> RemoteMirror.open(target("nope"),
                dir.resolve("c.log"), 0, null, h -> true, null, () -> false));
        assertTrue(e.getMessage().contains("login failed"), e.getMessage());
    }
}
