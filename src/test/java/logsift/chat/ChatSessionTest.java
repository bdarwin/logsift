package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import logsift.agent.LogTools;
import logsift.agent.Masking;
import logsift.agent.Scope;
import logsift.agent.Workspace;
import logsift.core.LogDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A chat as it goes, with an assistant that follows a script: it calls Logsift's real tools over
 * a log the window has open, streams an answer, and says what it cost. Everything is stored.
 */
class ChatSessionTest {
    @TempDir
    Path dir;

    private Path log;
    private LogDocument doc;
    private Workspace workspace;
    private ChatStore store;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private CountDownLatch idle;

    /** An assistant that plays {@code script} for each question. */
    private final class Scripted implements ChatEngine {
        final List<Setup> opened = new ArrayList<>();
        Consumer<Turn> script;
        boolean resumeFails;
        /** Its id, when it says it only with its first answer (as Claude Code does): set by the script. */
        boolean idLater;
        volatile String lateId;

        record Turn(String text, Setup setup, Listener l) {}

        @Override
        public String name() {
            return "Scripted";
        }

        @Override
        public void start() {
        }

        @Override
        public String user() {
            return "tester";
        }

        @Override
        public List<Model> models() {
            return List.of(new Model("cheap", "Cheap", 10, 50, 1000, List.of(), null, true));
        }

        @Override
        public Quota quota() {
            return new Quota(3, 1500, false, 99.8, "2026-12-01T00:00:00Z");
        }

        @Override
        public Conversation open(Setup setup, Listener l) throws Exception {
            if (setup.resume() != null && resumeFails) {
                throw new Exception("Session not found");
            }
            opened.add(setup);
            return new Conversation() {
                @Override
                public String id() {
                    return idLater ? lateId : "conv-" + opened.size();
                }

                @Override
                public void send(String text) {
                    Thread t = new Thread(() -> script.accept(new Turn(text, setup, l)));
                    t.start();
                }

                @Override
                public void abort() {
                    l.on(new ChatEvent.Idle(true));
                }

                @Override
                public void setModel(String model, String effort) {
                    seen.add("model " + model + " " + effort);
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void close() {
        }
    }

    private final ChatSession.View view = new ChatSession.View() {
        @Override
        public void changed(ChatMessage m, boolean added) {
            seen.add((added ? "+" : "~") + m.kind() + (m.tool() != null ? " " + m.tool() : ""));
        }

        @Override
        public void busy(boolean busy) {
            seen.add(busy ? "busy" : "idle");
            if (!busy) {
                idle.countDown();
            }
        }

        @Override
        public void titled(String title) {
            seen.add("title " + title);
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        log = dir.resolve("app.log");
        Files.writeString(log, """
                2026-11-04 10:41:00 INFO  [main] app: started
                2026-11-04 10:42:01 ERROR [http-1] db: connection refused to db-1:5432
                2026-11-04 10:42:02 ERROR [http-2] db: connection refused to db-1:5432
                2026-11-04 10:43:00 INFO  [main] app: recovered
                """);
        doc = LogDocument.open(log);
        workspace = new Workspace(new Workspace.Options(new Scope(List.of(dir), dir), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 20_000, List.of(), dir.resolve("audit.log")));
        store = ChatStore.inMemory();
        idle = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        workspace.close();
        store.close();
        doc.close();
    }

    private ChatSession session(Scripted engine, ChatInfo chat) {
        return new ChatSession(store, engine, chat, ChatTools.of(LogTools.all(), workspace),
                ChatTools.instructions(null), "cheap", null, view);
    }

    @Test
    void aTurnCallsLogsiftsToolsOnTheOpenLogAndStoresEverything() throws Exception {
        String h = workspace.adopt(log, doc);
        assertEquals("log:1", h);
        Scripted engine = new Scripted();
        engine.script = t -> {
            try {
                t.l().on(new ChatEvent.Usage("cheap", 3500, 40, 0, 4.0e7, 900));
                t.l().on(new ChatEvent.ToolStarted("c1", "read_lines", "{\"log\": \"log:1\", \"from\": 2, \"count\": 2}"));
                ChatEngine.ToolSpec read = t.setup().tools().stream().filter(x -> x.name().equals("read_lines"))
                        .findFirst().orElseThrow();
                ChatEngine.ToolHandler.Result r = read.handler().call("c1", Map.of("log", "log:1", "from", 2, "count", 2));
                assertFalse(r.failed(), r.text());
                t.l().on(new ChatEvent.ToolFinished("c1", true));
                t.l().on(new ChatEvent.Text("m2", "The database refused connections: "));
                t.l().on(new ChatEvent.Text("m2", "[app.log:2-3](logsift:log:1:2-3)."));
                t.l().on(new ChatEvent.Usage("cheap", 3600, 30, 3500, 6.0e6, 700));
                t.l().on(new ChatEvent.Idle(false));
            } catch (Exception e) {
                t.l().on(new ChatEvent.Failed(e.toString()));
                t.l().on(new ChatEvent.Idle(false));
            }
        };
        ChatInfo chat = store.create("New chat", "cheap", log.toString());
        try (ChatSession s = session(engine, chat)) {
            s.ask("Why did requests fail at 10:42?", "Looking at log:1 (app.log), line 2 chosen.");
            assertTrue(idle.await(10, TimeUnit.SECONDS));
            assertFalse(s.isBusy());
        }
        // the question went with what the person was looking at
        assertTrue(engine.opened.get(0).instructions().contains("logsift:log:1:1204"));
        List<ChatMessage> all = store.last(chat.id(), 10);
        assertEquals(List.of(ChatMessage.Kind.USER, ChatMessage.Kind.TOOL, ChatMessage.Kind.ASSISTANT),
                all.stream().map(ChatMessage::kind).toList());
        assertEquals("Why did requests fail at 10:42?", all.get(0).text()); // as asked, without the context
        ChatMessage tool = all.get(1);
        assertEquals("read_lines", tool.tool());
        assertEquals(Boolean.TRUE, tool.ok());
        assertTrue(tool.text().contains("connection refused to db-1:5432"), tool.text());
        ChatMessage answer = all.get(2);
        assertEquals("The database refused connections: [app.log:2-3](logsift:log:1:2-3).", answer.text());
        assertEquals(7100, answer.tokensIn()); // both calls of the model in the turn
        assertEquals(70, answer.tokensOut());
        assertEquals(0.046, answer.credits(), 1e-9);
        assertTrue(answer.millis() > 0);
        ChatInfo after = store.chat(chat.id());
        assertEquals("Why did requests fail at 10:42?", after.title());
        assertEquals("conv-1", after.session());
        assertEquals("busy", seen.get(0));
        assertEquals("idle", seen.get(seen.size() - 1));
        assertTrue(Files.readString(dir.resolve("audit.log")).contains("read_lines"), "audited like MCP calls");
    }

    @Test
    void anAssistantThatSaysItsIdWithItsAnswerIsKeptToo() throws Exception {
        Scripted engine = new Scripted();
        engine.idLater = true; // Claude Code's session id comes in its first answer
        engine.script = t -> {
            engine.lateId = "late-1";
            t.l().on(new ChatEvent.Text("m1", "Answered."));
            t.l().on(new ChatEvent.Idle(false));
        };
        ChatInfo chat = store.create("c", "cheap", null);
        try (ChatSession s = session(engine, chat)) {
            s.ask("Anything odd?", null);
            assertTrue(idle.await(10, TimeUnit.SECONDS));
        }
        List<ChatMessage> all = store.last(chat.id(), 10);
        assertEquals(List.of(ChatMessage.Kind.USER, ChatMessage.Kind.ASSISTANT),
                all.stream().map(ChatMessage::kind).toList(), "no error for an id not known yet");
        assertEquals("late-1", store.chat(chat.id()).session(), "kept, to go on with it later");
    }

    @Test
    void stoppingEndsTheTurnAndSaysSo() throws Exception {
        Scripted engine = new Scripted();
        CountDownLatch streaming = new CountDownLatch(1);
        engine.script = t -> {
            t.l().on(new ChatEvent.Text("m1", "Looking"));
            streaming.countDown();
        };
        ChatInfo chat = store.create("c", "cheap", null);
        try (ChatSession s = session(engine, chat)) {
            s.ask("Anything odd?", null);
            assertTrue(streaming.await(10, TimeUnit.SECONDS));
            s.stop();
            assertTrue(idle.await(10, TimeUnit.SECONDS));
        }
        List<ChatMessage> all = store.last(chat.id(), 10);
        assertEquals(ChatMessage.Kind.NOTE, all.get(all.size() - 1).kind());
        assertEquals("Stopped.", all.get(all.size() - 1).text());
        assertEquals("Looking", all.get(1).text()); // what came before is kept
    }

    @Test
    void aChatCopilotForgotGoesOnWithWhatWasSaid() throws Exception {
        ChatInfo chat = store.create("Old", "cheap", null);
        store.append(ChatMessage.user(chat.id(), "Which service fails most?"));
        ChatMessage a = store.append(ChatMessage.assistant(chat.id(), "cheap"));
        store.replace(a.withText("orders, 412 errors"));
        store.setSession(chat.id(), "gone-session");
        Scripted engine = new Scripted();
        engine.resumeFails = true;
        engine.script = t -> {
            t.l().on(new ChatEvent.Text("m", "Still orders."));
            t.l().on(new ChatEvent.Idle(false));
        };
        try (ChatSession s = session(engine, store.chat(chat.id()))) {
            s.ask("And now?", null);
            assertTrue(idle.await(10, TimeUnit.SECONDS));
        }
        ChatEngine.Setup again = engine.opened.get(0);
        assertEquals(null, again.resume());
        assertTrue(again.instructions().contains("orders, 412 errors"), "told what it said before");
        assertEquals("conv-1", store.chat(chat.id()).session());
        assertTrue(store.last(chat.id(), 10).stream().anyMatch(m -> m.kind() == ChatMessage.Kind.NOTE
                && m.text().contains("started again")));
        assertEquals("Old", store.chat(chat.id()).title()); // a chat with a name keeps it
    }

    @Test
    void aFailedTurnIsAnErrorInTheChatAndChangingModelReachesTheAssistant() throws Exception {
        Scripted engine = new Scripted();
        engine.script = t -> {
            t.l().on(new ChatEvent.Failed("You have used all your premium requests"));
            t.l().on(new ChatEvent.Idle(false));
        };
        ChatInfo chat = store.create("c", "cheap", null);
        try (ChatSession s = session(engine, chat)) {
            s.ask("hi", null);
            assertTrue(idle.await(10, TimeUnit.SECONDS));
            s.setModel("dear", "high");
            assertEquals("dear", s.model());
        }
        List<ChatMessage> all = store.last(chat.id(), 10);
        assertEquals(ChatMessage.Kind.ERROR, all.get(1).kind());
        assertTrue(seen.contains("model dear high"));
        assertEquals("dear", store.chat(chat.id()).model());
    }

    @Test
    void aFirstQuestionNamesTheChatShort() {
        assertEquals("Why errors?", ChatSession.title("  Why   errors?\n"));
        String t = ChatSession.title("Why do the checkout requests time out every night between two and three o'clock?");
        assertTrue(t.length() <= 58 && t.endsWith("…"), t);
    }

    @Test
    void theWindowsOpenLogIsReadAsIsAndStaysOpen() throws Exception {
        String h = workspace.adopt(log, doc);
        assertEquals(h, workspace.adopt(log, doc), "the same document has one handle");
        assertEquals(h, workspace.open(log), "opening its path gives the window's");
        assertNotNull(workspace.log(h));
        workspace.forget(doc);
        assertEquals(4, doc.lineCount()); // still open: it's the window's
        assertFalse(workspace.logs().containsKey(h));
    }
}
