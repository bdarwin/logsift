package logsift.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads a file over HTTP(S) into a folder, for opening or searching it there. */
public final class UrlFetch {
    private UrlFetch() {}

    /**
     * How to sign in: nothing, a user and password (Basic), or a token (sent as
     * {@code Authorization: Bearer <token>}, or as given if it already names a scheme).
     */
    public record Auth(String user, String password, String token) {
        public static final Auth NONE = new Auth(null, null, null);

        String header() {
            if (token != null && !token.isBlank()) {
                String t = token.trim();
                return t.contains(" ") ? t : "Bearer " + t;
            }
            if (user != null && !user.isBlank()) {
                String up = user + ":" + (password == null ? "" : password);
                return "Basic " + Base64.getEncoder().encodeToString(up.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        }
    }

    private static final int MAX_REDIRECTS = 5;
    private static final Pattern DISPOSITION_NAME = Pattern.compile(
            "filename\\*?=\\s*(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    /** Checks that a text is an http(s) URL; returns it as a URI. */
    public static URI parse(String text) throws IOException {
        try {
            URI u = new URI(text.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https") || u.getHost() == null) {
                throw new IOException("Not an http or https address: " + text.trim());
            }
            return u;
        } catch (URISyntaxException e) {
            throw new IOException("Not a valid address: " + text.trim());
        }
    }

    /**
     * Downloads {@code url} into {@code dir}, named as the server or the address says (a name
     * already there gets a number).
     *
     * @return the file, or null if cancelled
     */
    public static Path download(URI url, Auth auth, Path dir, DoubleConsumer progress, BooleanSupplier cancelled)
            throws IOException {
        URI at = url;
        String authHeader = auth == null ? null : auth.header();
        for (int hop = 0; ; hop++) {
            HttpURLConnection c = (HttpURLConnection) at.toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("User-Agent", "Logsift");
            c.setRequestProperty("Accept-Encoding", "identity");
            if (authHeader != null && at.getHost().equalsIgnoreCase(url.getHost())) {
                c.setRequestProperty("Authorization", authHeader); // never to another host
            }
            int code;
            try {
                code = c.getResponseCode();
            } catch (java.net.UnknownHostException e) {
                throw new IOException("Unknown host: " + at.getHost());
            } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
                throw new IOException("No answer from " + at.getHost() + (at.getPort() > 0 ? ":" + at.getPort() : "")
                        + " (" + e.getMessage() + ")");
            }
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                c.disconnect();
                if (hop >= MAX_REDIRECTS) {
                    throw new IOException("Too many redirects");
                }
                at = at.resolve(c.getHeaderField("Location"));
                continue;
            }
            if (code == 401 || code == 403) {
                c.disconnect();
                throw new IOException(code == 401
                        ? "The server wants a user and password or a token (401)"
                        : "The server refused access (403)" + (authHeader == null ? "; it may need a sign-in" : ""));
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("The server answered " + code
                        + (c.getResponseMessage() == null ? "" : " " + c.getResponseMessage()));
            }
            Path out = unique(dir, fileName(at, c.getHeaderField("Content-Disposition")));
            long total = c.getContentLengthLong();
            boolean ok = false;
            try (InputStream in = c.getInputStream(); OutputStream o = Files.newOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled.getAsBoolean()) {
                        return null;
                    }
                    o.write(buf, 0, n);
                    done += n;
                    if (progress != null && total > 0) {
                        progress.accept(Math.min(1, (double) done / total));
                    }
                }
                ok = true;
            } finally {
                c.disconnect();
                if (!ok) {
                    Files.deleteIfExists(out);
                }
            }
            return out;
        }
    }

    /** The file name from Content-Disposition, else the address's last part, else download.log. */
    static String fileName(URI url, String disposition) {
        if (disposition != null) {
            Matcher m = DISPOSITION_NAME.matcher(disposition);
            if (m.find()) {
                String n = safe(URLDecoder.decode(m.group(1).trim(), StandardCharsets.UTF_8));
                if (!n.isEmpty()) {
                    return n;
                }
            }
        }
        String path = url.getPath() == null ? "" : url.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        String n = safe(last);
        return n.isEmpty() ? "download.log" : n;
    }

    /** A name without folders or characters a file name can't have. */
    private static String safe(String name) {
        String n = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        n = n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        return n.equals(".") || n.equals("..") ? "" : n;
    }

    private static Path unique(Path dir, String name) {
        Path p = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; Files.exists(p); i++) {
            p = dir.resolve(stem + "-" + i + ext);
        }
        return p;
    }
}
