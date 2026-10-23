package logsift.jvm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the JVM that writes a log: the one that has the file open, the one it was linked to
 * before, or one the log names itself (Spring Boot's startup line says the program, host and
 * process id; its default layout has the process id on every line). For a log on a shared
 * drive, also says which server the drive is on.
 */
public final class LogOrigin {
    private LogOrigin() {
    }

    /**
     * What a log says about the JVM that writes it. Empty strings and -1 where it doesn't say.
     *
     * @param program a jar ({@code orders.jar}) or a main class's simple name
     */
    public record Hint(String program, String host, long pid, String user) {
        public static final Hint NONE = new Hint("", "", -1, "");

        public boolean isEmpty() {
            return program.isEmpty() && host.isEmpty() && pid < 0;
        }

        /** "orders.jar on prod-7 (PID 1234)" */
        public String label() {
            StringBuilder sb = new StringBuilder(program.isEmpty() ? "a Java program" : program);
            if (!host.isEmpty()) {
                sb.append(" on ").append(host);
            }
            if (pid >= 0) {
                sb.append(" (PID ").append(pid).append(')');
            }
            return sb.toString();
        }
    }

    // Spring Boot 1-3: "Starting OrdersApplication v1.4 using Java 21 on prod-7 with PID 1234
    // (/opt/orders/orders-1.4.jar started by ops in /opt/orders)"
    private static final Pattern STARTING = Pattern.compile(
            "Starting (?<app>[\\w$.-]+)(?: v[\\w.+-]+)?(?: using Java [\\w.+-]+)?(?: on (?<host>[\\w.-]+))?"
                    + " with PID (?<pid>\\d+)(?: \\((?<path>[^()]*?)(?: started by (?<user>\\S+) in [^()]*)?\\))?");
    // Spring Boot's default layout: "2026-10-22T10:00:00.000Z  INFO 1234 --- [main] ..."
    private static final Pattern PID_COLUMN = Pattern.compile(
            "^\\S+(?: \\S+)?\\s+(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\s+(?<pid>\\d+) --- \\[");

    /** What these lines (the start and end of a log) say; the last startup line wins (after restarts). */
    public static Hint scan(Collection<String> lines) {
        Hint found = Hint.NONE;
        long pidColumn = -1;
        for (String l : lines) {
            if (l == null) {
                continue;
            }
            if (l.contains("with PID ")) {
                Matcher m = STARTING.matcher(l);
                if (m.find()) {
                    String path = m.group("path") == null ? "" : m.group("path").trim();
                    String program = path.toLowerCase(Locale.ROOT).endsWith(".jar")
                            ? path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
                            : m.group("app");
                    found = new Hint(program, nz(m.group("host")), Long.parseLong(m.group("pid")), nz(m.group("user")));
                    pidColumn = -1;
                    continue;
                }
            }
            if (l.contains(" --- [")) {
                Matcher m = PID_COLUMN.matcher(l);
                if (m.find()) {
                    pidColumn = Long.parseLong(m.group("pid"));
                }
            }
        }
        if (pidColumn >= 0 && pidColumn != found.pid()) {
            // the newest lines came from this process (restarted since the startup line read)
            found = new Hint(found.program(), found.host(), pidColumn, found.user());
        }
        return found;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Reads the first and last lines of a file and {@link #scan}s them. */
    public static Hint scan(Path file, java.nio.charset.Charset cs) {
        try (var ch = Files.newByteChannel(file)) {
            long size = ch.size();
            List<String> lines = new ArrayList<>();
            byte[] head = new byte[(int) Math.min(size, 256 * 1024)];
            ch.read(java.nio.ByteBuffer.wrap(head));
            lines.addAll(List.of(new String(head, cs).split("\r?\n")));
            if (size > head.length) {
                long from = Math.max(head.length, size - 64 * 1024);
                byte[] tail = new byte[(int) (size - from)];
                ch.position(from);
                ch.read(java.nio.ByteBuffer.wrap(tail));
                String[] t = new String(tail, cs).split("\r?\n");
                lines.addAll(List.of(t).subList(Math.min(1, t.length), t.length)); // the first is cut
            }
            return scan(lines);
        } catch (IOException | RuntimeException e) {
            return Hint.NONE;
        }
    }

    // ---------------------------------------------------------------- shared drives

    private static final Pattern UNC = Pattern.compile("^(?:\\\\\\\\|//)([^\\\\/]+)[\\\\/]");

    /** The server of a UNC path ({@code \\files01\logs\app.log} → {@code files01}), or null. */
    public static String uncHost(String path) {
        Matcher m = UNC.matcher(path);
        return m.find() ? m.group(1) : null;
    }

    // "//ops@files01/logs on /Volumes/logs (smbfs, …)", "files01:/export/logs on /mnt/logs type nfs (…)"
    private static final Pattern MOUNT = Pattern.compile("^(?<src>\\S+) on (?<dir>.+?)(?: type (?<t1>\\w+))? \\((?<t2>\\w+)?");

    /**
     * The file server a path is on, from {@code mount}'s output (SMB, NFS and the like); null if
     * the path is on a local disk.
     */
    public static String mountHost(String mountOutput, String path) {
        String best = null;
        int bestLen = -1;
        for (String l : mountOutput.split("\n")) {
            Matcher m = MOUNT.matcher(l.trim());
            if (!m.find()) {
                continue;
            }
            String dir = m.group("dir");
            String type = (m.group("t1") != null ? m.group("t1") : nz(m.group("t2"))).toLowerCase(Locale.ROOT);
            if (!(path.equals(dir) || path.startsWith(dir.endsWith("/") ? dir : dir + "/")) || dir.length() <= bestLen) {
                continue;
            }
            String src = m.group("src");
            String host = null;
            if (src.startsWith("//")) {                       // smb: //user@host/share
                String h = src.substring(2);
                h = h.substring(h.indexOf('@') + 1);
                int slash = h.indexOf('/');
                host = slash < 0 ? h : h.substring(0, slash);
            } else if (src.matches("[\\w.-]+:/.*") && !type.isEmpty() && type.startsWith("nfs")) {
                host = src.substring(0, src.indexOf(':'));     // nfs: host:/export
            } else if (type.matches("smbfs|cifs|smb3|afpfs|webdav|nfs\\d?|fuse\\.sshfs")) {
                int at = src.indexOf('@');
                int colon = src.indexOf(':', at + 1);
                host = colon > 0 ? src.substring(at + 1, colon) : null;
            }
            if (host != null && !host.isEmpty()) {
                best = host.contains(";") ? host.substring(host.indexOf(';') + 1) : host;
                bestLen = dir.length();
            } else {
                best = null; // a closer local mount
                bestLen = dir.length();
            }
        }
        return best;
    }

    /** The file server of a Windows drive, from {@code net use}'s output ({@code Z:  \\files01\logs}). */
    public static String netUseHost(String netUseOutput, char drive) {
        Pattern p = Pattern.compile("(?i)\\s" + drive + ":\\s+(\\\\\\\\[^\\s\\\\]+)");
        for (String l : netUseOutput.split("\r?\n")) {
            Matcher m = p.matcher(" " + l);
            if (m.find()) {
                return uncHost(m.group(1) + "\\");
            }
        }
        return null;
    }

    /** The file server a log is on (a UNC path, a mapped drive, or a network mount), or null. */
    public static String fileServer(Path file) {
        String s = file.toAbsolutePath().toString();
        String unc = uncHost(s);
        if (unc != null) {
            return unc;
        }
        try {
            if (LocalJvms.isWindows()) {
                if (s.length() > 1 && s.charAt(1) == ':') {
                    return netUseHost(LocalJvms.run(List.of("net", "use"), 10), s.charAt(0));
                }
                return null;
            }
            return mountHost(LocalJvms.run(List.of("mount"), 10), s);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- who has it open

    /** JVMs on this computer (of {@code pids}) that have {@code file} open. */
    public static Set<Long> openedBy(Path file, Collection<Long> pids) {
        Set<Long> out = new LinkedHashSet<>();
        if (pids.isEmpty()) {
            return out;
        }
        Path real;
        try {
            real = file.toRealPath();
        } catch (IOException e) {
            return out;
        }
        Path proc = Path.of("/proc");
        if (Files.isDirectory(proc.resolve("self/fd"))) {
            for (long pid : pids) {
                try (var fds = Files.newDirectoryStream(proc.resolve(pid + "/fd"))) {
                    for (Path fd : fds) {
                        try {
                            if (Files.readSymbolicLink(fd).equals(real)) {
                                out.add(pid);
                                break;
                            }
                        } catch (IOException | UnsupportedOperationException e) {
                            // closed meanwhile
                        }
                    }
                } catch (IOException e) {
                    // another user's process
                }
            }
            return out;
        }
        if (!LocalJvms.isWindows()) {
            try {
                // macOS and the BSDs: lsof answers for our own processes
                StringBuilder only = new StringBuilder();
                pids.forEach(p -> only.append(only.length() == 0 ? "" : ",").append(p));
                // only these processes (and no others) are looked at
                List<String> cmd = new ArrayList<>(List.of("lsof", "-t", "-w", "-a", "-p", only.toString(), "--",
                        real.toString()));
                Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
                String text;
                try (var in = p.getInputStream()) {
                    text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                for (String l : text.split("\\s+")) {
                    try {
                        long pid = Long.parseLong(l.trim());
                        if (pids.contains(pid)) {
                            out.add(pid);
                        }
                    } catch (NumberFormatException e) {
                        // nothing
                    }
                }
            } catch (IOException e) {
                // no lsof
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return out;
    }

    /** The shell command that prints which of {@code pids} have {@code path} open on a Linux server. */
    static String openedByCommand(String path, Collection<Long> pids) {
        StringBuilder sb = new StringBuilder("F=$(readlink -f -- ").append(SshJvms.q(path))
                .append(" 2>/dev/null); [ -n \"$F\" ] || exit 0; for p in");
        pids.forEach(p -> sb.append(' ').append(p));
        sb.append("; do for f in /proc/$p/fd/*; do if [ \"$(readlink \"$f\" 2>/dev/null)\" = \"$F\" ]; then echo $p;"
                + " break; fi; done; done; true");
        return sb.toString();
    }

    /** JVMs on a server (of {@code pids}) that have {@code path} open (those its login may look at). */
    public static Set<Long> openedBy(SshJvms ssh, String path, Collection<Long> pids) {
        Set<Long> out = new LinkedHashSet<>();
        if (pids.isEmpty()) {
            return out;
        }
        try {
            for (String l : ssh.exec(openedByCommand(path, pids), null, 30).out().split("\n")) {
                try {
                    out.add(Long.parseLong(l.trim()));
                } catch (NumberFormatException e) {
                    // nothing
                }
            }
        } catch (IOException e) {
            // can't tell
        }
        return out;
    }

    // ---------------------------------------------------------------- choosing

    /** The JVM found, and why it is the one. */
    public record Match(JvmProcess jvm, String why) {
    }

    /**
     * Picks the JVM of a log among those running where it is: the one that has the file open,
     * the program it was linked to before, the process id or program the log names, or the
     * one whose command line names the log's folder. Null if none fits.
     *
     * @param here     the host name of where {@code running} are (a log naming another host
     *                 isn't matched by process id)
     * @param location the log's path there (for the command line), or null
     */
    public static Match choose(List<JvmProcess> running, Set<Long> openedBy, JvmLink remembered, Hint hint,
                               String here, String location) {
        List<JvmProcess> holders = running.stream().filter(p -> openedBy.contains(p.pid())).toList();
        if (!holders.isEmpty()) {
            JvmProcess p = remembered != null ? remembered.pick(holders) : null;
            return new Match(p != null ? p : holders.get(0), "has this log open");
        }
        if (remembered != null) {
            JvmProcess p = remembered.pick(running);
            if (p != null) {
                return new Match(p, "linked before");
            }
        }
        if (!hint.host().isEmpty() && !sameHost(hint.host(), here)) {
            return null; // the log says it was written elsewhere
        }
        if (hint.pid() >= 0) {
            for (JvmProcess p : running) {
                if (p.pid() == hint.pid() && (hint.program().isEmpty() || JvmLink.sameProgram(hint.program(), p.name()))) {
                    return new Match(p, "its process id is in the log");
                }
            }
        }
        if (!hint.program().isEmpty()) {
            List<JvmProcess> same = running.stream().filter(p -> JvmLink.sameProgram(hint.program(), p.name())).toList();
            if (same.size() == 1) {
                return new Match(same.get(0), "the log names " + hint.program());
            }
        }
        if (location != null && !location.isBlank()) {
            String dir = parent(location);
            List<JvmProcess> naming = running.stream().filter(p -> p.command().contains(location)
                    || dir != null && dir.length() > 1 && mentions(p.command(), dir)).toList();
            if (naming.size() == 1) {
                return new Match(naming.get(0), "its command line names this log's folder");
            }
        }
        return null;
    }

    private static boolean mentions(String command, String dir) {
        int i = command.indexOf(dir);
        while (i >= 0) {
            int end = i + dir.length();
            if (end == command.length() || "/\\ \"'".indexOf(command.charAt(end)) >= 0) {
                return true;
            }
            i = command.indexOf(dir, i + 1);
        }
        return false;
    }

    private static String parent(String path) {
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i <= 0 ? null : path.substring(0, i);
    }

    /** True if two host names mean the same machine ({@code prod-7} and {@code prod-7.acme.com}). */
    public static boolean sameHost(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        if (x.equals(y)) {
            return true;
        }
        if (x.matches("[\\d.:]+") || y.matches("[\\d.:]+")) {
            return false; // addresses: only equal ones
        }
        return x.split("\\.")[0].equals(y.split("\\.")[0]);
    }

    private static volatile String localHost;

    /** This computer's name. */
    public static String localHost() {
        String h = localHost;
        if (h == null) {
            try {
                h = java.net.InetAddress.getLocalHost().getHostName();
            } catch (IOException e) {
                h = System.getenv(LocalJvms.isWindows() ? "COMPUTERNAME" : "HOSTNAME");
                h = h == null ? "localhost" : h;
            }
            localHost = h;
        }
        return h;
    }
}
