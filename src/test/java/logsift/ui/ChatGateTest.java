package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ChatGateTest {
    @Test
    void theAssistantAnsweringIsTheOneThatAsks() throws Exception {
        String[] who = {"Claude Code"};
        ChatGate[] gate = new ChatGate[1];
        SwingUtilities.invokeAndWait(() -> gate[0] = new ChatGate(() -> "chat-1", () -> true, words -> { }, () -> who[0]));
        CompletableFuture<Exception> done = CompletableFuture.supplyAsync(() -> {
            try {
                gate[0].files().approve("take a thread dump of app.jar", "jvm:1", "The JVM pauses for a moment.");
                return null;
            } catch (Exception e) {
                return e;
            }
        });
        long end = System.currentTimeMillis() + 10_000;
        while (!gate[0].isAsking() && System.currentTimeMillis() < end) {
            Thread.sleep(20);
        }
        String[] text = new String[1];
        SwingUtilities.invokeAndWait(() -> text[0] = gate[0].textForTest());
        assertTrue(text[0].startsWith("Claude Code wants to take a thread dump of app.jar."), text[0]);
        SwingUtilities.invokeAndWait(() -> gate[0].answerForTest(ChatGate.Answer.DENY));
        assertTrue(done.get(10, TimeUnit.SECONDS) != null, "denied");
    }

    @Test
    void aHeapDumpIsntAllowedForTheRestOfTheChat() throws Exception {
        ChatGate[] gate = new ChatGate[1];
        SwingUtilities.invokeAndWait(() -> gate[0] = new ChatGate(() -> "chat-1", () -> true, words -> { }));
        for (int round = 0; round < 2; round++) {
            CompletableFuture<Exception> done = CompletableFuture.supplyAsync(() -> {
                try {
                    gate[0].files().approve("take a heap dump of app.jar", null, "A full GC, then a pause.");
                    return null;
                } catch (Exception e) {
                    return e;
                }
            });
            long end = System.currentTimeMillis() + 10_000;
            while (!gate[0].isAsking() && System.currentTimeMillis() < end) {
                Thread.sleep(20);
            }
            assertTrue(gate[0].isAsking(), "asked, round " + round);
            boolean[] forChat = new boolean[1];
            SwingUtilities.invokeAndWait(() -> forChat[0] = gate[0].chatButtonShownForTest());
            assertTrue(!forChat[0], "no Allow for This Chat");
            SwingUtilities.invokeAndWait(() -> gate[0].answerForTest(ChatGate.Answer.CHAT));
            assertTrue(done.get(10, TimeUnit.SECONDS) == null, "allowed");
        }
    }
}
