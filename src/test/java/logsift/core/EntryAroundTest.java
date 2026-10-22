package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntryAroundTest {
    @TempDir
    Path dir;

    private static final String LOG = """
            2026-10-21 10:00:00.000 INFO  [main] App - started
            2026-10-21 10:00:01.000 ERROR [main] App - payment failed
            java.lang.IllegalStateException: card declined
            \tat com.acme.Pay.charge(Pay.java:42)
            \tat com.acme.App.run(App.java:7)
            Caused by: java.io.IOException: timeout
            \t... 2 more

            2026-10-21 10:00:02.000 INFO  [main] App - next
            """;

    @Test
    void findsTheWholeStackTrace() throws Exception {
        Path f = dir.resolve("a.log");
        Files.writeString(f, LOG);
        try (LogDocument doc = LogDocument.open(f)) {
            doc.awaitIdle(10_000);
            EntryFormat ef = EntryFormat.detect(sample(doc));
            for (EntryFormat format : new EntryFormat[] {ef, null}) {
                for (long line = 1; line <= 6; line++) {
                    EntryFormat.Entry e = EntryFormat.around(doc, format, line, 1000);
                    assertEquals(1, e.first(), "line " + line + " " + format);
                    assertEquals(6, e.last(), "line " + line + " " + format);
                    assertEquals("2026-10-21 10:00:01.000 ERROR [main] App - payment failed", e.lines().get(0));
                    assertEquals("\t... 2 more", e.lines().get(5));
                }
                EntryFormat.Entry one = EntryFormat.around(doc, format, 0, 1000);
                assertEquals(List.of("2026-10-21 10:00:00.000 INFO  [main] App - started"), one.lines());
                assertEquals(8, EntryFormat.around(doc, format, 8, 1000).first());
            }
        }
    }

    private static List<String> sample(LogDocument doc) {
        String[] s = new String[(int) doc.lineCount()];
        doc.readLines(0, s.length, s);
        return List.of(s);
    }
}
