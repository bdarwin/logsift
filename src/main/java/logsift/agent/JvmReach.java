package logsift.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import logsift.core.RemoteMirror;
import logsift.jvm.JmxJvms;
import logsift.jvm.JvmLink;
import logsift.jvm.JvmProcess;
import logsift.jvm.LocalJvms;
import logsift.jvm.SshJvms;
import logsift.jvm.ThreadDump;

/**
 * The JVMs agents may ask about: those the person said where to find in Log Locations (on this
 * computer, a server over SSH, a JMX port), and those running on this computer. Connects only
 * after the person's OK ({@link Workspace#approve}); keeps a connection for the session. Over SSH
 * it signs in with the person's key and known hosts only; the window asks for what's missing.
 */
public class JvmReach implements AutoCloseable {

    /**
     * A JVM that can be asked about.
     *
     * @param id      its handle for agents ({@code jvm:1})
     * @param program what it runs ({@code orders.jar})
     * @param kind    where it is: this computer, over SSH, over JMX
     * @param host    the server (SSH or JMX), "" here
     * @param port    SSH's or JMX's port (0: the usual)
     * @param user    SSH's login or JMX's user, or ""
     * @param pid     a running JVM's process id here, or 0
     * @param from    where it came from: "service orders", "running here"
     */
    public record Place(String id, String program, JvmLink.Kind kind, String host, int port, String user, long pid,
                        String from) {
        /** "orders.jar on prod-7 (SSH)", "orders.jar (pid 4242) on this computer". */
        public String label() {
            return switch (kind) {
                case LOCAL -> program + (pid > 0 ? " (pid " + pid + ")" : "") + " on this computer";
                case SSH -> program + " on " + host + " (SSH" + (user.isEmpty() ? "" : ", " + user) + ")";
                case JMX -> (program.isEmpty() ? "the JVM" : program) + " on " + host + ":" + port + " (JMX)";
            };
        }

        /** What stays the same across listings: where and what. */
        String key() {
            return kind + "|" + host.toLowerCase(Locale.ROOT) + "|" + port + "|" + program + "|" + (kind == JvmLink.Kind.LOCAL
                    && program.isEmpty() ? pid : 0);
        }
    }

    /** A connected JVM. */
    public interface Target extends AutoCloseable {
        String label();

        ThreadDump dump() throws IOException;

        String diagnostic(String command, String... args) throws IOException;

        boolean isOpen();

        /**
         * Writes a heap dump to {@code local} (copied from the JVM's machine when it's another).
         *
         * @return a note for the person (where a copy stays), or null; {@code local} isn't written
         *         when the dump can only stay on the JVM's machine (JMX), and the note says where
         */
        default String heapDump(Path local) throws IOException {
            throw new IOException("Heap dumps can't be taken here.");
        }

        @Override
        void close();
    }

    /** A heap dump through the Heap tab's own way of taking one. */
    static String heapDump(logsift.jvm.Profiler.Target t, Path local) throws IOException {
        try {
            return t.heapDump(local, f -> { }, () -> Thread.currentThread().isInterrupted());
        } catch (logsift.jvm.JmxJvms.DumpOnServer e) {
            return "The heap dump was written on " + e.host() + " at " + e.path() + ", and stays there (over JMX it "
                    + "can't be copied, and Logsift doesn't remove files on a server). Copy it over to read it.";
        }
    }

    protected final Workspace w;
    private final Map<String, String> ids = new LinkedHashMap<>();
    private final Map<String, Place> places = new LinkedHashMap<>();
    private final Map<String, Target> open = new LinkedHashMap<>();
    private int next = 1;

    public JvmReach(Workspace w) {
        this.w = w;
    }

    /** The JVMs: those described for services (all, or {@code service}'s), then those running here. */
    public synchronized List<Place> list(String service) throws IOException {
        List<Place> out = new ArrayList<>();
        for (LogCatalog.Entry e : w.catalog().entries()) {
            if (service != null && !e.service().name().equalsIgnoreCase(service)) {
                continue;
            }
            for (LogCatalog.JvmPlace p : e.service().jvms()) {
                out.add(place(p.program(), p.kindOf(), p.host(), p.port(), p.user(), 0, "service " + e.service().name()));
            }
        }
        if (service == null && LocalJvms.canAttach()) {
            long self = LocalJvms.self();
            for (JvmProcess p : LocalJvms.list()) {
                if (p.pid() != self) {
                    out.add(place(p.name(), JvmLink.Kind.LOCAL, "", 0, "", p.pid(), "running here"));
                }
            }
        }
        return out;
    }

    private Place place(String program, JvmLink.Kind kind, String host, int port, String user, long pid, String from) {
        Place probe = new Place("", program, kind, host, port, user, pid, from);
        String id = ids.computeIfAbsent(probe.key(), k -> "jvm:" + next++);
        Place p = new Place(id, program, kind, host, port, user, pid, from);
        places.put(id, p);
        return p;
    }

    /** A JVM by its handle, a service's name (its first JVM) or a program's name. */
    public synchronized Place find(String what) throws IOException, Tool.Failure {
        String s = what.strip();
        if (!places.containsKey(s)) {
            list(null);
        }
        Place p = places.get(s);
        if (p != null) {
            return p;
        }
        LogCatalog.Entry e = w.catalog().entry(s);
        if (e != null && !e.service().jvms().isEmpty()) {
            return list(e.service().name()).get(0);
        }
        for (Place q : places.values()) {
            if (JvmLink.sameProgram(q.program(), s)) {
                return q;
            }
        }
        throw new Tool.Failure("No JVM " + s + ". list_jvms lists them; the person says where a service's JVM runs in "
                + "Log Locations (its JVMs).");
    }

    /** A connection to it: the one there is, or a new one. */
    public Target connect(Place p) throws Exception {
        synchronized (this) {
            Target t = open.get(p.id());
            if (t != null && t.isOpen()) {
                return t;
            }
        }
        Target t = switch (p.kind()) {
            case LOCAL -> local(p);
            case SSH -> ssh(p);
            case JMX -> jmx(p);
        };
        synchronized (this) {
            Target was = open.put(p.id(), t);
            if (was != null && was != t) {
                was.close();
            }
        }
        return t;
    }

    private Target local(Place p) throws IOException, Tool.Failure {
        JvmProcess proc = null;
        for (JvmProcess q : LocalJvms.list()) {
            if (p.pid() > 0 ? q.pid() == p.pid() : JvmLink.sameProgram(p.program(), q.name())) {
                proc = q;
                break;
            }
        }
        if (proc == null) {
            throw new Tool.Failure(p.program() + " isn't running on this computer.");
        }
        LocalJvms.Session s = LocalJvms.attach(proc.pid(), "");
        long pid = proc.pid();
        JvmProcess running = proc;
        String label = proc.name() + " (pid " + pid + ")";
        return new Target() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public ThreadDump dump() throws IOException {
                return s.dump();
            }

            @Override
            public String diagnostic(String command, String... args) throws IOException {
                return s.diagnostic(command, args);
            }

            @Override
            public String heapDump(Path local) throws IOException {
                return JvmReach.heapDump(logsift.jvm.Profiler.local(running, s), local);
            }

            @Override
            public boolean isOpen() {
                return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            }

            @Override
            public void close() {
                s.close();
            }
        };
    }

    private Target ssh(Place p) throws Exception {
        RemoteMirror.Target t = sshSignIn(p);
        if (t == null) {
            throw new Tool.Failure("No way to sign in to " + p.host() + " over SSH: the person didn't give one.");
        }
        SshJvms conn = SshJvms.connect(t, knownHosts(), this::trustHost);
        JvmProcess proc;
        try {
            proc = new JvmLink(JvmLink.Kind.SSH, p.host(), p.port(), p.user(), p.program(), 0).pick(conn.list());
        } catch (IOException e) {
            conn.close();
            throw e;
        }
        if (proc == null) {
            conn.close();
            throw new Tool.Failure(p.program() + " isn't running on " + p.host() + ".");
        }
        JvmProcess running = proc;
        SshJvms.Options o = new SshJvms.Options("", true, null);
        String label = running.name() + " (pid " + running.pid() + ") on " + p.host();
        return new Target() {
            @Override
            public String label() {
                return label;
            }

            @Override
            public ThreadDump dump() throws IOException {
                return conn.dump(running, o);
            }

            @Override
            public String diagnostic(String command, String... args) throws IOException {
                String[] all = new String[args.length + 1];
                all[0] = command;
                System.arraycopy(args, 0, all, 1, args.length);
                return conn.diagnostic(running, o, all);
            }

            @Override
            public String heapDump(Path local) throws IOException {
                return JvmReach.heapDump(logsift.jvm.Profiler.remote(running, conn, o), local);
            }

            @Override
            public boolean isOpen() {
                return conn.isConnected();
            }

            @Override
            public void close() {
                conn.close();
            }
        };
    }

    private Target jmx(Place p) throws IOException {
        JmxJvms.Session s = JmxJvms.connect(new JmxJvms.Address(p.host(), p.port(), p.user(),
                p.user().isEmpty() ? null : jmxPassword(p), false), 30);
        return new Target() {
            @Override
            public String label() {
                return s.label();
            }

            @Override
            public ThreadDump dump() throws IOException {
                return s.dump();
            }

            @Override
            public String diagnostic(String command, String... args) throws IOException {
                return s.diagnostic(command, args);
            }

            @Override
            public String heapDump(Path local) throws IOException {
                return JvmReach.heapDump(logsift.jvm.Profiler.jmx(s), local);
            }

            @Override
            public boolean isOpen() {
                return s.isConnected();
            }

            @Override
            public void close() {
                s.close();
            }
        };
    }

    // ---------------------------------------------------------------- what the window adds

    /**
     * How to sign in to a server over SSH: here, the person's own key ({@code ~/.ssh/id_ed25519}…),
     * no password. The window asks for what's missing.
     */
    protected RemoteMirror.Target sshSignIn(Place p) {
        Path key = null;
        for (String n : new String[] {"id_ed25519", "id_ecdsa", "id_rsa"}) {
            Path k = Path.of(System.getProperty("user.home"), ".ssh", n);
            if (Files.isRegularFile(k)) {
                key = k;
                break;
            }
        }
        if (key == null) {
            return null;
        }
        return new RemoteMirror.Target(p.host(), p.port() > 0 ? p.port() : 22,
                p.user().isEmpty() ? System.getProperty("user.name") : p.user(), "", null, key, null);
    }

    /** A server seen for the first time: trusted only when the person says so (never here). */
    protected boolean trustHost(String message) {
        return false;
    }

    /** A JMX port with a user: its password (here: none, so it fails as it should). */
    protected String jmxPassword(Place p) {
        return null;
    }

    protected Path knownHosts() {
        return Boolean.getBoolean("logsift.noKnownHosts") ? null
                : Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
    }

    @Override
    public synchronized void close() {
        open.values().forEach(Target::close);
        open.clear();
    }
}
