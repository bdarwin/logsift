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

    @Test
    void fileLinksAsModelsWriteThem() {
        assertEquals(new ChatMarkdown.FileLink(Path.of("/var/log/app.log"), 0),
                ChatMarkdown.FileLink.parse("/var/log/app.log"));
        assertEquals(new ChatMarkdown.FileLink(Path.of("/var/log/app.log"), 1204),
                ChatMarkdown.FileLink.parse("/var/log/app.log:1204"));
        assertEquals(new ChatMarkdown.FileLink(Path.of("/var/log/app.log"), 12),
                ChatMarkdown.FileLink.parse("/var/log/app.log:12-30"));
        assertEquals(new ChatMarkdown.FileLink(Path.of("/var/log/app.log"), 7),
                ChatMarkdown.FileLink.parse("file:///var/log/app.log#L7"));
        assertEquals(new ChatMarkdown.FileLink(Path.of("/tmp/my logs/a.log"), 0),
                ChatMarkdown.FileLink.parse("/tmp/my%20logs/a.log"), "as Markdown's renderer escapes it");
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
}
