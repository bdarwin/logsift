package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import logsift.agent.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The assistant's audit for coding agents: read from the chats, or asked of a running Logsift. */
class AuditToolsTest {
    @TempDir
    Path home;

    /** Two questions: one fine, one with a failed tool, rated bad. */
    private static String[] fill(ChatStore store) {
        ChatInfo c = store.create("Checkout errors", "cheap", null);
        ChatMessage q1 = store.append(ChatMessage.user(c.id(), "Why did checkout fail?"));
        store.turnAsked(c.id(), q1.seq(), "[Now] Friday", "Logsift's rules");
        ChatMessage a1 = store.append(ChatMessage.assistant(c.id(), "cheap"));
        store.replace(a1.withText("The database.").plusUsage("cheap", 900, 20, 0).withMillis(1500));
        ChatMessage q2 = store.append(ChatMessage.user(c.id(), "Since when?"));
        store.turnAsked(c.id(), q2.seq(), "[Now] Friday", "Logsift's rules");
        ChatMessage t = store.append(ChatMessage.tool(c.id(), "open_log", "{\"path\":\"/nope\"}"));
        store.replace(t.withResult("No such file", false, 4));
        ChatMessage a2 = store.append(ChatMessage.assistant(c.id(), "cheap"));
        store.replace(a2.withText("Since 10:42.").plusUsage("cheap", 1200, 30, 0).withMillis(2500));
        store.rate(c.id(), q2.seq(), -1, "It guessed a path");
        return new String[] {c.id() + ":" + q1.seq(), c.id() + ":" + q2.seq()};
    }

    private static Tool tool(List<Tool> tools, String name) {
        return tools.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void anAgentReadsTheAuditFromTheChatsWhenLogsiftIsntRunning() throws Exception {
        String[] ids;
        try (ChatStore store = ChatStore.open(home.resolve("chat"))) {
            ids = fill(store);
        }
        List<Tool> tools = AuditTools.all(AuditTools.fromHome(home));
        String flagged = tool(tools, AuditTools.LIST).run(new Tool.Args(Map.of()), null);
        assertTrue(flagged.startsWith("2 questions asked in the last 7 days; 1 flagged shown."), flagged);
        assertTrue(flagged.contains("**Question:** Since when?") && !flagged.contains("Why did checkout fail?"), flagged);
        assertTrue(flagged.contains("Verdict: bad — It guessed a path"), flagged);
        assertTrue(flagged.contains("- Id: " + ids[1]), flagged);
        String all = tool(tools, AuditTools.LIST).run(new Tool.Args(Map.of("show", "all", "days", 0)), null);
        assertTrue(all.contains("Why did checkout fail?") && all.contains("2 questions asked in all; 2 shown"), all);

        String one = tool(tools, AuditTools.QUESTION).run(new Tool.Args(Map.of("id", ids[1])), null);
        assertTrue(one.contains("| Tool failed |") && one.contains("> No such file"), one);
        assertFalse(one.contains("## Instructions"));
        String told = tool(tools, AuditTools.QUESTION).run(new Tool.Args(Map.of("id", ids[1], "instructions", true)), null);
        assertTrue(told.endsWith("## Instructions\n\nLogsift's rules"), told);
        // read, then let go at once: Logsift can open its chats again
        try (ChatStore again = ChatStore.open(home.resolve("chat"))) {
            assertEquals(2, again.totals(0).questions());
        }
    }

    @Test
    void mistakesAreSaidToTheAgent() throws Exception {
        try (ChatStore store = ChatStore.open(home.resolve("chat"))) {
            fill(store);
        }
        List<Tool> tools = AuditTools.all(AuditTools.fromHome(home));
        Tool.Failure f = assertThrows(Tool.Failure.class,
                () -> tool(tools, AuditTools.QUESTION).run(new Tool.Args(Map.of("id", "nope")), null));
        assertTrue(f.getMessage().contains("chat:seq"), f.getMessage());
        f = assertThrows(Tool.Failure.class,
                () -> tool(tools, AuditTools.QUESTION).run(new Tool.Args(Map.of("id", "gone:3")), null));
        assertTrue(f.getMessage().startsWith("No question gone:3"), f.getMessage());
        f = assertThrows(Tool.Failure.class,
                () -> tool(tools, AuditTools.LIST).run(new Tool.Args(Map.of("show", "everything")), null));
        assertTrue(f.getMessage().contains("flagged, all, bad"), f.getMessage());
    }

    @Test
    void logsiftMcpOffersThemAfterTheLogsTools() {
        List<String> names = logsift.agent.mcp.McpMain.tools(home).stream().map(Tool::name).toList();
        assertEquals(List.of(AuditTools.LIST, AuditTools.QUESTION), names.subList(names.size() - 2, names.size()));
        assertEquals(logsift.agent.LogTools.all().size() + 2, names.size());
        assertTrue(logsift.agent.LogTools.all().stream().noneMatch(t -> t.name().startsWith("assistant_")),
                "not for the window's own assistant");
    }

    @Test
    void noChatsYetIsSaid(@TempDir Path empty) throws Exception {
        String s = tool(AuditTools.all(AuditTools.fromHome(empty)), AuditTools.LIST).run(new Tool.Args(Map.of()), null);
        assertTrue(s.startsWith("No chats yet"), s);
    }

    @Test
    void aRunningLogsiftIsAskedAndAnswersIntoAFileOfItsOwn() throws Exception {
        try (ChatStore store = ChatStore.inMemory();
             logsift.core.SingleInstance si = logsift.core.SingleInstance.listen(home, paths -> {
             }, (cmd, reply) -> reply.accept(AuditTools.run(home, store, cmd)))) {
            String[] ids = fill(store);
            assertTrue(Files.exists(home.resolve("instance")), "listening: " + si);
            String s = AuditTools.askRunning(home, AuditTools.LIST, Map.of("days", 7L, "show", "bad", "limit", 5L,
                    "details", false));
            assertTrue(s.contains("**Question:** Since when?"), s);
            Tool.Failure f = assertThrows(Tool.Failure.class,
                    () -> AuditTools.askRunning(home, AuditTools.QUESTION, Map.of("id", "x:9")));
            assertTrue(f.getMessage().startsWith("No question x:9"), f.getMessage());
            assertTrue(AuditTools.askRunning(home, AuditTools.QUESTION, Map.of("id", ids[0])).contains("The database."));
            try (var left = Files.list(home.resolve("chat").resolve("audit-answers"))) {
                assertEquals(0, left.count(), "each answer's file goes once read");
            }
            // only an answer's own name: nothing is written anywhere else
            assertEquals(1, AuditTools.run(home, store, List.of("audit", AuditTools.LIST, "{}", "../../evil.md")));
            assertEquals(1, AuditTools.run(home, store, List.of("audit", "read_lines", "{}",
                    java.util.UUID.randomUUID() + ".md")));
            assertFalse(Files.exists(home.resolve("evil.md")));
        }
    }
}
