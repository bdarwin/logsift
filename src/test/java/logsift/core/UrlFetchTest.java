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

    /** A server with a certificate nothing trusts (as behind a company proxy): said so, then opened when asked. */
    @Test
    void anUntrustedCertificateIsSaidAndCanBeSkipped() throws Exception {
        Path ks = dir.resolve("self-signed.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "keytool.exe" : "keytool")
                .toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=logsift-test", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", "secret", "-keypass", "secret").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(ks)) {
            store.load(in, "secret".toCharArray());
        }
        javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "secret".toCharArray());
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        com.sun.net.httpserver.HttpsServer https = com.sun.net.httpserver.HttpsServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(ctx));
        https.createContext("/app.log", x -> send(x, 200, "over https\n", null));
        https.start();
        try {
            java.net.URI u = UrlFetch.parse("https://127.0.0.1:" + https.getAddress().getPort() + "/app.log");
            UrlFetch.UntrustedCertificate e = assertThrows(UrlFetch.UntrustedCertificate.class,
                    () -> UrlFetch.download(u, null, dir, null, () -> false));
            assertEquals("127.0.0.1", e.host());
            Path f = UrlFetch.download(u, null, dir, null, () -> false, true);
            assertEquals("over https\n", java.nio.file.Files.readString(f));
            // a trust store with nothing in it (as Windows' personal one often is) says "not trusted"
            java.security.KeyStore empty = java.security.KeyStore.getInstance("PKCS12");
            empty.load(null, null);
            java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) store.getCertificate("s");
            Tls.AnyOf any = new Tls.AnyOf(List.of(Tls.managerOf(empty)));
            assertThrows(java.security.cert.CertificateException.class,
                    () -> any.checkServerTrusted(new java.security.cert.X509Certificate[] {cert}, "RSA"));
        } finally {
            https.stop(0);
        }
    }
}
