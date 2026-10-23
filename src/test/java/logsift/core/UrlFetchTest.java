package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UrlFetchTest {
    @TempDir
    Path dir;
    private HttpServer server;
    private String base;
    private final List<String> authSeen = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/logs/app.log", x -> send(x, 200, "line 1\nline 2\n", null));
        server.createContext("/download", x -> send(x, 200, "a,b\n1,2\n", "attachment; filename=\"report.csv\""));
        server.createContext("/moved", x -> {
            x.getResponseHeaders().add("Location", "/logs/app.log");
            send(x, 302, "", null);
        });
        server.createContext("/secret.log", x -> {
            String a = x.getRequestHeaders().getFirst("Authorization");
            authSeen.add(String.valueOf(a));
            boolean ok = "Basic dTpwdw==".equals(a) || "Bearer t0k".equals(a);
            send(x, ok ? 200 : 401, ok ? "secret\n" : "", null);
        });
        server.createContext("/gone", x -> send(x, 404, "", null));
        server.createContext("/", x -> send(x, 200, "root\n", null));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void send(com.sun.net.httpserver.HttpExchange x, int code, String body, String disposition)
            throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (disposition != null) {
            x.getResponseHeaders().add("Content-Disposition", disposition);
        }
        x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(b);
        }
    }

    private Path get(String path, UrlFetch.Auth auth) throws IOException {
        return UrlFetch.download(UrlFetch.parse(base + path), auth, dir, null, () -> false);
    }

    @Test
    void downloadsNamedByAddressOrServer() throws Exception {
        Path a = get("/logs/app.log", UrlFetch.Auth.NONE);
        assertEquals("app.log", a.getFileName().toString());
        assertEquals("line 1\nline 2\n", Files.readString(a));
        assertEquals("app-2.log", get("/logs/app.log", UrlFetch.Auth.NONE).getFileName().toString(),
                "a name already there gets a number");
        assertEquals("report.csv", get("/download", UrlFetch.Auth.NONE).getFileName().toString());
        assertEquals("app-3.log", get("/moved", UrlFetch.Auth.NONE).getFileName().toString(), "follows redirects");
        assertEquals("download.log", get("/", UrlFetch.Auth.NONE).getFileName().toString());
    }

    @Test
    void signsIn() throws Exception {
        IOException e = assertThrows(IOException.class, () -> get("/secret.log", UrlFetch.Auth.NONE));
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertEquals("secret\n", Files.readString(get("/secret.log", new UrlFetch.Auth("u", "pw", null))));
        assertEquals("secret\n", Files.readString(get("/secret.log", new UrlFetch.Auth(null, null, "t0k"))));
        assertEquals("Bearer t0k", authSeen.get(authSeen.size() - 1));
    }

    @Test
    void saysWhatWentWrong() {
        assertTrue(assertThrows(IOException.class, () -> get("/gone", UrlFetch.Auth.NONE)).getMessage().contains("404"));
        assertThrows(IOException.class, () -> UrlFetch.parse("ftp://x/y"));
        assertThrows(IOException.class, () -> UrlFetch.parse("not a url"));
        server.stop(0);
        assertTrue(assertThrows(IOException.class, () -> get("/logs/app.log", UrlFetch.Auth.NONE)).getMessage()
                .contains("No answer"));
    }

    @Test
    void cancels() throws Exception {
        assertNull(UrlFetch.download(URI.create(base + "/logs/app.log"), null, dir, null, () -> true));
        try (var s = Files.list(dir)) {
            assertEquals(0, s.count(), "nothing left behind");
        }
    }

    @Test
    void namesFromDisposition() {
        URI u = URI.create("http://h/x/");
        assertEquals("a b.log", UrlFetch.fileName(u, "attachment; filename*=UTF-8''a%20b.log"));
        assertEquals(".._etc_passwd", UrlFetch.fileName(u, "attachment; filename=\"..:etc:passwd\""),
                "no characters a file name can't have");
        assertEquals("passwd", UrlFetch.fileName(u, "attachment; filename=\"../../etc/passwd\""));
    }
}
