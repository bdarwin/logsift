package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PyDumpsTest {

    private static String resource(String name) throws Exception {
        return new String(PyDumpsTest.class.getResourceAsStream("/threaddumps/" + name).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void readsPySpyDumps() throws Exception {
        String text = resource("py-spy-celery.txt");
        assertTrue(DumpParser.looksLikeDump(text));
        List<ThreadDump> dumps = DumpParser.parse(text);
        assertEquals(1, dumps.size());
        ThreadDump d = dumps.get(0);
        assertTrue(d.vm().startsWith("Python 3.12.7 · /usr/local/bin/python3.12 /usr/local/bin/celery"), d.vm());
        assertEquals(0, d.line(), "from the Process line");
        assertEquals(List.of("MainThread", "ForkPoolWorker-2", "ForkPoolWorker-3"),
                d.threads().stream().map(JThread::name).toList());
        JThread w = d.threads().get(1);
        assertEquals(JThread.State.RUNNABLE, w.state());
        assertEquals("active", w.stateDetail());
        assertEquals("readinto", w.frames().get(0).method());
        assertEquals("socket.py:720", w.frames().get(0).location());
        assertTrue(w.frames().stream().anyMatch(f -> f.method().equals("send_receipt")
                && f.location().equals("shop/tasks.py:37")), "the Arguments/Locals under a frame are skipped");
        assertEquals(JThread.State.WAITING, d.threads().get(0).state(), "idle");
    }

    @Test
    void readsFaulthandlerDumpsInALog(@TempDir Path dir) throws Exception {
        String text = resource("faulthandler.txt");
        List<ThreadDump> dumps = DumpParser.parse(text);
        assertEquals(1, dumps.size());
        ThreadDump d = dumps.get(0);
        assertEquals(1, d.line());
        assertEquals(2, d.threads().size());
        JThread cur = d.threads().get(1);
        assertEquals("Current thread 0x00007f3a2c3f1740", cur.name());
        assertEquals(JThread.State.RUNNABLE, cur.state());
        assertEquals("_rolling_mean", cur.frames().get(0).method());
        assertEquals("/app/inventory/forecast.py:142", cur.frames().get(0).location());
        assertEquals(4, cur.frames().size(), "the log line after it is not a frame");
        // in an open log, next to a Java dump
        Path f = dir.resolve("mixed.log");
        Files.writeString(f, text + resource("jstack-21.txt"));
        try (logsift.core.LogDocument doc = logsift.core.LogDocument.open(f)) {
            doc.awaitIdle(10_000);
            List<ThreadDump> all = DumpParser.scan(doc, null, () -> false);
            assertEquals(4, all.size(), "the Python dump, then the file's three Java dumps");
            assertTrue(all.get(0).vm().startsWith("Python"));
            assertTrue(all.get(1).vm().contains("VM"), all.get(1).vm());
        }
        assertTrue(DumpParser.startsDump("Thread 0x00007f3a1e7fc700 (most recent call first):"));
        assertTrue(DumpParser.startsDump("2026-10-21 10:00:00 stderr Python v3.11.4 (/usr/bin/python3)"));
    }

    @Test
    void analysisWorksOnPythonThreads() throws Exception {
        List<ThreadDump> dumps = DumpParser.parse(resource("py-spy-celery.txt"));
        ThreadAnalysis.Report r = ThreadAnalysis.analyze(dumps);
        assertTrue(r != null);
    }
}
