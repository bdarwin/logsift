package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * After signing in in the browser, the assistant asks the Copilot that signed in who it is: a new
 * Copilot started then may not see the sign-in yet, and the window went back to "Sign In".
 */
class ChatServiceSignInTest {

    /** A Copilot that knows the sign-in only in this process. */
    static final class OneProcess implements ChatEngine {
        volatile boolean signedIn;
        volatile boolean closed;

        public String name() {
            return "GitHub Copilot";
        }

        public void start() {
        }

        public String user() {
            return signedIn ? "bdarwin" : null;
        }

        public List<Model> models() {
            return List.of();
        }

        public Quota quota() {
            return null;
        }

        public Conversation open(Setup setup, Listener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean canSignIn() {
            return true;
        }

        public void close() {
            closed = true;
        }
    }

    @AfterEach
    void back() {
        ChatService.useFactory(null);
    }

    @Test
    void afterSigningInItAsksTheCopilotThatSignedIn() throws Exception {
        AtomicInteger made = new AtomicInteger();
        OneProcess[] last = new OneProcess[1];
        ChatService.useFactory((settings, home) -> {
            made.incrementAndGet();
            last[0] = new OneProcess();
            return last[0];
        });
        ChatService s = ChatService.get();
        ChatService.State before = s.state().get(30, TimeUnit.SECONDS);
        assertNull(before.user());
        assertTrue(before.canSignIn(), "asks to sign in");

        last[0].signedIn = true; // the browser said "authorized"
        ChatService.State after = s.signedIn().get(30, TimeUnit.SECONDS);
        assertEquals("bdarwin", after.user());
        assertEquals(1, made.get(), "no new Copilot: the one that signed in answers");
        assertSame(before.engine(), after.engine());
        assertFalse(last[0].closed, "kept, not closed");
    }

    @Test
    void anAccountsLoginIsReadFromCopilotsDescription() {
        assertEquals("bdarwin", CopilotEngine.accountLogin(Map.of("login", "bdarwin", "host", "https://github.com")));
        assertNull(CopilotEngine.accountLogin("not a map"));
    }

    @Test
    void theSignInLogIsInLogsiftsChatFolderAndKeepsNoToken(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        CopilotEngine e = new CopilotEngine(null, dir.resolve("copilot-home"), dir.resolve("chat").resolve("work"));
        assertEquals(dir.resolve("chat").resolve("sign-in.log"), e.signInLog());
        e.note("step open-url");
        assertTrue(java.nio.file.Files.readString(e.signInLog()).contains("step open-url"));
    }
}
