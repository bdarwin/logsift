package logsift.jvm;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which JVM writes a log: where it runs (this computer, a server over SSH, or a JMX port) and
 * what it runs ({@code orders.jar}, {@code com.acme.App}). Remembered by program, not by
 * process id, so it still finds the JVM after a restart or a new version of the jar.
 *
 * @param host for SSH and JMX: the server (empty on this computer)
 * @param port SSH's or the JMX port
 * @param user SSH's login, or the JMX user ("" for none)
 * @param pid  the process id last seen (preferred while it still runs the same program)
 */
public record JvmLink(Kind kind, String host, int port, String user, String program, long pid) {

    public enum Kind { LOCAL, SSH, JMX }

    public JvmLink {
        host = host == null ? "" : host;
        user = user == null ? "" : user;
        program = program == null ? "" : program;
    }

    public static JvmLink local(JvmProcess p) {
        return new JvmLink(Kind.LOCAL, "", 0, "", p.name(), p.pid());
    }

    public static JvmLink ssh(String host, int port, String user, JvmProcess p) {
        return new JvmLink(Kind.SSH, host, port, user, p.name(), p.pid());
    }

    public static JvmLink jmx(JmxJvms.Address a, JvmProcess p) {
        return new JvmLink(Kind.JMX, a.host(), a.port(), a.user(), p.name(), p.pid());
    }

    /** "orders.jar", "orders.jar on prod-7", "orders.jar on prod-7:9010 (JMX)" */
    public String label() {
        return switch (kind) {
            case LOCAL -> program;
            case SSH -> program + " on " + host;
            case JMX -> program + " on " + (host.startsWith("service:jmx:") ? host : host + ":" + port) + " (JMX)";
        };
    }

    /** Where it is, in words: "this computer", "prod-7", "prod-7:9010 (JMX)". */
    public String where() {
        return switch (kind) {
            case LOCAL -> "this computer";
            case SSH -> host;
            case JMX -> (host.startsWith("service:jmx:") ? host : host + ":" + port) + " (JMX)";
        };
    }

    /** The same program at the same place (the pid may differ). */
    public boolean samePlace(JvmLink o) {
        return o != null && kind == o.kind && host.equalsIgnoreCase(o.host) && (kind == Kind.LOCAL || port == o.port);
    }

    /** The running JVM this link means: the same pid still running the program, else the program. */
    public JvmProcess pick(List<JvmProcess> running) {
        for (JvmProcess p : running) {
            if (p.pid() == pid && sameProgram(program, p.name())) {
                return p;
            }
        }
        for (JvmProcess p : running) {
            if (sameProgram(program, p.name())) {
                return p;
            }
        }
        return null;
    }

    private static final Pattern JAR_VERSION = Pattern.compile("[-_]v?\\d[\\w.+-]*(?=\\.jar$)");

    /**
     * True if two names mean the same program: equal, a main class and its simple name
     * ({@code OrdersApplication}), or a jar at another version ({@code orders-1.4.jar},
     * {@code orders-1.5.jar}).
     */
    public static boolean sameProgram(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        String x = norm(a);
        String y = norm(b);
        return x.equals(y) || x.endsWith("." + y) || y.endsWith("." + x);
    }

    private static String norm(String s) {
        String t = s.trim();
        t = t.substring(Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\')) + 1);
        return JAR_VERSION.matcher(t).replaceFirst("").toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- remembering

    /** One line, for the preferences. */
    public String encode() {
        return String.join("|", kind.name(), enc(host), Integer.toString(port), enc(user), enc(program),
                Long.toString(pid));
    }

    /** Null if {@code s} is not one. */
    public static JvmLink decode(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] f = s.split("\\|", -1);
        if (f.length != 6) {
            return null;
        }
        try {
            return new JvmLink(Kind.valueOf(f[0]), dec(f[1]), Integer.parseInt(f[2]), dec(f[3]), dec(f[4]),
                    Long.parseLong(f[5]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String dec(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
