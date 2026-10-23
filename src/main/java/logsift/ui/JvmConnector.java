package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Predicate;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import logsift.core.RemoteMirror;
import logsift.jvm.JmxJvms;
import logsift.jvm.JvmLink;
import logsift.jvm.JvmProcess;
import logsift.jvm.LocalJvms;
import logsift.jvm.Profiler;
import logsift.jvm.SshJvms;
import logsift.jvm.ThreadDump;

/**
 * Connects to a JVM (on this computer, on a server over SSH, or through a JMX port) and takes a
 * first thread dump. Runs on a background thread; asks for the sudo password on the EDT.
 */
final class JvmConnector {

    /**
     * A connected JVM: its first dump, how to take more and to profile it, and the link to
     * remember for a log.
     */
    record Connection(String source, ThreadDump first, ThreadsTab.Capture capture, Profiler.Target target,
                      JvmLink link) {
        boolean isOpen() {
            return capture.isOpen();
        }
    }

    private final Window owner;
    private final Path knownHosts;
    private final Predicate<String> trustHost;

    JvmConnector(Window owner, Path knownHosts, Predicate<String> trustHost) {
        this.owner = owner;
        this.knownHosts = knownHosts;
        this.trustHost = trustHost;
    }

    Connection local(JvmProcess jvm, String jdk) throws IOException {
        LocalJvms.Session s = LocalJvms.attach(jvm.pid(), jdk);
        ThreadDump first;
        try {
            first = s.dump();
        } catch (IOException e) {
            s.close();
            throw e;
        }
        String label = jvm.name() + " (" + jvm.pid() + ")";
        ThreadsTab.Capture c = new ThreadsTab.Capture() {
            private volatile boolean closed;

            @Override
            public String label() {
                return label;
            }

            @Override
            public ThreadDump take() throws Exception {
                return s.dump();
            }

            @Override
            public boolean isOpen() {
                return !closed && ProcessHandle.of(jvm.pid()).map(ProcessHandle::isAlive).orElse(false);
            }

            @Override
            public void close() {
                closed = true;
                s.close();
            }
        };
        return new Connection(label, first, c, Profiler.local(jvm, s), JvmLink.local(jvm));
    }

    Connection ssh(RemoteMirror.Target t, JvmProcess jvm, SshJvms.Options options) throws IOException {
        // a connection of its own, for as long as it is used
        SshJvms conn = SshJvms.connect(t, knownHosts, trustHost);
        RemoteCapture c = new RemoteCapture(conn, t, jvm, options);
        ThreadDump first;
        try {
            first = c.take();
        } catch (Exception e) {
            c.close();
            throw e instanceof IOException io ? io : new IOException(e.getMessage(), e);
        }
        // the profiler asks the capture for the connection: it may have reconnected
        Profiler.Target target = new DelegatingTarget(c::target);
        return new Connection(jvm.name() + " on " + t.host(), first, c, target,
                JvmLink.ssh(t.host(), t.port(), t.user(), jvm));
    }

    Connection jmx(JmxJvms.Address a) throws IOException {
        JmxJvms.Session s = JmxJvms.connect(a, 30);
        ThreadDump first;
        try {
            first = s.dump();
        } catch (IOException e) {
            s.close();
            throw e;
        }
        JvmProcess jvm = s.process();
        ThreadsTab.Capture c = new ThreadsTab.Capture() {
            @Override
            public String label() {
                return s.label();
            }

            @Override
            public ThreadDump take() throws Exception {
                return s.dump();
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
        return new Connection(jvm.name() + " on " + a.label(), first, c, Profiler.jmx(s), JvmLink.jmx(a, jvm));
    }

    /** A profiler target asked for each time (the SSH connection may change). */
    private static final class DelegatingTarget implements Profiler.Target {
        private final java.util.function.Supplier<Profiler.Target> make;

        DelegatingTarget(java.util.function.Supplier<Profiler.Target> make) {
            this.make = make;
        }

        private Profiler.Target t() {
            return make.get();
        }

        @Override
        public String label() {
            return t().label();
        }

        @Override
        public java.util.List<Profiler.Engine> engines() {
            return t().engines();
        }

        @Override
        public java.util.List<Profiler.Mode> modes(Profiler.Engine engine) {
            return t().modes(engine);
        }

        @Override
        public java.util.List<String> hardwareEvents() {
            return t().hardwareEvents();
        }

        @Override
        public java.util.List<logsift.jvm.Profile> run(Profiler.Request request, java.util.function.DoubleConsumer progress,
                                                       java.util.function.BooleanSupplier cancelled) throws IOException {
            return t().run(request, progress, cancelled);
        }

        @Override
        public String diagnostic(String command, String... args) throws IOException {
            return t().diagnostic(command, args);
        }

        @Override
        public void heapDump(Path local, java.util.function.DoubleConsumer progress,
                             java.util.function.BooleanSupplier cancelled) throws IOException {
            t().heapDump(local, progress, cancelled);
        }

        @Override
        public String noHeapDump() {
            return t().noHeapDump();
        }
    }

    /** Dumps of a JVM on a server; reconnects, and asks for the sudo password when needed. */
    private final class RemoteCapture implements ThreadsTab.Capture {
        private SshJvms conn;
        private final RemoteMirror.Target target;
        private final JvmProcess jvm;
        private SshJvms.Options options;
        private volatile boolean closed;

        RemoteCapture(SshJvms conn, RemoteMirror.Target target, JvmProcess jvm, SshJvms.Options options) {
            this.conn = conn;
            this.target = target;
            this.jvm = jvm;
            this.options = options;
        }

        synchronized SshJvms conn() {
            return conn;
        }

        private Profiler.Target profiler;
        private SshJvms profilerConn;
        private SshJvms.Options profilerOptions;

        /** The profiler for the connection as it is now. */
        synchronized Profiler.Target target() {
            if (profiler == null || profilerConn != conn || profilerOptions != options) {
                profiler = Profiler.remote(jvm, conn, options);
                profilerConn = conn;
                profilerOptions = options;
            }
            return profiler;
        }

        @Override
        public String label() {
            return jvm.name() + " (" + jvm.pid() + ") on " + target.host();
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public synchronized ThreadDump take() throws Exception {
            if (!conn.isConnected()) {
                conn.close();
                conn = SshJvms.connect(target, knownHosts, trustHost);
            }
            for (int tries = 0; ; tries++) {
                try {
                    return conn.dump(jvm, options);
                } catch (SshJvms.SudoPasswordNeeded e) {
                    String pw = askSudoPassword(e.getMessage(), tries > 0);
                    if (pw == null) {
                        throw e;
                    }
                    options = new SshJvms.Options(options.jdkHome(), true, pw);
                }
            }
        }

        @Override
        public synchronized void close() {
            closed = true;
            conn.close();
        }
    }

    /** Asks (on the EDT) for the sudo password; null if cancelled. */
    private String askSudoPassword(String why, boolean again) {
        String[] out = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JPasswordField f = new JPasswordField(16);
                JPanel pn = new JPanel(new BorderLayout(0, 6));
                pn.add(new JLabel("<html>" + (again ? "That password didn't work. " : "") + why
                        + ".<br>Your sudo password on the server:</html>"), BorderLayout.NORTH);
                pn.add(f, BorderLayout.CENTER);
                if (JOptionPane.showConfirmDialog(owner, pn, "sudo", JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                    out[0] = new String(f.getPassword());
                }
            });
        } catch (Exception e) {
            return null;
        }
        return out[0];
    }
}
