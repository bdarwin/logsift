package logsift.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.swing.AbstractButton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Records the assistant at work (about two minutes): four questions, answered for real by
 * Claude Code on Haiku with Logsift's tools, about a .NET log, a CSV table, a heap dump and a
 * running JVM. Reading a file that isn't open and taking a thread dump ask first, and the video
 * clicks Allow. Waits are sped up. It costs a few cents at most, on the Claude account signed in
 * on this computer.
 *
 * <p>Run with {@code ./gradlew visualTest --tests logsift.ui.AssistantRecording -Pdemo=/some/dir};
 * needs ffmpeg and a signed-in {@code claude} on the PATH.
 */
@Tag("visual")
@EnabledIfSystemProperty(named = "logsift.demo", matches = ".+")
class AssistantRecording extends Recording {
    /** How much faster than real time the waits for answers play. */
    private static final int FAST = 4;

    private ChatPanel chat;

    @Override
    String videoName() {
        return "logsift-assistant.mp4";
    }

    @Test
    void record() throws Exception {
        Path dir = Files.createDirectories(out.resolve("logs"));
        Path dotnet = dir.resolve("shop-api.log");
        Files.writeString(dotnet, logsift.tools.TestData.serilog());
        Path sales = dir.resolve("sales.csv");
        TourData.writeSales(sales);
        Path hprof = TourData.heapDump(dir);
        Process jvm = logsift.jvm.FixtureApp.start(false, dir.resolve("billing.log"));
        var service = logsift.chat.ChatService.get();
        service.saveSettings(logsift.chat.ChatSettings.DEFAULT.withEngine(logsift.chat.ChatSettings.CLAUDE)
                .withModel(logsift.chat.ClaudeEngine.PREFIX + "haiku", "low"));
        service.saveOptions(logsift.chat.ChatInstructions.Options.DEFAULT.withInTab(false));
        for (var c : service.store().chats(null, 10_000)) {
            service.store().delete(c.id()); // last recording's chats
        }
        try {
            openWindow();
            startVideo();
            try {
                script(dotnet, sales, hprof);
            } finally {
                stopVideo();
                onEdt(() -> {
                    frame.dispose();
                    return null;
                });
            }
        } finally {
            logsift.jvm.FixtureApp.stop(jvm);
        }
    }

    private void script(Path dotnet, Path sales, Path hprof) throws Exception {
        say("Logsift's assistant: Claude Code or GitHub Copilot, reading with Logsift's own tools");
        LogTab api = open(dotnet);
        chat = onEdt(frame::showChat);
        hold(2500);

        ask("Why is the shop API failing, and what should I fix first?",
                "A .NET log: it groups the exceptions by their inner cause");
        say("Each answer cites lines you can click, and says what it cost");
        String href = firstLink();
        if (href != null) {
            onEdt(() -> {
                chat.linkForTest(href);
                return null;
            });
            hold(2500);
        }

        newChat();
        open(sales);
        ask("Which region sold the most, and which product drove it?", "A CSV table: it asks it in SQL");

        newChat();
        onEdt(() -> {
            frame.tabsForTest().setSelectedComponent(api);
            return null;
        });
        ask("What is filling the memory in the heap dump " + hprof + "?",
                "A heap dump: what fills the memory, and what keeps it alive");

        newChat();
        ask("Take a thread dump of the FixtureApp JVM running on this computer: is anything stuck?",
                "A running JVM: a thread dump only after you say yes");

        say("Logsift: free and open source, for Windows, macOS and Linux · logsift.dev");
        showCursor = false;
        hold(4000);
    }

    /** Types a question, sends it, answers what it asks (Allow), waits sped up, then reads the answer. */
    private void ask(String question, String caption) throws Exception {
        say(caption);
        moveTo(onEdt(chat::inputForTest), 600);
        typeInto(onEdt(chat::inputForTest), question, 45);
        hold(500);
        press("Enter");
        onEdt(() -> {
            chat.sendForTest();
            return null;
        });
        speed = FAST;
        say(caption + "  (sped up " + FAST + "×)");
        long deadline = System.currentTimeMillis() + 300_000;
        while (onEdt(chat::busyForTest)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("no answer in 5 minutes");
            }
            ChatGate gate = onEdt(chat::gateForTest);
            if (onEdt(gate::isShowing)) {
                speed = 1;
                say("It asks before it reads a file you haven't opened, or reaches a running JVM");
                hold(2200);
                AbstractButton allow = onEdt(() -> (AbstractButton) findAll(gate, c -> c instanceof AbstractButton x
                        && x.isShowing() && "Allow".equals(x.getText())).get(0));
                moveTo(allow, 700);
                hold(400);
                click(allow);
                hold(600);
                speed = FAST;
                say(caption + "  (sped up " + FAST + "×)");
            }
            frame();
        }
        speed = 1;
        readAnswer();
    }

    /** Shows the answer from its top, then scrolls down through it. */
    private void readAnswer() throws Exception {
        ChatTranscript tr = onEdt(chat::transcriptForTest);
        javax.swing.JScrollBar bar = onEdt(() -> ((javax.swing.JScrollPane) javax.swing.SwingUtilities
                .getAncestorOfClass(javax.swing.JScrollPane.class, tr)).getVerticalScrollBar());
        int top = onEdt(() -> {
            List<ChatEntryView> views = tr.viewsForTest();
            for (int i = views.size() - 1; i >= 0; i--) {
                if (views.get(i).message().kind() == logsift.chat.ChatMessage.Kind.ASSISTANT) {
                    return Math.max(0, views.get(i).getY() - 12);
                }
            }
            return 0;
        });
        scrollTo(bar, top, 600);
        hold(3000);
        still("answer");
        int end = onEdt(() -> bar.getMaximum() - bar.getVisibleAmount());
        scrollTo(bar, end, Math.min(8000, Math.max(1500, (end - top) * 5)));
        hold(1500);
    }

    private String firstLink() throws Exception {
        return onEdt(() -> {
            var views = chat.transcriptForTest().viewsForTest();
            for (int i = views.size() - 1; i >= 0; i--) {
                var m = java.util.regex.Pattern.compile("\\((logsift:[^)\\s]+)\\)").matcher(views.get(i).message().text());
                if (views.get(i).message().kind() == logsift.chat.ChatMessage.Kind.ASSISTANT && m.find()) {
                    return m.group(1);
                }
            }
            return null;
        });
    }

    private void newChat() throws Exception {
        onEdt(() -> {
            chat.newChat();
            return null;
        });
        hold(800);
    }
}
