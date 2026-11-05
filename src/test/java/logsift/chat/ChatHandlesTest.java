package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which file a handle in a chat meant, read back from the chat itself. */
class ChatHandlesTest {

    @Test
    void aLinkOpensTheFileItsHandleMeantThen() {
        try (ChatStore store = ChatStore.inMemory()) {
            ChatInfo c = store.create("t", "m", null);
            Map<String, String> window = new LinkedHashMap<>();
            window.put("log:1", "/srv/orders/app.log");
            ChatMessage q = store.append(ChatMessage.user(c.id(), "why?", ChatHandles.record(window)));
            ChatMessage tool = store.append(ChatMessage.tool(c.id(), "open_log", "{\"path\":\"/srv/x/logs.zip/app.log\"}")
                    .withResult("log:3  /srv/x/logs.zip/app.log\n12 KB, 300 lines, UTF-8.\n", true, 5));
            ChatMessage first = store.append(ChatMessage.assistant(c.id(), "m").withText("[app.log:3](logsift:log:3:3)"));
            // Logsift started again: log:3 is another log now, opened later in the same chat
            store.append(ChatMessage.tool(c.id(), "open_log", "{}").withResult("log:3  /srv/y/other.log\n", true, 5));
            ChatMessage second = store.append(ChatMessage.assistant(c.id(), "m").withText("[other.log:1](logsift:log:3:1)"));

            assertEquals("/srv/x/logs.zip/app.log", ChatHandles.find(store, c.id(), first.seq(), "log:3"),
                    "the file it meant where the link is, a file in a zip too");
            assertEquals("/srv/y/other.log", ChatHandles.find(store, c.id(), second.seq(), "log:3"));
            assertEquals("/srv/y/other.log", ChatHandles.find(store, c.id(), 0, "log:3"), "0: as at the chat's end");
            assertEquals("/srv/orders/app.log", ChatHandles.find(store, c.id(), first.seq(), "log:1"),
                    "a window's log, kept with the question");
            assertNull(ChatHandles.find(store, c.id(), q.seq(), "log:3"), "not yet opened there");
            assertNull(ChatHandles.find(store, c.id(), second.seq(), "log:9"));
            assertNull(ChatHandles.record(Map.of()));
            assertEquals(tool.seq() + 1, first.seq());
        }
    }

    @Test
    void manyEntriesLaterItIsStillFound() {
        try (ChatStore store = ChatStore.inMemory()) {
            ChatInfo c = store.create("t", "m", null);
            store.append(ChatMessage.tool(c.id(), "open_log", "{}").withResult("log:2  /a/b.log\n", true, 1));
            ChatMessage last = null;
            for (int i = 0; i < 450; i++) {
                last = store.append(ChatMessage.assistant(c.id(), "m").withText("answer " + i));
            }
            assertEquals("/a/b.log", ChatHandles.find(store, c.id(), last.seq(), "log:2"), "pages back through the chat");
        }
    }
}
