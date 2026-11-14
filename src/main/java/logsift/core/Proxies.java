package logsift.core;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An office's proxy, for everything Logsift connects to over HTTP (updates, URLs): the one this
 * computer's network settings name (Windows' and macOS's, a PAC file too), else HTTPS_PROXY and
 * NO_PROXY; or the one chosen in Settings (or none). Signed in to as Windows does (NTLM, Kerberos)
 * or, when it asks for a name and password, with the ones the person types, kept only until
 * Logsift quits.
 */
public final class Proxies {
    private Proxies() {
    }

    /** Asks the person to sign in to a proxy; null when they don't. */
    public interface SignIn {
        /**
         * @param proxy  "proxy.example.com:8080"
         * @param again  the name and password given before didn't work
         */
        PasswordAuthentication ask(String proxy, boolean again);
    }

    private static volatile boolean ready;
    private static volatile SignIn signIn;
    /** Given this session, by proxy, and when: never written anywhere. */
    private static final Map<String, Given> given = new ConcurrentHashMap<>();

    private record Given(PasswordAuthentication auth, long at) {
    }

    /** Asked again this soon after an answer: the proxy said no to it (not just another connection). */
    static long rejectedWithinMs = 5_000;
    private static final ThreadLocal<Boolean> quiet = new ThreadLocal<>();

    // ---------------------------------------------------------------- the setting

    /** How Logsift finds its proxy (Settings → Network). */
    public enum Mode {
        /** This computer's network settings, else HTTPS_PROXY. */
        AUTOMATIC,
        /** The one given. */
        MANUAL,
        /** Never one. */
        NONE
    }

    /**
     * The proxy setting.
     *
     * @param bypass hosts reached directly with a manual proxy ("intranet.example.com, .corp"), as NO_PROXY
     */
    public record Setting(Mode mode, String host, int port, String bypass) {
        public static final Setting AUTOMATIC = new Setting(Mode.AUTOMATIC, "", 0, "");

        public Setting {
            mode = mode == null ? Mode.AUTOMATIC : mode;
            host = host == null ? "" : host.strip();
            bypass = bypass == null ? "" : bypass.strip();
            // a proxy pasted whole ("http://proxy.corp:8080/"): its host, and its port when none is given
            int scheme = host.indexOf("://");
            if (scheme >= 0) {
                host = host.substring(scheme + 3);
            }
            host = host.replaceAll("/.*$", "").replaceAll("^.*@", "");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(.*[^:]):(\\d{1,5})").matcher(host);
            if (m.matches() && (host.startsWith("[") || m.group(1).indexOf(':') < 0)) {
                host = m.group(1);
                if (port <= 0) {
                    port = Integer.parseInt(m.group(2));
                }
            }
        }

        /** What's wrong with it, in words, or null: a manual proxy needs its host and port. */
        public String problem() {
            if (mode != Mode.MANUAL) {
                return null;
            }
            if (host.isEmpty()) {
                return "Give the proxy's host name, like proxy.example.com.";
            }
            if (!host.matches("[A-Za-z0-9._:\\[\\]-]+")) {
                return "Give the proxy's host name alone, like proxy.example.com (the port goes beside it).";
            }
            if (port < 1 || port > 65535) {
                return "Give the proxy's port, like 8080.";
            }
            return null;
        }
    }

    private static volatile Setting setting;

    private static java.util.prefs.Preferences prefs() {
        return java.util.prefs.Preferences.userRoot().node(System.getProperty("logsift.prefs", "/logsift/ui"));
    }

    /** The proxy setting (read once, when first needed: a beta's own settings are chosen by then). */
    public static Setting setting() {
        Setting s = setting;
        if (s == null) {
            java.util.prefs.Preferences p = prefs();
            Mode m;
            try {
                m = Mode.valueOf(p.get("proxy.mode", Mode.AUTOMATIC.name()));
            } catch (IllegalArgumentException e) {
                m = Mode.AUTOMATIC;
            }
            s = new Setting(m, p.get("proxy.host", ""), p.getInt("proxy.port", 0), p.get("proxy.bypass", ""));
            if (s.problem() != null) {
                s = new Setting(Mode.AUTOMATIC, s.host(), s.port(), s.bypass()); // half-typed: as if not chosen
            }
            setting = s;
        }
        return s;
    }

    /** Uses {@code s} from now on, and remembers it. A manual proxy without its host or port isn't used. */
    public static void choose(Setting s) {
        java.util.prefs.Preferences p = prefs();
        p.put("proxy.mode", s.mode().name());
        p.put("proxy.host", s.host());
        p.putInt("proxy.port", s.port());
        p.put("proxy.bypass", s.bypass());
        setting = s.problem() == null ? s : new Setting(Mode.AUTOMATIC, s.host(), s.port(), s.bypass());
    }

    /** Whether {@code u}'s host is never proxied (this computer itself). Tests turn it off. */
    static boolean loopbackDirect = true;

    /** Before anything connects (the app does it first thing). */
    public static synchronized void init() {
        if (ready) {
            return;
        }
        ready = true;
        unlessSet("java.net.useSystemProxies", "true");
        // Java won't send a name and password to a proxy for HTTPS unless told it may: offices' do ask
        unlessSet("jdk.http.auth.tunneling.disabledSchemes", "");
        unlessSet("jdk.http.auth.proxying.disabledSchemes", "");
        ProxySelector system = ProxySelector.getDefault();
        if (system != null) {
            ProxySelector.setDefault(new OrEnvironment(system, System.getenv()));
        }
        Authenticator.setDefault(new Asker(System.getenv()));
    }

    private static void unlessSet(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    /** Who asks the person (the window); null: nobody. */
    public static void signIn(SignIn s) {
        signIn = s;
    }

    /** Runs {@code r} without asking anyone to sign in (a check nobody asked for). */
    public static <T> T quietly(IoCall<T> r) throws IOException {
        Boolean was = quiet.get();
        quiet.set(Boolean.TRUE);
        try {
            return r.call();
        } finally {
            if (was == null) {
                quiet.remove();
            } else {
                quiet.set(was);
            }
        }
    }

    public interface IoCall<T> {
        T call() throws IOException;
    }

    /** How {@code u} is reached: "through the proxy host:port", or "directly". */
    public static String how(URI u) {
        Proxy p = proxyFor(u);
        return p == null ? "directly" : "through the proxy " + name(p);
    }

    /** The proxy {@code u} goes through, or null. */
    public static Proxy proxyFor(URI u) {
        try {
            ProxySelector s = ProxySelector.getDefault();
            if (s == null) {
                return null;
            }
            for (Proxy p : s.select(u)) {
                if (p.type() != Proxy.Type.DIRECT) {
                    return p;
                }
            }
        } catch (RuntimeException e) {
            // none known
        }
        return null;
    }

    static String name(Proxy p) {
        SocketAddress a = p.address();
        if (a instanceof InetSocketAddress i) {
            return i.getHostString() + ":" + i.getPort();
        }
        return String.valueOf(a);
    }

    // ---------------------------------------------------------------- HTTPS_PROXY and NO_PROXY

    /**
     * The proxy the environment names for {@code u} (HTTPS_PROXY, HTTP_PROXY, as curl reads them),
     * or null: none, or NO_PROXY says not for this host.
     */
    static Proxy fromEnvironment(Map<String, String> env, URI u) {
        URI p = environmentProxy(env, u);
        if (p == null) {
            return null;
        }
        int port = p.getPort() > 0 ? p.getPort() : "https".equalsIgnoreCase(p.getScheme()) ? 443 : 80;
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(p.getHost(), port));
    }

    private static URI environmentProxy(Map<String, String> env, URI u) {
        if (u == null || u.getHost() == null) {
            return null;
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String v = scheme.equals("https") ? env(env, "https_proxy") : scheme.equals("http") ? env(env, "http_proxy") : null;
        if (v == null || v.isBlank() || bypassed(env(env, "no_proxy"), u.getHost())) {
            return null;
        }
        try {
            URI p = URI.create(v.strip().contains("://") ? v.strip() : "http://" + v.strip());
            return p.getHost() == null ? null : p;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The lower-case name, else the upper-case one (curl's order). */
    private static String env(Map<String, String> env, String lower) {
        String v = env.get(lower);
        return v != null && !v.isBlank() ? v : env.get(lower.toUpperCase(Locale.ROOT));
    }

    /** Whether NO_PROXY ("*", "example.com", ".example.com", "10.0.0.1", with or without a port) names {@code host}. */
    static boolean bypassed(String noProxy, String host) {
        return loopback(host) || listed(noProxy, host);
    }

    /** This computer itself. */
    static boolean loopback(String host) {
        String h = host.toLowerCase(Locale.ROOT).replaceAll("^\\[|\\]$", "");
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1");
    }

    /** Whether {@code list} (as NO_PROXY) names {@code host}. */
    static boolean listed(String list, String host) {
        if (list == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT).replaceAll("^\\[|\\]$", "");
        for (String raw : list.split("[,;\\s]+")) {
            String e = raw.strip().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) {
                continue;
            }
            if (e.equals("*")) {
                return true;
            }
            e = e.replaceAll(":\\d+$", "").replaceAll("^\\*", "");
            String bare = e.startsWith(".") ? e.substring(1) : e;
            if (h.equals(bare) || h.endsWith("." + bare)) {
                return true;
            }
        }
        return false;
    }

    /** The computer's proxy; when it has none, the environment's. */
    static final class OrEnvironment extends ProxySelector {
        private final ProxySelector system;
        private final Map<String, String> env;

        OrEnvironment(ProxySelector system, Map<String, String> env) {
            this.system = system;
            this.env = env;
        }

        @Override
        public List<Proxy> select(URI uri) {
            Setting s = setting();
            if (s.mode() != Mode.AUTOMATIC) {
                String host = uri == null || uri.getHost() == null ? "" : uri.getHost();
                String scheme = uri == null || uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                // a web proxy is for the web: SSH and JMX (Java asks for "socket://") go direct
                boolean web = scheme.equals("http") || scheme.equals("https");
                if (s.mode() == Mode.NONE || !web || (loopbackDirect && loopback(host)) || listed(s.bypass(), host)) {
                    return List.of(Proxy.NO_PROXY);
                }
                return List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(s.host(), s.port())));
            }
            List<Proxy> l = system.select(uri);
            boolean direct = l == null || l.isEmpty() || l.stream().allMatch(p -> p.type() == Proxy.Type.DIRECT);
            if (direct) {
                Proxy p = fromEnvironment(env, uri);
                if (p != null) {
                    return List.of(p);
                }
            }
            return l == null || l.isEmpty() ? List.of(Proxy.NO_PROXY) : l;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            system.connectFailed(uri, sa, ioe);
        }
    }

    // ---------------------------------------------------------------- signing in

    /** Answers proxies only (never a website): the environment's name and password, else the person's. */
    static final class Asker extends Authenticator {
        private final Map<String, String> env;

        Asker(Map<String, String> env) {
            this.env = env;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.PROXY) {
                return null;
            }
            String proxy = getRequestingHost() + ":" + getRequestingPort();
            Given before = given.get(proxy);
            long now = System.currentTimeMillis();
            if (before != null && now - before.at() > rejectedWithinMs) {
                given.put(proxy, new Given(before.auth(), now)); // another connection: the same answer
                return before.auth();
            }
            if (before == null) {
                PasswordAuthentication e = fromUrl(proxy);
                if (e != null) {
                    given.put(proxy, new Given(e, now));
                    return e;
                }
            }
            SignIn s = signIn;
            if (s == null || Boolean.TRUE.equals(quiet.get())) {
                return null;
            }
            PasswordAuthentication a = s.ask(proxy, before != null);
            if (a == null) {
                given.remove(proxy);
                return null;
            }
            given.put(proxy, new Given(a, System.currentTimeMillis()));
            return a;
        }

        /** A name and password in HTTPS_PROXY ("http://name:password@proxy:8080"), for that proxy. */
        private PasswordAuthentication fromUrl(String proxy) {
            for (String k : List.of("https_proxy", "http_proxy")) {
                String v = env(env, k);
                if (v == null || v.isBlank()) {
                    continue;
                }
                try {
                    URI p = URI.create(v.strip().contains("://") ? v.strip() : "http://" + v.strip());
                    String info = p.getRawUserInfo();
                    int port = p.getPort() > 0 ? p.getPort() : 80;
                    if (info != null && (p.getHost() + ":" + port).equalsIgnoreCase(proxy)) {
                        int c = info.indexOf(':');
                        String user = URLDecoder.decode(c < 0 ? info : info.substring(0, c), StandardCharsets.UTF_8);
                        String pw = c < 0 ? "" : URLDecoder.decode(info.substring(c + 1), StandardCharsets.UTF_8);
                        return new PasswordAuthentication(user, pw.toCharArray());
                    }
                } catch (IllegalArgumentException e) {
                    // not a URL
                }
            }
            return null;
        }
    }

    /** For tests: read the setting again from the preferences. */
    static void forgetSettingForTest() {
        setting = null;
    }

    /** For tests: forget the names and passwords given. */
    static void forgetForTest() {
        given.clear();
    }

    // ---------------------------------------------------------------- what went wrong, in words

    /** Where the proxy is chosen. */
    public static final String SETTING = "Settings → Network";

    private static boolean manual() {
        return setting().mode() == Mode.MANUAL;
    }

    private static String noProxyHint() {
        return setting().mode() == Mode.NONE
                ? ", and Logsift is set to use none: choose one in " + SETTING + "."
                : " this computer isn't set to use: give it in " + SETTING + ".";
    }

    /** The proxy wants a sign-in, and none was given (or the person didn't). */
    public static IOException signInNeeded(Proxy p, String what, IOException cause) {
        return new IOException("Your network's proxy" + (p == null ? "" : " (" + name(p) + ")")
                + " asks for a sign-in. Choose Check for Updates in the Help menu to sign in, or download " + what
                + " from the releases page in your browser.", cause);
    }

    /**
     * {@code e}, said plainly when an office network is why: a proxy that wants a sign-in, refuses,
     * or doesn't answer; a computer that can't find {@code u}'s host. Otherwise {@code e} itself.
     */
    public static IOException explain(IOException e, URI u, String what) {
        String m = String.valueOf(e.getMessage());
        String host = u.getHost();
        Proxy p = proxyFor(u);
        if (m.contains("Unable to tunnel through proxy")) {
            if (m.matches("(?s).*Proxy returns \"HTTP/\\S+ 407.*")) {
                return signInNeeded(p, what, e);
            }
            return new IOException("Your network's proxy" + (p == null ? "" : " (" + name(p) + ")")
                    + " won't connect to " + host + " (" + m.replaceAll("(?s).*Proxy returns ", "").replace("\"", "")
                    + "). Your IT team can allow it; or download " + what + " from the releases page in your browser.", e);
        }
        if (e instanceof java.net.UnknownHostException) {
            if (p != null) {
                return new IOException("The proxy " + name(p) + (manual() ? " set in Logsift's Settings"
                        : " this computer is set to use") + " can't be found. Check its name in " + SETTING
                        + (manual() ? "." : ", or this computer's network settings."), e);
            }
            return new IOException("Can't find " + host + ". This computer may be offline, or reach the internet only "
                    + "through a proxy" + noProxyHint(), e);
        }
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.NoRouteToHostException) {
            if (p != null) {
                return new IOException("No answer from the proxy " + name(p) + " (" + m + "). Check it in " + SETTING
                        + ".", e);
            }
            return new IOException("No answer from " + host + " (" + m + "). Your office may reach the internet "
                    + "only through a proxy" + noProxyHint(), e);
        }
        return e;
    }
}
