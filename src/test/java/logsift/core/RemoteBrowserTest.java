package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Browsing folders over SFTP, as the window does: each step on a thread of its own. */
class RemoteBrowserTest {
    @TempDir
    Path dir;

    private SshServer server;

    @BeforeEach
    void startServer() throws IOException {
        Path root = Files.createDirectories(dir.resolve("remote"));
        Files.createDirectories(root.resolve("var/log/app/archive"));
        Files.writeString(root.resolve("var/log/app/server.log"), "x\n");
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(dir.resolve("hostkey.ser")));
        server.setPasswordAuthenticator((user, password, session) -> user.equals("ops") && password.equals("secret"));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(root));
        server.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.stop(true);
    }

    /** Runs {@code r} on a new thread, as the window does, and waits for it to end. */
    private static void onANewThread(IoRun r) throws Exception {
        AtomicReference<Exception> failed = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Exception e) {
                failed.set(e);
            }
        });
        t.start();
        t.join();
        if (failed.get() != null) {
            throw failed.get();
        }
    }

    private interface IoRun {
        void run() throws Exception;
    }

    @Test
    void manyStepsEachOnItsOwnThreadKeepTheConnection() throws Exception {
        RemoteMirror.Target t = new RemoteMirror.Target("127.0.0.1", server.getPort(), "ops", "/", "secret", null, null);
        try (RemoteBrowser b = RemoteBrowser.connect(t, dir.resolve("known_hosts"), h -> true)) {
            // typing a folder resolves it (realpath), then lists it: each from a thread that then ends
            for (int i = 0; i < 300; i++) {
                String[] where = new String[1];
                String typed = i % 2 == 0 ? "/var/log/app" : "/var/log/app/../app";
                onANewThread(() -> where[0] = b.resolve(typed));
                assertEquals("/var/log/app", where[0]);
                onANewThread(() -> assertEquals(List.of("archive", "server.log"),
                        b.list(where[0]).stream().map(RemoteBrowser.Entry::name).toList()));
            }
        }
    }
}
