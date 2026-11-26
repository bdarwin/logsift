package logsift.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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
    void copilotsQuestionsAreAnsweredAsThePersonDecides() throws Exception {
        java.util.List<String> notes = new java.util.ArrayList<>();
        java.util.List<String> asked = new java.util.ArrayList<>();
        // no credential store: kept in a file only when the person says yes
        assertEquals("allow", CopilotEngine.decision(null, "needs-plaintext-consent", q -> asked.add(q), notes::add));
        assertTrue(asked.get(0).contains("Programs running as you can read that file"), asked.get(0));
        Exception no = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> CopilotEngine.decision(null, "needs-plaintext-consent", q -> false, notes::add));
        assertTrue(no.getMessage().contains("declined"), no.getMessage());
        // several accounts: the one just authorized
        var done = new com.microsoft.copilot.generated.rpc.AuthLoginStepCompleted();
        done.setResult(new com.microsoft.copilot.generated.rpc.AuthLoginResultDto(
                com.microsoft.copilot.generated.rpc.AuthLoginResultStatus.NEEDS_ACCOUNT_SELECTION, "https://github.com",
                "bdarwin", List.of(
                        new com.microsoft.copilot.generated.rpc.AuthLoginAccount("acc-1", "https://github.com", "work-me", null),
                        new com.microsoft.copilot.generated.rpc.AuthLoginAccount("acc-2", "https://github.com", "bdarwin", null))));
        assertEquals("acc-2", CopilotEngine.decision(done, "needs-account-selection", q -> false, notes::add));
        assertNull(CopilotEngine.decision(done, "completed", q -> false, notes::add), "nothing to decide");
        assertTrue(String.join("\n", notes).contains("choosing bdarwin"), notes.toString());
        assertEquals("needs-plaintext-consent",
                com.microsoft.copilot.generated.rpc.AuthLoginResultStatus.NEEDS_PLAINTEXT_CONSENT.getValue(),
                "Copilot's own word for it, with hyphens");
    }

    @Test
    void theSignInLogIsInLogsiftsChatFolderAndKeepsNoToken(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        CopilotEngine e = new CopilotEngine(null, dir.resolve("copilot-home"), dir.resolve("chat").resolve("work"));
        assertEquals(dir.resolve("chat").resolve("sign-in.log"), e.signInLog());
        e.note("step open-url");
        assertTrue(java.nio.file.Files.readString(e.signInLog()).contains("step open-url"));
    }
}
