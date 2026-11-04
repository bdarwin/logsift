package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Files named in a question with @: which ones, and the one line that tells the assistant. */
class ChatMentionsTest {
    static final ChatMentions.File OPEN = new ChatMentions.File("app.log", Path.of("/var/log/app.log"), "log:2", "/var/log");
    static final ChatMentions.File RECENT = new ChatMentions.File("payments.log", Path.of("/srv/pay/payments.log"), null,
            "/srv/pay");
    static final ChatMentions.File OLD = new ChatMentions.File("app-old.log", Path.of("/tmp/app-old.log"), null, "/tmp");

    @Test
    void namesThatStartWithWhatIsTypedComeFirst() {
        List<ChatMentions.File> all = List.of(RECENT, OLD, OPEN);
        assertEquals(List.of(OLD, OPEN), ChatMentions.match(all, "app"));
        assertEquals(List.of(RECENT), ChatMentions.match(all, "srv"), "by its folder too");
        assertEquals(List.of(RECENT, OLD, OPEN), ChatMentions.match(all, ""), "all, for a bare @");
    }

    @Test
    void theAssistantIsToldWhichFileEachNameIs() {
        assertEquals("[Named] @app.log = log:2; @payments.log = " + Path.of("/srv/pay/payments.log") + ".",
                ChatMentions.line(List.of(OPEN, RECENT)));
        assertNull(ChatMentions.line(List.of()));
    }
}
