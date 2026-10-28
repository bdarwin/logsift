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

    /** Where a version's download page is; the link is always made here, never taken from the answer. */
    static final String RELEASES = "https://github.com/bdarwin/logsift/releases/tag/v";

    /** The download page of a version (the list of releases for anything but a plain version number). */
    public static String page(String version) {
        return isPlainVersion(version) ? RELEASES + version : "https://github.com/bdarwin/logsift/releases";
    }

    /** 1.4.0, 2.0.0-beta.1: digits and dots, and maybe a short suffix; nothing that could change a link. */
    static boolean isPlainVersion(String v) {
        return v != null && v.matches("\\d{1,4}(\\.\\d{1,6}){0,3}(-[0-9A-Za-z.]{1,20})?");
    }

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

    /**
     * The latest release, or empty if it could not be read. Certificates are trusted when Java or
     * this computer trusts them (a company proxy's usually is); if not, the check is made again
     * without checking the certificate. That is safe here: only a version number is read, and the
     * download page is always GitHub's (see {@link #RELEASES}).
     */
    public static Optional<Release> latest(String url) throws IOException {
        System.setProperty("java.net.useSystemProxies", "true"); // office proxies
        try {
            return latest(url, false);
        } catch (IOException e) {
            if (!Tls.isTrustProblem(e)) {
                throw e;
            }
            return latest(url, true);
        }
    }

    private static Optional<Release> latest(String url, boolean anyCertificate) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        if (c instanceof javax.net.ssl.HttpsURLConnection https) {
            Tls.apply(https, anyCertificate);
        }
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

    /**
     * Reads {@code tag_name} from GitHub's release JSON. Only a plain version number is taken
     * (1.4.0, 2.0.0-beta.1); the page to open is made from it, not read from the answer.
     */
    static Optional<Release> parse(String json) {
        String tag = field(json, "tag_name");
        if (tag == null) {
            return Optional.empty();
        }
        String v = tag.startsWith("v") ? tag.substring(1) : tag;
        return isPlainVersion(v) ? Optional.of(new Release(v, page(v))) : Optional.empty();
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\/", "/") : null;
    }
}
