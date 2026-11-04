package logsift.chat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The assistant's settings, in Logsift's folder ({@code chat/settings.properties}).
 *
 * @param model   the model new chats start with (null: Copilot's choice)
 * @param effort  how hard it thinks (null: the model's own)
 * @param copilot the copilot program chosen by hand (null: found)
 */
public record ChatSettings(String model, String effort, String copilot) {
    public static final ChatSettings DEFAULT = new ChatSettings(null, null, null);

    static Path file(Path home) {
        return home.resolve("chat").resolve("settings.properties");
    }

    public static ChatSettings load(Path home) {
        Path f = file(home);
        if (!Files.isRegularFile(f)) {
            return DEFAULT;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return DEFAULT;
        }
        return new ChatSettings(blank(p.getProperty("model")), blank(p.getProperty("effort")),
                blank(p.getProperty("copilot")));
    }

    public void save(Path home) throws IOException {
        Properties p = new Properties();
        if (model != null) {
            p.setProperty("model", model);
        }
        if (effort != null) {
            p.setProperty("effort", effort);
        }
        if (copilot != null) {
            p.setProperty("copilot", copilot);
        }
        Path f = file(home);
        Files.createDirectories(f.getParent());
        try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            p.store(w, "Logsift's assistant");
        }
    }

    public ChatSettings withModel(String m, String e) {
        return new ChatSettings(m, e, copilot);
    }

    public ChatSettings withCopilot(String c) {
        return new ChatSettings(model, effort, blank(c));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
