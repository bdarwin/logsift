package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The proxy chosen in Settings → Network (automatic, manual, none), and its Test Connection. */
class ProxySettingTest {
    private HttpServer github;
    private TestProxy proxy;
    private ProxySelector wasSelector;
    private String wasBase;
    private String at;
    /** What "this computer's network settings" say: tests set it. */
    private volatile Proxy system = Proxy.NO_PROXY;

    @BeforeEach
    void serve() throws IOException {
        Proxies.init();
        Proxies.forgetForTest();
        github = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        github.createContext("/", x -> {
            if (x.getRequestURI().getPath().equals("/bdarwin/logsift/releases/latest")) {
                x.getResponseHeaders().add("Location", "/bdarwin/logsift/releases/tag/v1.4.2");
                x.sendResponseHeaders(302, -1);
            } else {
                x.sendResponseHeaders(404, -1);
            }
            x.close();
        });
        github.start();
        proxy = new TestProxy();
        wasSelector = ProxySelector.getDefault();
        wasBase = System.getProperty("logsift.updateBase");
        at = "127.0.0.1:" + github.getAddress().getPort();
        System.setProperty("logsift.updateBase", "http://" + at);
        ProxySelector computer = new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(system);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            }
        };
        ProxySelector.setDefault(new Proxies.OrEnvironment(computer, Map.of()));
        Proxies.loopbackDirect = false; // the test's own "GitHub" is on this computer
    }

    @AfterEach
    void stop() throws IOException {
        Proxies.loopbackDirect = true;
        Proxies.choose(Proxies.Setting.AUTOMATIC);
        Proxies.signIn(null);
        ProxySelector.setDefault(wasSelector);
        github.stop(0);
        proxy.close();
        if (wasBase == null) {
            System.clearProperty("logsift.updateBase");
        } else {
            System.setProperty("logsift.updateBase", wasBase);
        }
    }

    private Proxies.Setting manual(String bypass) {
        return new Proxies.Setting(Proxies.Mode.MANUAL, "127.0.0.1", proxy.port(), bypass);
    }

    private long latestThroughProxy() {
        return proxy.seen.stream().filter(s -> s.equals("GET http://" + at + "/bdarwin/logsift/releases/latest HTTP/1.1"))
                .count();
    }

    @Test
    void aManualProxyIsUsedAndTheHostsItsNotForGoDirect() throws Exception {
        Proxies.choose(manual(""));
        SelfUpdate.Reach r = SelfUpdate.testConnection();
        assertTrue(r.ok(), r.text());
        assertEquals("Reached " + at + " through the proxy 127.0.0.1:" + proxy.port()
                + ". Updates can be checked and downloaded.", r.text());
        assertEquals(1, latestThroughProxy(), "" + proxy.seen);

        Proxies.choose(manual("intranet.example.com, 127.0.0.1"));
        r = SelfUpdate.testConnection();
        assertEquals("Reached " + at + " directly. Updates can be checked and downloaded.", r.text());
        assertEquals(1, latestThroughProxy(), "a host it's not for goes direct");
        assertEquals(Proxy.NO_PROXY, ProxySelector.getDefault().select(URI.create("https://wiki.intranet.example.com/x"))
                .get(0), "and its subdomains");
        assertEquals(Proxy.Type.HTTP, ProxySelector.getDefault().select(URI.create("https://github.com/")).get(0).type());
        assertEquals(Proxy.NO_PROXY, ProxySelector.getDefault().select(URI.create("socket://prod-7:22")).get(0),
                "SSH and JMX connections never go through a web proxy");
    }

    @Test
    void noneGoesDirectEvenWhenTheComputerNamesAProxy() throws Exception {
        system = proxy.proxy();
        assertTrue(SelfUpdate.testConnection().text().contains("through the proxy 127.0.0.1:" + proxy.port()),
                "automatic: the computer's");
        Proxies.choose(new Proxies.Setting(Proxies.Mode.NONE, "", 0, ""));
        SelfUpdate.Reach r = SelfUpdate.testConnection();
        assertEquals("Reached " + at + " directly. Updates can be checked and downloaded.", r.text());
        assertEquals(1, latestThroughProxy());
    }

    @Test
    void aHalfTypedManualProxyIsntUsedButIsKept() {
        assertEquals("Give the proxy's host name, like proxy.example.com.",
                new Proxies.Setting(Proxies.Mode.MANUAL, " ", 8080, "").problem());
        assertEquals("Give the proxy's port, like 8080.",
                new Proxies.Setting(Proxies.Mode.MANUAL, "proxy.corp", 0, "").problem());
        assertEquals("Give the proxy's host name alone, like proxy.example.com (the port goes beside it).",
                new Proxies.Setting(Proxies.Mode.MANUAL, "proxy corp", 8080, "").problem());
        assertNull(new Proxies.Setting(Proxies.Mode.MANUAL, "proxy.corp", 8080, "").problem());
        // pasted whole, as an address: its host, and its port
        assertEquals(new Proxies.Setting(Proxies.Mode.MANUAL, "proxy.corp", 8080, ""),
                new Proxies.Setting(Proxies.Mode.MANUAL, "http://proxy.corp:8080/", 0, ""));
        assertEquals(new Proxies.Setting(Proxies.Mode.MANUAL, "proxy.corp", 3128, ""),
                new Proxies.Setting(Proxies.Mode.MANUAL, "http://ann@proxy.corp:8080", 3128, ""), "a port given wins");
        assertEquals("[::1]", new Proxies.Setting(Proxies.Mode.MANUAL, "[::1]:8080", 0, "").host());
        assertEquals("fe80::1", new Proxies.Setting(Proxies.Mode.MANUAL, "fe80::1", 8080, "").host(), "IPv6 left whole");
        Proxies.choose(new Proxies.Setting(Proxies.Mode.MANUAL, "proxy.corp", 0, ""));
        assertEquals(Proxies.Mode.AUTOMATIC, Proxies.setting().mode(), "not usable yet: as if not chosen");
        Proxies.forgetSettingForTest();
        assertEquals(Proxies.Mode.AUTOMATIC, Proxies.setting().mode(), "after a restart too");
        assertEquals("proxy.corp", Proxies.setting().host(), "what was typed is kept");
    }

    @Test
    void theSettingIsRememberedAcrossRestarts() {
        Proxies.choose(new Proxies.Setting(Proxies.Mode.MANUAL, "proxy.corp", 3128, ".internal"));
        Proxies.forgetSettingForTest();
        assertEquals(new Proxies.Setting(Proxies.Mode.MANUAL, "proxy.corp", 3128, ".internal"), Proxies.setting());
        Proxies.loopbackDirect = true;
        assertEquals(Proxy.NO_PROXY, ProxySelector.getDefault().select(URI.create("http://localhost:8080/")).get(0),
                "this computer itself is never proxied");
    }

    @Test
    void testConnectionSaysWhatsWrongInWords() throws Exception {
        int gone;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            gone = s.getLocalPort();
        }
        Proxies.choose(new Proxies.Setting(Proxies.Mode.MANUAL, "127.0.0.1", gone, ""));
        SelfUpdate.Reach r = SelfUpdate.testConnection();
        assertFalse(r.ok());
        assertTrue(r.text().startsWith("No answer from the proxy 127.0.0.1:" + gone + " ("), r.text());
        assertTrue(r.text().endsWith("Check it in Settings → Network."), r.text());

        Proxies.choose(new Proxies.Setting(Proxies.Mode.MANUAL, "no-such-proxy.invalid", 8080, ""));
        r = SelfUpdate.testConnection();
        assertFalse(r.ok());
        assertTrue(r.text().startsWith("The proxy no-such-proxy.invalid:8080 set in Logsift's Settings can't be found."),
                r.text());

        // a proxy wanting a sign-in: the person is asked (a test is asked for), and it works
        Proxies.choose(manual(""));
        proxy.wants = "ann:s3cret";
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        Proxies.signIn((p, again) -> {
            asked.add(p);
            return new PasswordAuthentication("ann", "s3cret".toCharArray());
        });
        r = SelfUpdate.testConnection();
        assertTrue(r.ok(), r.text());
        assertEquals(List.of("127.0.0.1:" + proxy.port()), asked);
    }

    @Test
    void testConnectionWhenUpdatesAreOff() {
        System.clearProperty("logsift.updateBase");
        String was = System.getProperty("logsift.updateUrl");
        System.setProperty("logsift.updateUrl", "none");
        try {
            SelfUpdate.Reach r = SelfUpdate.testConnection();
            assertFalse(r.ok());
            assertEquals("This copy of Logsift doesn't connect to GitHub (update checks are turned off).", r.text());
        } finally {
            if (was == null) {
                System.clearProperty("logsift.updateUrl");
            } else {
                System.setProperty("logsift.updateUrl", was);
            }
        }
    }
}
