package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The links in the assistant's answers: Logsift's lines, files on this computer, the web. */
class ChatLinksTest {

    @Test
    void lineLinksWithOrWithoutLogsiftsScheme() {
        assertEquals(new ChatMarkdown.LineLink("log:1", 1204, 1204), ChatMarkdown.LineLink.parse("logsift:log:1:1204"));
        assertEquals(new ChatMarkdown.LineLink("log:2", 10, 20), ChatMarkdown.LineLink.parse("log:2:10-20"));
        assertNull(ChatMarkdown.LineLink.parse("logsift:log:1:0"));
        assertNull(ChatMarkdown.LineLink.parse("https://example.com"));
    }

    /** A path as this system writes it, whole (on Windows "/var/log" is on the current drive). */
    private static Path abs(String p) {
        return Path.of(p).toAbsolutePath().normalize();
    }

    @Test
    void fileLinksAsModelsWriteThem() {
        assertEquals(new ChatMarkdown.FileLink(abs("/var/log/app.log"), 0),
                ChatMarkdown.FileLink.parse("/var/log/app.log"));
        assertEquals(new ChatMarkdown.FileLink(abs("/var/log/app.log"), 1204),
                ChatMarkdown.FileLink.parse("/var/log/app.log:1204"));
        assertEquals(new ChatMarkdown.FileLink(abs("/var/log/app.log"), 12),
                ChatMarkdown.FileLink.parse("/var/log/app.log:12-30"));
        String uri = abs("/var/log/app.log").toUri().toString(); // file:///var/log/… or file:///C:/var/log/…
        assertEquals(new ChatMarkdown.FileLink(abs("/var/log/app.log"), 7),
                ChatMarkdown.FileLink.parse(uri + "#L7"));
        assertEquals(new ChatMarkdown.FileLink(abs("/tmp/my logs/a.log"), 0),
                ChatMarkdown.FileLink.parse("/tmp/my%20logs/a.log"), "as Markdown's renderer escapes it");
        if (java.io.File.separatorChar == '\\') {
            assertEquals(new ChatMarkdown.FileLink(Path.of("C:\\logs\\app.log"), 12),
                    ChatMarkdown.FileLink.parse("C:\\logs\\app.log:12"), "a Windows path");
            assertEquals(new ChatMarkdown.FileLink(Path.of("C:\\logs\\app.log"), 0),
                    ChatMarkdown.FileLink.parse("C:%5Clogs%5Capp.log"), "as Markdown's renderer escapes it");
        }
        assertEquals(new ChatMarkdown.FileLink(Path.of(System.getProperty("user.home"), "logs", "a.log"), 3),
                ChatMarkdown.FileLink.parse("~/logs/a.log:3"));
        assertNull(ChatMarkdown.FileLink.parse("https://example.com/a.log"));
        assertNull(ChatMarkdown.FileLink.parse("app.log"), "a bare name says nothing about where it is");
        assertNull(ChatMarkdown.FileLink.parse("javascript:alert(1)"));
    }

    @Test
    void fileLinksStayLinksInTheAnswer() {
        String html = ChatMarkdown.body("See [app.log](/var/log/app.log:12) and [x](javascript:alert(1)).");
        assertTrue(html.contains("href=\"/var/log/app.log:12\""), html);
        assertFalse(html.contains("javascript"), html);
        assertNotNull(ChatMarkdown.FileLink.parse("/var/log/app.log:12"));
    }

    @Test
    void numbersInTablesLineUpAtTheRight() {
        String html = ChatMarkdown.body("| host | errors | p95 |\n|---|---|---|\n| web-1 | 1,204 | 850 ms |\n");
        assertTrue(html.contains("<td align=\"right\">1,204</td>"), html);
        assertTrue(html.contains("<td align=\"right\">850 ms</td>"), html);
        assertTrue(html.contains("<td>web-1</td>"), html);
        assertFalse(ChatMarkdown.numeric("web-1"));
        assertTrue(ChatMarkdown.numeric("12%"));
    }
}
