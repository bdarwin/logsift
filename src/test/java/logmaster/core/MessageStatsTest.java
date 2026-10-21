package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import logmaster.tools.LogGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageStatsTest {
    @TempDir
    Path dir;

    private static String template(String line) {
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[b.length + 16];
        int n = MessageStats.template(b, 0, b.length, out);
        return new String(out, 0, n, StandardCharsets.UTF_8);
    }

    @Test
    void variablePartsBecomeWildcards() {
        assertEquals("ERROR [<*>] c.a.Service - Database connection lost after <*> ms",
                template("2026-10-21 10:02:13.123 ERROR [worker-7] c.a.Service - Database connection lost after 26579 ms"));
        assertEquals("INFO  [main] Order <*> created for user=<*> amount=<*> EUR".replace("  ", " "),
                template("2026-10-21 10:02:13,123 INFO  [main] Order 100121 created for user=u1687 amount=388.72 EUR"));
        assertEquals("Oct <*> <*> host sshd[<*>]: Failed password for root from <*> port <*>",
                template("Oct 21 10:02:13 host sshd[812]: Failed password for root from 10.0.0.7 port 51122"));
        assertEquals("{\"level\":\"WARN\",\"ts\":\"<*>\",\"msg\":\"slow <*>\"}",
                template("{\"level\":\"WARN\",\"ts\":\"2026-10-21T10:02:13Z\",\"msg\":\"slow 812ms\"}"));
    }

    @Test
    void regexMatchesItsLines() {
        String t = "ERROR [<*>] c.a.Service - Lost (after <*> ms) user=<*>";
        Pattern p = Pattern.compile(MessageStats.toRegex(t));
        assertTrue(p.matcher("2026-10-21 ERROR [w-1] c.a.Service - Lost (after 12 ms) user=u9").find());
        assertTrue(!p.matcher("2026-10-21 ERROR [w-1] c.a.Service - Lost (before 12 ms) user=u9").find());
        assertTrue(RequiredLiteral.fromRegex(MessageStats.toRegex(t)) != null, "keeps the fast pre-filter");
    }

    @Test
    void countsEveryEntryOfAGeneratedLog() throws Exception {
        Path f = dir.resolve("m.log");
        LogGenerator.generate(f, 6 << 20, 3);
        List<String> lines = Files.readAllLines(f);
        EntryFormat entries = EntryFormat.detect(lines.subList(0, 500));
        long heads = lines.stream().filter(entries::startsEntry).count();
        try (LogDocument doc = LogDocument.open(f, 512 * 1024)) {
            assertTrue(doc.awaitIdle(10_000));
            MessageStats.Result r = MessageStats.analyze(doc, entries, 0, Long.MAX_VALUE, null,
                    () -> false, null);
            assertEquals(heads, r.lines(), "every entry counted once");
            assertEquals(heads, r.templates().stream().mapToLong(MessageStats.Template::count).sum());
            assertTrue(r.templates().size() < 200, "a few dozen kinds of messages, not thousands: "
                    + r.templates().size());
            // names are merged: "Customer <*> logged in user=<*>"
            assertTrue(r.templates().stream().anyMatch(t -> t.text().contains("Customer <*> logged in")),
                    "customer names merged");
            // each template's regex finds at least as many entry lines as it counted
            for (MessageStats.Template t : r.templates().subList(0, Math.min(15, r.templates().size()))) {
                Pattern p = Pattern.compile(t.regex());
                long found = lines.stream().filter(entries::startsEntry).filter(l -> p.matcher(l).find()).count();
                assertTrue(found >= t.count(), t.text() + ": regex finds " + found + " < " + t.count());
                assertTrue(p.matcher(lines.get((int) t.firstLine())).find(), "first line matches " + t.text());
            }
        }
    }
}
