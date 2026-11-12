package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Updating from behind an office's proxy: through it, signed in to it, through its tunnel for HTTPS,
 * past one that looks inside secure connections, and what's said when it blocks, refuses or is gone.
 */
class SelfUpdateProxyTest {
    @TempDir
    Path dir;

    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> types = new ConcurrentHashMap<>();
    private final Map<String, String> redirects = new ConcurrentHashMap<>();
    private TestProxy proxy;
    private ProxySelector wasSelector;
    private String wasBase;
    /** Each time the person was asked to sign in: whether it was "again". */
    private final List<Boolean> asks = new CopyOnWriteArrayList<>();

    private static final String DL = "/bdarwin/logsift/releases/download/";
    private static final String COMMIT = "61a7f1d0123456789abcdef0123456789abcdef0";

    @BeforeEach
    void serve() throws IOException {
        Proxies.init();
        Proxies.forgetForTest();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
        proxy = new TestProxy();
        wasSelector = ProxySelector.getDefault();
        wasBase = System.getProperty("logsift.updateBase");
        base("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void answer(com.sun.net.httpserver.HttpExchange x) throws IOException {
        String p = x.getRequestURI().getPath();
        if (redirects.containsKey(p)) {
            x.getResponseHeaders().add("Location", redirects.get(p));
            x.sendResponseHeaders(302, -1);
        } else if (files.containsKey(p)) {
            byte[] b = files.get(p);
            x.getResponseHeaders().add("Content-Type", types.getOrDefault(p, "application/octet-stream"));
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
        } else {
            x.sendResponseHeaders(404, -1);
        }
        x.close();
    }

    @AfterEach
    void stop() throws IOException {
        Proxies.signIn(null);
        ProxySelector.setDefault(wasSelector);
        server.stop(0);
        proxy.close();
        if (wasBase == null) {
            System.clearProperty("logsift.updateBase");
        } else {
            System.setProperty("logsift.updateBase", wasBase);
        }
    }

    private static void base(String b) {
        System.setProperty("logsift.updateBase", b);
    }

    /** Everything goes through {@code p}, as an office's network settings say. */
    private static void through(Proxy p) {
        ProxySelector.setDefault(always(p));
    }

    private static ProxySelector always(Proxy p) {
        return new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(p);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        };
    }

    /** The person, asked to sign in: answers in turn (null: Cancel). */
    private void person(String... answers) {
        Proxies.signIn((p, again) -> {
            asks.add(again);
            String a = asks.size() <= answers.length ? answers[asks.size() - 1] : null;
            if (a == null) {
                return null;
            }
            String[] up = a.split(":", 2);
            return new PasswordAuthentication(up[0], up[1].toCharArray());
        });
    }

    private SelfUpdate.Install folder() throws IOException {
        Path app = Files.createDirectories(dir.resolve("Logsift"));
        return new SelfUpdate.Install(SelfUpdate.Kind.FOLDER, app, app.resolve("Logsift.exe"), "windows-x64", "zip");
    }

    private static final SelfUpdate.Available V142 = new SelfUpdate.Available("v1.4.2", "1.4.2", "", null);

    /** Release 1.4.2 on the test's GitHub: the latest, with its zip and sums. */
    private byte[] release() throws Exception {
        byte[] zip = "the new Logsift".getBytes(StandardCharsets.UTF_8);
        redirects.put("/bdarwin/logsift/releases/latest", "/bdarwin/logsift/releases/tag/v1.4.2");
        files.put(DL + "v1.4.2/Logsift-1.4.2-windows-x64.zip", zip);
        files.put(DL + "v1.4.2/SHA256SUMS.txt", (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip))
                + "  Logsift-1.4.2-windows-x64.zip\n").getBytes(StandardCharsets.UTF_8));
        files.put(DL + "snapshot/BUILD.txt", ("commit=" + COMMIT + "\ndate=2026-11-07T10:00:00Z\n")
                .getBytes(StandardCharsets.UTF_8));
        return zip;
    }

    private static final SelfUpdate.Build OLD = new SelfUpdate.Build("1.4.1", "release", "aaa");

    // ---------------------------------------------------------------- through the proxy

    @Test
    void anUpdateComesThroughTheOfficeProxy() throws Exception {
        byte[] zip = release();
        through(proxy.proxy());
        assertEquals("v1.4.2", SelfUpdate.check(SelfUpdate.Channel.RELEASES, OLD).orElseThrow().tag());
        Path f = SelfUpdate.download(V142, folder(), dir.resolve("dl"), n -> { }, () -> false);
        assertEquals(new String(zip, StandardCharsets.UTF_8), Files.readString(f));
        String at = "http://127.0.0.1:" + server.getAddress().getPort();
        assertTrue(proxy.seen.contains("GET " + at + "/bdarwin/logsift/releases/latest HTTP/1.1"), "" + proxy.seen);
        assertTrue(proxy.seen.contains("GET " + at + DL + "v1.4.2/Logsift-1.4.2-windows-x64.zip HTTP/1.1"),
                "the download too: " + proxy.seen);
        assertTrue(asks.isEmpty(), "no sign-in asked for");
    }

    @Test
    void aProxyThatWantsASignInAsksThePersonOnce() throws Exception {
        release();
        proxy.wants = "ann:s3cret";
        through(proxy.proxy());
        person("ann:wrong", "ann:s3cret");
        assertEquals("v1.4.2", SelfUpdate.check(SelfUpdate.Channel.RELEASES, OLD).orElseThrow().tag());
        assertEquals(List.of(false, true), asks, "asked, and again when the proxy said no to the first password");
        Path f = SelfUpdate.download(V142, folder(), dir.resolve("dl"), n -> { }, () -> false);
        assertTrue(Files.size(f) > 0);
        assertEquals(List.of(false, true), asks, "not asked again: it's remembered until Logsift quits");
    }

    @Test
    void aCheckNobodyAskedForNeverAsksToSignIn() throws Exception {
        release();
        proxy.wants = "ann:s3cret";
        through(proxy.proxy());
        person("ann:s3cret");
        IOException e = assertThrows(IOException.class, () -> Proxies.quietly(
                () -> SelfUpdate.check(SelfUpdate.Channel.RELEASES, OLD)));
        assertTrue(e.getMessage().startsWith("Your network's proxy (127.0.0.1:" + proxy.port() + ") asks for a sign-in."),
                e.getMessage());
        assertTrue(asks.isEmpty(), "nobody asked: the check at start is quiet");
    }

    @Test
    void cancellingTheSignInSaysWhatToDo() throws Exception {
        release();
        proxy.wants = "ann:s3cret";
        through(proxy.proxy());
        person((String) null);
        IOException e = assertThrows(IOException.class, () -> SelfUpdate.check(SelfUpdate.Channel.RELEASES, OLD));
        assertTrue(e.getMessage().contains("asks for a sign-in. Choose Check for Updates in the Help menu to sign in"),
                e.getMessage());
        assertEquals(List.of(false), asks);
    }

    // ---------------------------------------------------------------- HTTPS: the tunnel, and certificates

    @Test
    void httpsGoesThroughTheProxysTunnelSignedIn() throws Exception {
        // a fresh Logsift, as it starts: Java must be told before its first connection that a proxy
        // may be signed in to for HTTPS, so this runs in a JVM of its own
        HttpsServer https = https();
        try {
            release();
            proxy.wants = "ann:s3cret";
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    "-Dlogsift.updateUrl=none", "-Djava.awt.headless=true",
                    TunnelCheck.class.getName(), "https://127.0.0.1:" + https.getAddress().getPort(),
                    String.valueOf(proxy.port()), "ann:s3cret")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            assertEquals(0, p.waitFor(), out);
            assertTrue(out.endsWith("found " + COMMIT + ", asked 1"), out);
            assertTrue(proxy.seen.contains("CONNECT 127.0.0.1:" + https.getAddress().getPort() + " HTTP/1.1 (no sign-in)")
                    && proxy.seen.contains("CONNECT 127.0.0.1:" + https.getAddress().getPort() + " HTTP/1.1"),
                    "asked to sign in, then let through: " + proxy.seen);
        } finally {
            https.stop(0);
        }
    }

    /** A fresh Logsift checking for a snapshot through a proxy: prints what it found. */
    static final class TunnelCheck {
        public static void main(String[] args) throws Exception {
            Proxies.init(); // as the app does first thing
            System.setProperty("logsift.updateBase", args[0]);
            Proxy p = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1",
                    Integer.parseInt(args[1])));
            through(p);
            int[] asked = {0};
            String[] up = args[2].split(":", 2);
            Proxies.signIn((proxy, again) -> {
                asked[0]++;
                return new PasswordAuthentication(up[0], up[1].toCharArray());
            });
            SelfUpdate.Available a = SelfUpdate.check(SelfUpdate.Channel.SNAPSHOTS,
                    new SelfUpdate.Build("1.4.1", "snapshot", "3c4f2d3")).orElseThrow();
            System.out.println("found " + a.commit() + ", asked " + asked[0]);
        }
    }

    @Test
    void aProxyThatLooksInsideHttpsLetsTheCheckThroughButNotTheDownload() throws Exception {
        // the test's server has a certificate nothing here trusts, as an office proxy that re-signs
        // secure connections does when its certificate isn't on this computer
        HttpsServer https = https();
        try {
            release();
            base("https://127.0.0.1:" + https.getAddress().getPort());
            assertEquals("v1.4.2", SelfUpdate.check(SelfUpdate.Channel.RELEASES, OLD).orElseThrow().tag(),
                    "only a version is read: the check gets through");
            IOException e = assertThrows(IOException.class,
                    () -> SelfUpdate.download(V142, folder(), dir.resolve("dl"), n -> { }, () -> false));
            assertTrue(e.getMessage().startsWith("Your network shows GitHub with a certificate neither Java nor this "
                    + "computer trusts"), e.getMessage());
            assertTrue(e.getMessage().endsWith("https://github.com/bdarwin/logsift/releases/tag/v1.4.2"), e.getMessage());
            assertTrue(!Files.exists(dir.resolve("dl/Logsift-1.4.2-windows-x64.zip")), "nothing downloaded unchecked");
        } finally {
            https.stop(0);
        }
    }

    @Test
    void aProxyThatRefusesGitHubSaysSo() throws Exception {
        proxy.refuseTunnels = 403;
        through(proxy.proxy());
        base("https://127.0.0.1:" + server.getAddress().getPort());
        IOException e = assertThrows(IOException.class, () -> SelfUpdate.check(SelfUpdate.Channel.RELEASES, OLD));
        assertEquals("Your network's proxy (127.0.0.1:" + proxy.port() + ") won't connect to 127.0.0.1 (HTTP/1.1 403 "
                + "Forbidden). Your IT team can allow it; or download Logsift from the releases page in your browser.",
                e.getMessage());
    }

    // ---------------------------------------------------------------- blocked, or gone

    @Test
    void aBlockedDownloadIsSaidNotTakenForAFile() throws Exception {
        release();
        String page = "<html><body>Blocked by your company's policy</body></html>";
        files.put(DL + "v1.4.2/Logsift-1.4.2-windows-x64.zip", page.getBytes(StandardCharsets.UTF_8));
        types.put(DL + "v1.4.2/Logsift-1.4.2-windows-x64.zip", "text/html; charset=utf-8");
        IOException e = assertThrows(IOException.class,
                () -> SelfUpdate.download(V142, folder(), dir.resolve("dl"), n -> { }, () -> false));
        assertTrue(e.getMessage().startsWith("Your network sent a web page instead of Logsift-1.4.2-windows-x64.zip"),
                e.getMessage());
        // and a check isn't taken for "nothing newer"
        files.put(DL + "snapshot/BUILD.txt", page.getBytes(StandardCharsets.UTF_8));
        types.put(DL + "snapshot/BUILD.txt", "text/html");
        e = assertThrows(IOException.class, () -> SelfUpdate.check(SelfUpdate.Channel.SNAPSHOTS,
                new SelfUpdate.Build("1.4.1", "snapshot", "3c4f2d3")));
        assertTrue(e.getMessage().startsWith("Your network sent a web page instead of BUILD.txt"), e.getMessage());
    }

    @Test
    void aProxyThatDoesntAnswerIsNamed() throws Exception {
        int gone;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            gone = s.getLocalPort();
        }
        through(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", gone)));
        IOException e = assertThrows(IOException.class, () -> SelfUpdate.check(SelfUpdate.Channel.RELEASES, OLD));
        assertTrue(e.getMessage().startsWith("No answer from the proxy 127.0.0.1:" + gone), e.getMessage());
    }

    // ---------------------------------------------------------------- the environment, and who's answered

    @Test
    void httpsProxyAndNoProxyAreReadAsCurlReadsThem() {
        Map<String, String> env = Map.of("HTTPS_PROXY", "http://proxy.corp:3128", "no_proxy", ".internal, intranet:8080");
        Proxy p = Proxies.fromEnvironment(env, URI.create("https://github.com/x"));
        assertEquals("proxy.corp:3128", Proxies.name(p));
        assertNull(Proxies.fromEnvironment(env, URI.create("https://wiki.internal/x")), "NO_PROXY: .internal");
        assertNull(Proxies.fromEnvironment(env, URI.create("https://intranet/x")));
        assertNull(Proxies.fromEnvironment(env, URI.create("http://github.com/x")), "no HTTP_PROXY");
        assertNull(Proxies.fromEnvironment(env, URI.create("https://localhost:9/x")), "never for this computer");
        assertEquals("p:80", Proxies.name(Proxies.fromEnvironment(Map.of("https_proxy", "p"), URI.create("https://a/"))));
        assertTrue(Proxies.bypassed("*", "github.com"));
        assertTrue(Proxies.bypassed("github.com", "api.github.com"));
        assertTrue(!Proxies.bypassed("hub.com", "github.com"), "a whole name, not its end");
        // the computer's own proxy first; the environment's only when it has none
        assertEquals("proxy.corp:3128", Proxies.name(new Proxies.OrEnvironment(always(Proxy.NO_PROXY), env)
                .select(URI.create("https://github.com/")).get(0)));
        Proxy system = proxy.proxy();
        assertEquals(system, new Proxies.OrEnvironment(always(system), env).select(URI.create("https://github.com/")).get(0));
    }

    @Test
    void onlyAProxyIsAnsweredNeverAWebsite() throws Exception {
        Authenticator a = new Proxies.Asker(Map.of("HTTPS_PROXY", "http://bob:p%40ss@proxy.corp:3128"));
        person("ann:s3cret");
        URI u = URI.create("https://github.com/");
        assertNull(Authenticator.requestPasswordAuthentication(a, "github.com", null, 443, "https", "realm", "basic",
                u.toURL(), Authenticator.RequestorType.SERVER), "a website's sign-in is never answered");
        PasswordAuthentication env = Authenticator.requestPasswordAuthentication(a, "proxy.corp", null, 3128, "http",
                "office", "basic", u.toURL(), Authenticator.RequestorType.PROXY);
        assertEquals("bob", env.getUserName());
        assertEquals("p@ss", new String(env.getPassword()), "HTTPS_PROXY's own name and password, for its proxy");
        PasswordAuthentication other = Authenticator.requestPasswordAuthentication(a, "other.corp", null, 8080, "http",
                "office", "basic", u.toURL(), Authenticator.RequestorType.PROXY);
        assertEquals("ann", other.getUserName(), "another proxy: the person");
        assertEquals(List.of(false), asks);
    }

    @Test
    void theAppTellsJavaItMaySignInToAProxyForHttps() {
        Proxies.init();
        assertEquals("", System.getProperty("jdk.http.auth.tunneling.disabledSchemes"));
        assertEquals("true", System.getProperty("java.net.useSystemProxies"));
        assertTrue(Authenticator.getDefault() instanceof Proxies.Asker);
    }

    // ---------------------------------------------------------------- an HTTPS server

    /** The test's GitHub over HTTPS, with a certificate made just now that nothing trusts. */
    private HttpsServer https() throws Exception {
        Path ks = dir.resolve("server.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-keyalg", "EC", "-groupname", "secp256r1",
                "-alias", "s", "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "changeit", "-keypass", "changeit")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        HttpsServer s = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(ctx));
        s.createContext("/", this::answer);
        s.start();
        return s;
    }
}
