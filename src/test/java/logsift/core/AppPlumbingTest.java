package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Versions, the update check, single instance and piped input. */
class AppPlumbingTest {
    @TempDir
    Path dir;

    @Test
    void comparesVersions() {
        assertTrue(Version.compare("1.10.0", "1.9.3") > 0);
        assertTrue(Version.compare("v1.1.0", "1.0.9") > 0);
        assertEquals(0, Version.compare("1.0", "1.0.0"));
        assertTrue(Version.compare("1.1.0-beta", "1.1.0") < 0);
        assertTrue(Version.isNewer("1.1.0", "1.0.0"));
        assertFalse(Version.isNewer("1.0.0", "1.0.0"));
        assertFalse(Version.isNewer("9.0.0", "dev"), "a development build never asks to update");
    }

    @Test
    void readsLatestReleaseFromGitHubJson() throws Exception {
        String json = "{\"url\":\"x\",\"html_url\":\"https:\\/\\/github.com\\/bdarwin\\/logsift\\/releases\\/tag\\/v1.2.0\","
                + "\"tag_name\":\"v1.2.0\",\"name\":\"Logsift 1.2.0\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest", ex -> {
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            var r = UpdateChecker.latest("http://127.0.0.1:" + server.getAddress().getPort() + "/latest").orElseThrow();
            assertEquals("1.2.0", r.version());
            assertEquals("https://github.com/bdarwin/logsift/releases/tag/v1.2.0", r.url());
            assertTrue(UpdateChecker.latest("http://127.0.0.1:" + server.getAddress().getPort() + "/missing").isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void secondLaunchHandsFilesToTheFirst() throws Exception {
        assertFalse(SingleInstance.forward(dir, List.of(Path.of("a.log"))), "nothing running yet");
        List<Path> got = new CopyOnWriteArrayList<>();
        SingleInstance si = SingleInstance.listen(dir, got::addAll);
        try {
            assertTrue(SingleInstance.forward(dir, List.of(Path.of("/var/log/a.log"), Path.of("/tmp/b c.log"))));
            for (int i = 0; i < 50 && got.size() < 2; i++) {
                Thread.sleep(20);
            }
            // paths arrive absolute (on Windows "/var/log/a.log" becomes "C:\\var\\log\\a.log")
            assertEquals(List.of(Path.of("/var/log/a.log").toAbsolutePath(), Path.of("/tmp/b c.log").toAbsolutePath()), got);
            // a wrong secret is ignored
            Files.writeString(dir.resolve("instance"),
                    Files.readAllLines(dir.resolve("instance")).get(0) + "\nwrong\n");
            assertFalse(SingleInstance.forward(dir, List.of(Path.of("/etc/passwd"))));
            assertEquals(2, got.size());
        } finally {
            si.close();
        }
        assertFalse(Files.exists(dir.resolve("instance")), "removed on exit");
        assertFalse(SingleInstance.forward(dir, List.of(Path.of("a.log"))));
    }

    @Test
    void capturesPipedInputAsItArrives() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out);
        Path f = StdinCapture.start(in, dir.resolve("stdin"));
        out.write("first line\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
        waitForSize(f, 11);
        assertEquals("first line\n", Files.readString(f), "written before the pipe closes");
        out.write("second line\n".getBytes(StandardCharsets.UTF_8));
        out.close();
        waitForSize(f, 23);
        assertEquals("first line\nsecond line\n", Files.readString(f));

        Path old = dir.resolve("stdin").resolve("stdin-20200101-000000.log");
        Files.writeString(old, "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(30))));
        StdinCapture.start(new ByteArrayInputStream(new byte[0]), dir.resolve("stdin"));
        assertFalse(Files.exists(old), "week-old captures are cleaned up");
        assertTrue(Files.exists(f));
    }

    private static void waitForSize(Path f, long size) throws Exception {
        for (int i = 0; i < 200 && Files.size(f) < size; i++) {
            Thread.sleep(10);
        }
    }
}
