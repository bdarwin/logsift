package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The chats in DuckDB: kept, paged by key both ways, searched, and what they cost. */
class ChatStoreTest {
    @TempDir
    Path dir;

    @Test
    void aChatIsKeptAcrossRestarts() throws Exception {
        String id;
        try (ChatStore s = ChatStore.open(dir)) {
            ChatInfo c = s.create("Why errors at 10:42?", "claude-haiku-5.5", "/var/log/app.log");
            id = c.id();
            s.append(ChatMessage.user(id, "Why errors at 10:42?"));
            ChatMessage a = s.append(ChatMessage.assistant(id, "claude-haiku-5.5"));
            s.replace(a.withText("The database was down: [app.log:1204](logsift:log:1:1204)")
                    .plusUsage("claude-haiku-5.5", 3600, 76, 5.2e7).withMillis(4200));
            s.setSession(id, "session-1");
        }
        try (ChatStore s = ChatStore.open(dir)) {
            ChatInfo c = s.chat(id);
            assertEquals("Why errors at 10:42?", c.title());
            assertEquals("session-1", c.session());
            assertEquals("/var/log/app.log", c.context());
            assertEquals(2, c.messages());
            assertEquals(0.052, c.credits(), 1e-9);
            List<ChatMessage> l = s.last(id, 10);
            assertEquals(List.of(ChatMessage.Kind.USER, ChatMessage.Kind.ASSISTANT), l.stream().map(ChatMessage::kind).toList());
            ChatMessage a = l.get(1);
            assertEquals(2, a.seq());
            assertTrue(a.text().startsWith("The database was down"));
            assertEquals(3600, a.tokensIn());
            assertEquals(76, a.tokensOut());
            assertEquals(4200, a.millis());
        }
    }

    @Test
    void aLongChatScrollsBackAPageAtATimeWithNothingRepeatedOrSkipped() {
        try (ChatStore s = ChatStore.inMemory()) {
            String id = s.create("long", "m", null).id();
            for (int i = 1; i <= 1000; i++) {
                s.append(ChatMessage.user(id, "question " + i));
            }
            assertEquals(1000, s.count(id));
            List<Long> seen = new ArrayList<>();
            List<ChatMessage> page = s.last(id, 50);
            long t0 = System.nanoTime();
            while (!page.isEmpty()) {
                List<Long> seqs = page.stream().map(ChatMessage::seq).toList();
                seen.addAll(0, seqs);
                page = s.before(id, seqs.get(0), 50);
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(1000, seen.size());
            for (int i = 0; i < 1000; i++) {
                assertEquals(i + 1, seen.get(i));
            }
            assertTrue(ms < 2000, "20 pages took " + ms + " ms");
            // and forwards, from a search hit
            List<ChatMessage> around = s.around(id, 500, 3);
            assertEquals(List.of(497L, 498L, 499L, 500L, 501L, 502L, 503L), around.stream().map(ChatMessage::seq).toList());
            assertEquals(List.of(998L, 999L, 1000L), s.after(id, 997, 10).stream().map(ChatMessage::seq).toList());
        }
    }

    @Test
    void theChatsListIsNewestFirstAndPagedByKey() throws Exception {
        try (ChatStore s = ChatStore.inMemory()) {
            for (int i = 0; i < 25; i++) {
                String id = s.create("chat " + i, "m", null).id();
                s.append(ChatMessage.user(id, "q" + i));
                Thread.sleep(2); // a later time for each
            }
            assertEquals(25, s.chatCount());
            Set<String> titles = new HashSet<>();
            List<ChatInfo> page = s.chats(null, 10);
            assertEquals("chat 24", page.get(0).title());
            while (!page.isEmpty()) {
                page.forEach(c -> assertTrue(titles.add(c.title()), "twice: " + c.title()));
                page = s.chats(page.get(page.size() - 1), 10);
            }
            assertEquals(25, titles.size());
        }
    }

    @Test
    void searchFindsWordsInAnyChatAndTakesWildcardsLiterally() {
        try (ChatStore s = ChatStore.inMemory()) {
            String a = s.create("Checkout timeouts", "m", null).id();
            s.append(ChatMessage.user(a, "Why does checkout time out after the deploy?"));
            ChatMessage ans = s.append(ChatMessage.assistant(a, "m"));
            s.replace(ans.withText("The pool ran out: 100% of connections were busy from 10:41."));
            String b = s.create("Disk", "m", null).id();
            s.append(ChatMessage.user(b, "Is the disk full? used_space looks high"));

            List<ChatStore.Hit> hits = s.search("checkout", 10);
            assertEquals(2, hits.size()); // the name and the question
            assertTrue(hits.stream().allMatch(h -> h.chat().equals(a)));
            List<ChatStore.Hit> pct = s.search("100%", 10);
            assertEquals(1, pct.size());
            assertEquals(ChatMessage.Kind.ASSISTANT, pct.get(0).kind());
            assertTrue(pct.get(0).snippet().contains("100% of connections"), pct.get(0).snippet());
            assertEquals(1, s.search("used_space", 10).size());
            assertEquals(0, s.search("used space", 10).size()); // _ is not "any letter"
            assertEquals(0, s.search("  ", 10).size());
        }
    }

    @Test
    void aSnippetIsTheWordsAroundTheMatchOnOneLine() {
        String text = "x".repeat(100) + "\nthe needle\nin the\nhaystack " + "y".repeat(200);
        String s = ChatStore.snippet(text, "NEEDLE");
        assertTrue(s.startsWith("…"), s);
        assertTrue(s.endsWith("…"), s);
        assertTrue(s.contains("the needle in the haystack"), s);
        assertFalse(s.contains("\n"));
    }

    @Test
    void usageAddsUpEachModelsAnswersSince() {
        try (ChatStore s = ChatStore.inMemory()) {
            String id = s.create("c", "m", null).id();
            for (int i = 0; i < 3; i++) {
                ChatMessage m = s.append(ChatMessage.assistant(id, "claude-sonnet-5.5"));
                s.replace(m.plusUsage("claude-sonnet-5.5", 1000, 100, 2e8));
            }
            ChatMessage m = s.append(ChatMessage.assistant(id, "gpt-6-luna"));
            s.replace(m.plusUsage("gpt-6-luna", 500, 50, 1e7));
            s.append(ChatMessage.user(id, "not an answer"));
            List<ChatStore.Usage> u = s.usage(0);
            assertEquals(2, u.size());
            assertEquals("claude-sonnet-5.5", u.get(0).model());
            assertEquals(3, u.get(0).answers());
            assertEquals(3000, u.get(0).tokensIn());
            assertEquals(0.6, u.get(0).credits(), 1e-9);
            assertEquals(0, s.usage(System.currentTimeMillis() + 60_000).size());
        }
    }

    @Test
    void deletingAChatTakesItsEntriesToo() {
        try (ChatStore s = ChatStore.inMemory()) {
            String id = s.create("c", "m", null).id();
            s.append(ChatMessage.user(id, "hello"));
            s.delete(id);
            assertNull(s.chat(id));
            assertEquals(0, s.count(id));
            assertEquals(0, s.search("hello", 5).size());
        }
    }
}
