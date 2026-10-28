package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checking for a new version, also behind a company proxy whose certificate Java doesn't trust. */
class UpdateCheckerTest {
    @TempDir
    Path dir;

    private static final String JSON = "{\"html_url\":\"https:\\/\\/github.com\\/bdarwin\\/logsift\\/releases\\/tag\\/v9.1.0\","
            + "\"tag_name\":\"v9.1.0\"}";

    @Test
    void anUntrustedCertificateDoesntStopTheCheck() throws Exception {
        Path ks = dir.resolve("proxy.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool")
                .toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=company-proxy", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", "secret", "-keypass", "secret").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(ks)) {
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
        https.createContext("/latest", x -> {
            byte[] b = JSON.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        https.start();
        try {
            var r = UpdateChecker.latest("https://127.0.0.1:" + https.getAddress().getPort() + "/latest").orElseThrow();
            assertEquals("9.1.0", r.version());
            assertEquals("https://github.com/bdarwin/logsift/releases/tag/v9.1.0", r.url());
        } finally {
            https.stop(0);
        }
    }

    @Test
    void theDownloadPageIsAlwaysGitHubs() {
        // whatever page the answer names, Logsift opens GitHub's page of that version
        var r = UpdateChecker.parse("{\"html_url\":\"https://evil.example/download\",\"tag_name\":\"v1.4.0\"}")
                .orElseThrow();
        assertEquals("https://github.com/bdarwin/logsift/releases/tag/v1.4.0", r.url());
        assertEquals("https://github.com/bdarwin/logsift/releases/tag/v2.0.0-beta.1",
                UpdateChecker.parse("{\"tag_name\":\"v2.0.0-beta.1\"}").orElseThrow().url());
    }

    @Test
    void onlyAPlainVersionNumberIsTaken() {
        for (String tag : new String[] {"v1.4.0/../../evil", "1.4.0?x=1", "v1.4.0#a", "../x", "", "latest",
                "v1.4.0 or something", "1.2.3-" + "x".repeat(40)}) {
            assertTrue(UpdateChecker.parse("{\"tag_name\":\"" + tag + "\"}").isEmpty(), tag);
        }
        assertTrue(UpdateChecker.parse("{\"html_url\":\"https://github.com/x\"}").isEmpty(), "no tag");
        assertEquals("https://github.com/bdarwin/logsift/releases", UpdateChecker.page("1.0/../../evil"),
                "a stored version that isn't plain opens the list of releases");
    }
}
