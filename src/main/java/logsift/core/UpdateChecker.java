package logsift.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub for the latest release. Only one anonymous request is made; nothing about the
 * user or their logs is sent.
 */
public final class UpdateChecker {
    private UpdateChecker() {}

    public static final String LATEST = "https://api.github.com/repos/bdarwin/logsift/releases/latest";

    /** The latest published release. */
    public record Release(String version, String url) {}

    /**
     * The URL to ask: {@link #LATEST}, or the {@code logsift.updateUrl} system property (tests);
     * null if update checks are turned off with {@code logsift.updateUrl=none}.
     */
    public static String url() {
        String u = System.getProperty("logsift.updateUrl", LATEST);
        return u.isBlank() || u.equals("none") ? null : u;
    }

    /** The latest release, or empty if it could not be read. */
    public static Optional<Release> latest(String url) throws IOException {
        System.setProperty("java.net.useSystemProxies", "true"); // office proxies
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Logsift/" + Version.current());
        try {
            if (c.getResponseCode() != 200) {
                return Optional.empty();
            }
            try (InputStream in = c.getInputStream()) {
                byte[] b = in.readNBytes(1 << 20);
                return parse(new String(b, StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    /** Reads {@code tag_name} and {@code html_url} from GitHub's release JSON. */
    static Optional<Release> parse(String json) {
        String tag = field(json, "tag_name");
        String url = field(json, "html_url");
        if (tag == null || url == null) {
            return Optional.empty();
        }
        return Optional.of(new Release(tag.startsWith("v") ? tag.substring(1) : tag, url));
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\/", "/") : null;
    }
}
