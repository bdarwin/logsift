package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The assistant's audit: each question, what answering it took, and what looks wrong or wasted. */
class ChatAuditTest {
    private ChatStore store;

    @BeforeEach
    void setUp() {
        store = ChatStore.inMemory();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ChatMessage tool(String chat, String name, String args, String answer, boolean ok, long ms) {
        ChatMessage m = store.append(ChatMessage.tool(chat, name, args));
        ChatMessage done = m.withResult(answer, ok, ms);
        store.replace(done);
        return done;
    }

    private ChatMessage answer(String chat, String text, long in, long out, long ms) {
        ChatMessage a = store.append(ChatMessage.assistant(chat, "cheap"));
        ChatMessage done = a.withText(text).plusUsage("cheap", in, out, 2e7).withMillis(ms);
        store.replace(done);
        return done;
    }

    /** A chat of two questions: the first answered well, the second wastefully. */
    private ChatInfo wasteful() {
        ChatInfo c = store.create("Why errors", "cheap", null);
        ChatMessage q1 = store.append(ChatMessage.user(c.id(), "Why errors at 10:42?"));
        store.turnAsked(c.id(), q1.seq(), "[Now] Friday", "Logsift's rules");
        tool(c.id(), "search", "{\"log\":\"log:1\",\"text\":\"ERROR\"}", "2 lines", true, 40);
        answer(c.id(), "The database: [app.log:2](logsift:log:1:2).", 3000, 50, 2000);
        store.turnAnswered(c.id(), q1.seq(), 2, 2500);

        ChatMessage q2 = store.append(ChatMessage.user(c.id(), "And yesterday?"));
        store.turnAsked(c.id(), q2.seq(), "[Now] Friday", "Logsift's rules");
        tool(c.id(), "read_lines", "{\"log\":\"log:1\",\"from\":1}", "x".repeat(30_000), true, 300);
        tool(c.id(), "read_lines", "{\"from\": 1, \"log\": \"log:1\"}", "x".repeat(30_000), true, 300); // the same
        tool(c.id(), "open_log", "{\"path\":\"/nope\"}", "No such file", false, 5);
        answer(c.id(), "See [gc.log:864](logsift:log:864).", 140_000, 900, 8000);
        store.turnAnswered(c.id(), q2.seq(), 4, 0);
        return c;
    }

    @Test
    void eachQuestionIsListedWithWhatAnsweringItTook() {
        ChatInfo c = wasteful();
        List<ChatAudit.Turn> turns = ChatAudit.page(store, 0, null, 50, ChatAudit.Settings.DEFAULT);
        assertEquals(2, turns.size());
        ChatAudit.Turn last = turns.get(0); // the newest first
        assertEquals("And yesterday?", last.question());
        assertEquals("Why errors", last.title());
        assertEquals(3, last.toolCalls());
        assertEquals(1, last.failedTools());
        assertEquals(60_012, last.toolChars());
        assertEquals(140_900, last.tokens());
        assertEquals(4, last.calls());
        assertEquals(8000, last.millis());
        assertEquals(Set.of(ChatAudit.Flag.FAILED_TOOL, ChatAudit.Flag.REPEATED, ChatAudit.Flag.BIG_RESULT,
                ChatAudit.Flag.COSTLY, ChatAudit.Flag.BROKEN_LINK), last.flags());
        assertTrue(last.steps().get(1).again(), "the second read_lines is the same call, its keys in another order");
        assertFalse(last.steps().get(0).again());

        ChatAudit.Turn first = turns.get(1);
        assertEquals("Why errors at 10:42?", first.question());
        assertEquals(Set.of(), first.flags());
        assertEquals(2500, first.cached());
        assertEquals(first.seq(), ChatAudit.turn(store, c.id(), first.seq(), ChatAudit.Settings.DEFAULT).seq());
        assertEquals(last.flags(), ChatAudit.turn(store, c.id(), last.seq(), ChatAudit.Settings.DEFAULT).flags(),
                "opened alone, it's the same");
        assertEquals(last.seq(), first.next());
    }

    @Test
    void whatIsFlaggedFollowsTheSettings() {
        wasteful();
        ChatAudit.Settings loose = ChatAudit.Settings.DEFAULT.withBigResultKb(100).withCostlyTokens(500_000)
                .withSlowSeconds(5).withManyTools(2);
        ChatAudit.Turn last = ChatAudit.page(store, 0, null, 50, loose).get(0);
        assertFalse(last.flags().contains(ChatAudit.Flag.BIG_RESULT));
        assertFalse(last.flags().contains(ChatAudit.Flag.COSTLY));
        assertTrue(last.flags().contains(ChatAudit.Flag.SLOW), "8 s is slower than 5");
        assertTrue(last.flags().contains(ChatAudit.Flag.MANY_TOOLS), "3 tools, more than 2");
    }

    @Test
    void anErrorAndAnUnansweredQuestionAreFlagged() {
        ChatInfo c = store.create("Broken", "cheap", null);
        store.append(ChatMessage.user(c.id(), "Hello?"));
        store.append(ChatMessage.error(c.id(), "Copilot stopped"));
        store.append(ChatMessage.user(c.id(), "Again?"));
        tool(c.id(), "list_logs", "{}", "none", true, 3);
        ChatMessage a = store.append(ChatMessage.assistant(c.id(), "cheap"));
        store.replace(a.plusUsage("cheap", 100, 0, 0));
        List<ChatAudit.Turn> t = ChatAudit.page(store, 0, null, 50, ChatAudit.Settings.DEFAULT);
        assertEquals(Set.of(ChatAudit.Flag.NO_ANSWER), t.get(0).flags());
        assertEquals(Set.of(ChatAudit.Flag.ERROR), t.get(1).flags());
    }

    @Test
    void thePersonsVerdictIsKeptAndABadOneIsFlagged() {
        ChatInfo c = wasteful();
        ChatAudit.Turn first = ChatAudit.page(store, 0, null, 50, ChatAudit.Settings.DEFAULT).get(1);
        store.rate(c.id(), first.seq(), -1, "  It missed the restart at 03:00. ");
        ChatAudit.Turn again = ChatAudit.turn(store, c.id(), first.seq(), ChatAudit.Settings.DEFAULT);
        assertEquals(-1, again.rating());
        assertEquals("It missed the restart at 03:00.", again.note());
        assertEquals(Set.of(ChatAudit.Flag.RATED_BAD), again.flags());
        store.rate(c.id(), first.seq(), 1, "");
        assertEquals(1, store.turn(c.id(), first.seq()).rating());
        assertEquals("[Now] Friday", store.turn(c.id(), first.seq()).sent(), "what was sent stays");

        // a question from before the audit (1.4.0) can be rated too
        ChatMessage old = store.append(ChatMessage.user(c.id(), "From before"));
        assertEquals(ChatStore.TurnInfo.NONE, store.turn(c.id(), old.seq()));
        store.rate(c.id(), old.seq(), 1, "good");
        assertEquals(1, store.turn(c.id(), old.seq()).rating());
    }

    @Test
    void instructionsAreKeptOnceAndGoWithTheChat() {
        ChatInfo c = wasteful();
        ChatAudit.Turn first = ChatAudit.page(store, 0, null, 50, ChatAudit.Settings.DEFAULT).get(1);
        assertEquals("Logsift's rules", store.turn(c.id(), first.seq()).instructions());
        store.delete(c.id());
        assertEquals(ChatStore.TurnInfo.NONE, store.turn(c.id(), first.seq()), "deleted with its chat");
        assertEquals(List.of(), ChatAudit.page(store, 0, null, 50, ChatAudit.Settings.DEFAULT));
    }

    @Test
    void questionsComeAPageAtATimeNewestFirst() {
        List<String> asked = new ArrayList<>();
        for (int chat = 0; chat < 3; chat++) {
            ChatInfo c = store.create("Chat " + chat, "cheap", null);
            for (int i = 0; i < 9; i++) {
                String q = "Q" + chat + "." + i;
                asked.add(q);
                store.append(ChatMessage.user(c.id(), q));
                answer(c.id(), "A", 10, 1, 5);
            }
        }
        List<String> seen = new ArrayList<>();
        ChatAudit.Turn last = null;
        int pages = 0;
        while (true) {
            List<ChatAudit.Turn> p = ChatAudit.page(store, 0, last, 10, ChatAudit.Settings.DEFAULT);
            if (p.isEmpty()) {
                break;
            }
            assertTrue(p.size() <= 10);
            pages++;
            p.forEach(t -> {
                seen.add(t.question());
                assertEquals(1, t.steps().size(), "each with its own answer only");
            });
            last = p.get(p.size() - 1);
        }
        assertEquals(3, pages);
        assertEquals(27, seen.size());
        assertEquals(Set.copyOf(asked), Set.copyOf(seen), "each question once");
        assertEquals(27, ChatAudit.turns(store, 0, 1000, ChatAudit.Settings.DEFAULT).size());
        assertEquals(5, ChatAudit.turns(store, 0, 5, ChatAudit.Settings.DEFAULT).size());
        ChatStore.Totals totals = store.totals(0);
        assertEquals(27, totals.questions());
        assertEquals(27 * 11, totals.tokens());
        assertEquals(0, store.totals(System.currentTimeMillis() + 60_000).questions(), "none since then");
    }

    @Test
    void brokenLinksAreTheOnesThatCantOpenTheirLine() {
        assertEquals(List.of("logsift:log:864", "logsift:log:"), ChatAudit.brokenLinks(
                "[a](logsift:log:864) [b](logsift:log:1:2) [c](logsift:log:2:10-20) [d](logsift:log:) [e](/var/x:3)"));
        assertEquals(List.of(), ChatAudit.brokenLinks(null));
    }

    @Test
    void theReportHasWhatAnAgentNeedsToFindWhatGoesWrong() {
        wasteful();
        List<ChatAudit.Turn> turns = ChatAudit.page(store, 0, null, 50, ChatAudit.Settings.DEFAULT);
        store.rate(turns.get(0).chat(), turns.get(0).seq(), -1, "Read the whole log twice");
        turns = ChatAudit.page(store, 0, null, 50, ChatAudit.Settings.DEFAULT);
        String r = ChatAudit.report(store, turns, false);
        assertTrue(r.startsWith("# Logsift assistant audit\n\n2 questions · 143,950 tokens · 1 flagged · 0 good, 1 bad"), r);
        assertTrue(r.contains("| Same call again | 1 |"), r);
        assertTrue(r.contains("**Question:** And yesterday?"), r);
        assertTrue(r.contains("Verdict: bad — Read the whole log twice"), r);
        assertTrue(r.contains("Broken links: logsift:log:864"), r);
        assertTrue(r.contains("`read_lines` `{\"from\": 1, \"log\": \"log:1\"}` → 30000 chars (~7500 tokens), 300 ms, same call again"), r);
        assertTrue(r.contains("`open_log` `{\"path\":\"/nope\"}` → failed, 12 chars"), r);
        assertFalse(r.contains("Sent with the question") || r.contains("## Instructions"), "only with the details");
        String d = ChatAudit.report(store, turns, true);
        assertTrue(d.contains("Sent with the question:\n\n```\n[Now] Friday\n```"), d);
        assertTrue(d.contains("> No such file"), "the start of each tool's answer");
        assertEquals(1, d.split("## Instructions ", -1).length - 1, "the instructions once: " + d);
        assertTrue(d.contains("Logsift's rules"));
    }

    @Test
    void itsSettingsAreKept(@TempDir Path home) throws Exception {
        assertEquals(ChatAudit.Settings.DEFAULT, ChatAudit.Settings.load(home));
        ChatAudit.Settings s = ChatAudit.Settings.DEFAULT.withKeepSent(false).withBigResultKb(50)
                .withCostlyTokens(20_000).withSlowSeconds(30).withManyTools(5);
        s.save(home);
        assertEquals(s, ChatAudit.Settings.load(home));
        Files.writeString(home.resolve("chat/audit.properties"), "bigResultKb=lots\nslowSeconds=0\n");
        ChatAudit.Settings odd = ChatAudit.Settings.load(home);
        assertEquals(20, odd.bigResultKb(), "not a number: the default");
        assertEquals(5, odd.slowSeconds(), "at least 5 s");
        assertNotNull(ChatAudit.Flag.of("broken line link"));
    }
}
