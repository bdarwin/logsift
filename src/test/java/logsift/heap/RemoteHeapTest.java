package logsift.heap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * A dump read by a {@link HeapServer} in a JVM of its own (Java 8 when LOGSIFT_JAVA8 names one),
 * asked through {@link RemoteHeap}: the same answers as reading it here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteHeapTest {
    @TempDir
    static Path tmp;
    private Path file;
    private HeapDump local;
    private Process server;
    private Path state;
    private Path work;
    private RemoteHeap remote;

    @BeforeAll
    void start() throws Exception {
        file = tmp.resolve("leak.hprof");
        LeakApp.run(file, tmp.resolve("histogram.txt"));
        local = HeapDump.open(file, null, null, null);
        state = tmp.resolve("state");
        work = Files.createTempDirectory("logsift-remote-index").resolve("index");
        server = startServer(file, work, state);
        remote = connect(state);
        List<String> stages = new ArrayList<>();
        remote.awaitReady((s, f) -> stages.add(s), null);
    }

    static Process startServer(Path dump, Path work, Path state) throws Exception {
        String java8 = System.getenv("LOGSIFT_JAVA8");
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        String java = java8 != null ? java8 : Path.of(System.getProperty("java.home"), "bin", exe).toString();
        String jar = Path.of(HeapServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        return new ProcessBuilder(java, "-Xmx256m", "-cp", jar, HeapServer.class.getName(), "--dump", dump.toString(),
                "--work", work.toString(), "--state", state.toString(), "--idle", "5")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start();
    }

    /** Connects with what the server wrote to its state file. */
    static RemoteHeap connect(Path state) throws Exception {
        Path f = state.resolve("server");
        long until = System.currentTimeMillis() + 30_000;
        while (!Files.exists(f) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        String[] first = Files.readAllLines(f).get(0).split(" ");
        Socket s = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(first[0]));
        return RemoteHeap.connect(new RemoteHeap.Link() {
            @Override
            public InputStream in() throws IOException {
                return s.getInputStream();
            }

            @Override
            public OutputStream out() throws IOException {
                return s.getOutputStream();
            }

            @Override
            public void close() throws IOException {
                s.close();
            }
        }, first[1], "test-host");
    }

    @AfterAll
    void stop() throws Exception {
        if (remote != null) {
            remote.close();
        }
        if (server != null && server.isAlive()) {
            server.destroyForcibly();
        }
        if (local != null) {
            local.close();
        }
    }

    @Test
    void theSameAnswersAsReadingItHere() throws Exception {
        assertEquals("leak.hprof", remote.fileName());
        assertEquals("test-host:" + file.toAbsolutePath(), remote.location());
        assertEquals(local.reachableBytes(), remote.reachableBytes());
        assertEquals(local.objectCount(), remote.objectCount());
        assertEquals(local.threadCount(), remote.threadCount());
        assertEquals(local.classes(), remote.classes());
        assertEquals(local.findings(), remote.findings());
        int[] top = local.held(local.root(), 20);
        assertArrayEquals(top, remote.held(remote.root(), 20));
        for (int v : top) {
            assertEquals(local.describe(v), remote.describe(v));
            assertEquals(local.retained(v), remote.retained(v));
            assertEquals(local.heldCount(v), remote.heldCount(v));
        }
        int map = local.objectsOf("java.util.HashMap", 1)[0];
        assertArrayEquals(new int[] {map}, remote.objectsOf("java.util.HashMap", 1));
        assertEquals(local.pathFromRoot(map), remote.pathFromRoot(map));
        assertEquals(local.threadDumpText(), remote.threadDumpText());
        assertEquals(local.map(local.root()).inside().size(), remote.map(remote.root()).inside().size());
        assertEquals(local.map(map).inside().get(0).label(), remote.map(map).inside().get(0).label());
        List<String> stages = new ArrayList<>();
        assertEquals(local.waste(null, null), remote.waste((s, f) -> stages.add(s), null));
        assertTrue(!stages.isEmpty(), "progress came along");
    }

    @Test
    void aWrongTokenIsTurnedAway() throws Exception {
        String[] first = Files.readAllLines(state.resolve("server")).get(0).split(" ");
        Socket s = new Socket(InetAddress.getLoopbackAddress(), Integer.parseInt(first[0]));
        assertThrows(IOException.class, () -> RemoteHeap.connect(new RemoteHeap.Link() {
            @Override
            public InputStream in() throws IOException {
                return s.getInputStream();
            }

            @Override
            public OutputStream out() throws IOException {
                return s.getOutputStream();
            }

            @Override
            public void close() throws IOException {
                s.close();
            }
        }, "not-the-token", "test-host"));
    }

    @Test
    void stoppingEndsTheServerAndItsTemporaryIndex() throws Exception {
        Path dir = Files.createTempDirectory("logsift-remote-stop");
        Process p = startServer(file, dir.resolve("index"), dir.resolve("state"));
        RemoteHeap r = connect(dir.resolve("state"));
        r.awaitReady(null, null);
        assertTrue(Files.exists(dir.resolve("state/server")));
        r.stopServer();
        r.close();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "the server ended");
        assertEquals("stopped", new String(Files.readAllBytes(dir.resolve("state/server")), "UTF-8").trim(),
                "its state file stays, saying so");
        try (java.util.stream.Stream<Path> left = Files.list(dir.resolve("index"))) {
            assertEquals(0, left.count(), "its index was temporary files, gone with it");
        }
    }
}
