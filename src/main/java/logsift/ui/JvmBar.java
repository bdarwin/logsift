package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import logsift.core.RemoteMirror;
import logsift.jvm.JmxJvms;
import logsift.jvm.JvmLink;
import logsift.jvm.JvmProcess;
import logsift.jvm.LocalJvms;
import logsift.jvm.LogOrigin;
import logsift.jvm.SshJvms;

/**
 * The bar above a log that names the JVM writing it, with its Threads, Profile and Heap a click
 * away. The JVM is found by itself where it can be (on this computer, or on the server of a log
 * opened over SSH: the one that has the log open, the one linked before, or the one the log
 * names); a log on a shared drive or copied from elsewhere gets a suggestion from what it says
 * about itself, and the user links it once. Links are remembered by program, for the log and
 * for its folder.
 */
final class JvmBar extends JPanel {

    /** What the bar needs from the window. */
    interface Host {
        JvmConnector connector();

        /** Opens the dialog to link this log, offering {@code s} first (may be null). */
        void linkDialog(JvmBar bar, JvmDialog.Suggestion s);

        /** Shows a live Threads tab for the connection (with {@code first} as its first dump). */
        ThreadsTab openThreads(JvmBar bar, String source, logsift.jvm.ThreadDump first, ThreadsTab.Capture capture,
                               logsift.jvm.Profiler.Target target);

        void openProfile(String source, logsift.jvm.Profiler.Target target);

        void openHeap(String source, logsift.jvm.Profiler.Target target);

        /** True if the tab is still open; then selects it. */
        boolean selectIfOpen(java.awt.Component tab);

        default boolean trustHost(String message) {
            return false;
        }

        Path knownHosts();
    }

    enum State { HIDDEN, LOOKING, FOUND, REMEMBERED, SUGGESTED, CONNECTED }

    private static final Preferences PREFS = AppState.prefs();

    private final Host host;
    private final String logName;
    private final String fileKey;
    private final String dirKey;
    private final Path file;               // on this computer (null for a log read over SSH)
    private final Path scan;               // what to read the hints from
    private final Charset charset;
    private final RemoteMirror.Target sftp; // the log's server, for a log read over SSH

    private volatile State state = State.HIDDEN;
    private JvmLink link;                  // found, remembered or connected
    private JvmProcess found;              // running there now
    private String why = "";
    private LogOrigin.Hint hint = LogOrigin.Hint.NONE;
    private String fileServer;
    private Shared conn;
    private ThreadsTab threads;
    private boolean dismissed;

    private final JLabel text = new JLabel();
    private final JButton threadsButton = new JButton("Threads", Icons.of("cpu"));
    private final JButton profileButton = new JButton("Profile", Icons.of("flame"));
    private final JButton heapButton = new JButton("Heap", Icons.of("memory-stick"));
    private final JButton connectButton = new JButton("Connect");
    private final JButton linkButton = new JButton("Find Its JVM…", Icons.of("link"));
    private final JButton more = new JButton(Icons.of("chevron-down"));
    private final JButton close = new JButton(Icons.of("x"));

    /**
     * @param file  the log on this computer, or null for one read over SSH ({@code sftp})
     * @param scan  the file to read for hints (the local copy of a remote log)
     */
    JvmBar(Host host, String logName, Path file, Path scan, Charset charset, RemoteMirror.Target sftp) {
        super(new BorderLayout(10, 0));
        this.host = host;
        this.logName = logName;
        this.file = file;
        this.scan = scan;
        this.charset = charset == null ? StandardCharsets.UTF_8 : charset;
        this.sftp = sftp;
        if (sftp != null) {
            fileKey = "sftp://" + sftp.user() + "@" + sftp.host() + ":" + sftp.port() + sftp.path();
            int slash = sftp.path().lastIndexOf('/');
            dirKey = fileKey.substring(0, fileKey.length() - sftp.path().length())
                    + (slash > 0 ? sftp.path().substring(0, slash) : "/");
        } else {
            Path abs = file.toAbsolutePath().normalize();
            fileKey = abs.toString();
            dirKey = abs.getParent() == null ? fileKey : abs.getParent().toString();
        }
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.uiColor("Component.borderColor", Color.GRAY)),
                BorderFactory.createEmptyBorder(4, 10, 4, 8)));
        text.setIconTextGap(8);
        text.setHorizontalAlignment(SwingConstants.LEFT);
        add(text, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        for (JButton b : new JButton[] {threadsButton, profileButton, heapButton, connectButton, linkButton, more, close}) {
            b.putClientProperty("JButton.buttonType", "toolBarButton");
            b.setFocusable(false);
            buttons.add(b);
        }
        threadsButton.setToolTipText("Take a thread dump of this JVM now");
        profileButton.setToolTipText("Profile this JVM: where it spends its time, memory or lock waits");
        heapButton.setToolTipText("See what fills this JVM's heap, and what grows");
        connectButton.setToolTipText("Connect to the JVM this log is linked to");
        more.setToolTipText("Link to another JVM, or forget the link");
        close.setToolTipText("Hide");
        threadsButton.addActionListener(e -> withConnection(this::showThreads));
        profileButton.addActionListener(e -> withConnection(c -> host.openProfile(c.c.source(), c.c.target())));
        heapButton.addActionListener(e -> withConnection(c -> host.openHeap(c.c.source(), c.c.target())));
        connectButton.addActionListener(e -> withConnection(c -> { }));
        linkButton.addActionListener(e -> host.linkDialog(this, suggestion()));
        more.addActionListener(e -> moreMenu());
        close.addActionListener(e -> {
            dismissed = true;
            setVisible(false);
            revalidateParent();
        });
        add(buttons, BorderLayout.EAST);
        setVisible(false);
    }

    // ---------------------------------------------------------------- finding the JVM

    /** Looks for the log's JVM in the background. */
    void start() {
        Thread t = new Thread(() -> {
            try {
                find();
            } catch (Exception | LinkageError e) {
                // nothing to show then
            }
        }, "logsift-jvm-link");
        t.setDaemon(true);
        t.start();
    }

    private void find() throws IOException {
        LogOrigin.Hint h = scan == null ? LogOrigin.Hint.NONE : LogOrigin.scan(scan, charset);
        JvmLink fileLink = JvmLink.decode(PREFS.get(key("jvm.link.", fileKey), null));
        JvmLink remembered;
        if (fileLink != null) {
            remembered = fileLink;
        } else {
            // the folder's link, unless the log says it comes from somewhere else
            JvmLink dir = JvmLink.decode(PREFS.get(key("jvm.linkDir.", dirKey), null));
            String here = sftp != null ? sftp.host() : LogOrigin.localHost();
            boolean elsewhere = !h.host().isEmpty() && !LogOrigin.sameHost(h.host(), here)
                    || !h.program().isEmpty() && dir != null && !JvmLink.sameProgram(h.program(), dir.program());
            remembered = elsewhere ? null : dir;
        }
        JvmLink match = null;
        JvmProcess running = null;
        String reason = "";
        String server = null;
        if (sftp != null) {
            RemoteMirror.Target t = new RemoteMirror.Target(sftp.host(), sftp.port(), sftp.user(), "", sftp.password(),
                    sftp.privateKey(), sftp.passphrase());
            try (SshJvms ssh = SshJvms.connect(t, host.knownHosts(), host::trustHost)) {
                List<JvmProcess> jvms = ssh.list();
                java.util.Set<Long> open = LogOrigin.openedBy(ssh, sftp.path(), jvms.stream().map(JvmProcess::pid).toList());
                JvmLink here = remembered != null && remembered.kind() == JvmLink.Kind.SSH
                        && LogOrigin.sameHost(remembered.host(), sftp.host()) ? remembered : null;
                LogOrigin.Match m = LogOrigin.choose(jvms, open, here, h, sftp.host(), sftp.path());
                if (m != null) {
                    running = m.jvm();
                    reason = m.why();
                    match = JvmLink.ssh(sftp.host(), sftp.port(), sftp.user(), m.jvm());
                }
            } catch (IOException e) {
                // can't look there: fall back to what is remembered
            }
        } else {
            server = LogOrigin.fileServer(file);
            if (server == null && LocalJvms.canAttach()) {
                List<JvmProcess> jvms = LocalJvms.list();
                java.util.Set<Long> open = LogOrigin.openedBy(file, jvms.stream().map(JvmProcess::pid).toList());
                JvmLink here = remembered != null && remembered.kind() == JvmLink.Kind.LOCAL ? remembered : null;
                LogOrigin.Match m = LogOrigin.choose(jvms, open, here, h, LogOrigin.localHost(), fileKey);
                if (m != null) {
                    running = m.jvm();
                    reason = m.why();
                    match = JvmLink.local(m.jvm());
                }
            }
        }
        JvmLink rememberedF = remembered;
        JvmLink matchF = match;
        JvmProcess runningF = running;
        String reasonF = reason;
        String serverF = server;
        SwingUtilities.invokeLater(() -> {
            if (state == State.CONNECTED) {
                return; // linked by hand meanwhile
            }
            hint = h;
            fileServer = serverF;
            if (matchF != null) {
                link = matchF;
                found = runningF;
                why = reasonF;
                setState(State.FOUND);
            } else if (rememberedF != null) {
                link = rememberedF;
                setState(State.REMEMBERED);
            } else if (sftp != null ? !h.isEmpty() : serverF != null
                    || !h.host().isEmpty() && !LogOrigin.sameHost(h.host(), LogOrigin.localHost())) {
                setState(State.SUGGESTED);
            } else {
                setState(State.HIDDEN);
            }
        });
    }

    /** What to offer first in the link dialog. */
    JvmDialog.Suggestion suggestion() {
        if (link != null) {
            return new JvmDialog.Suggestion(link.kind(), link.host(), link.port(), link.user(), link.pid(), link.program());
        }
        if (sftp != null) {
            return new JvmDialog.Suggestion(JvmLink.Kind.SSH, sftp.host(), sftp.port(), sftp.user(), hint.pid(),
                    hint.program());
        }
        String h = !hint.host().isEmpty() ? hint.host() : fileServer;
        if (h != null && !LogOrigin.sameHost(h, LogOrigin.localHost())) {
            return new JvmDialog.Suggestion(JvmLink.Kind.SSH, h, 0, "", hint.pid(), hint.program());
        }
        return new JvmDialog.Suggestion(JvmLink.Kind.LOCAL, "", 0, "", hint.pid(), hint.program());
    }

    // ---------------------------------------------------------------- showing

    State state() {
        return state;
    }

    String textForTest() {
        return text.getText();
    }

    JButton threadsButtonForTest() {
        return threadsButton;
    }

    JButton linkButtonForTest() {
        return linkButton;
    }

    JButton heapButtonForTest() {
        return heapButton;
    }

    private void setState(State s) {
        state = s;
        boolean live = s == State.FOUND || s == State.CONNECTED;
        threadsButton.setVisible(live);
        profileButton.setVisible(live);
        heapButton.setVisible(live);
        connectButton.setVisible(s == State.REMEMBERED);
        linkButton.setVisible(s == State.SUGGESTED);
        more.setVisible(s != State.SUGGESTED && s != State.HIDDEN);
        switch (s) {
            case FOUND -> {
                text.setIcon(Icons.of("cpu"));
                text.setText("<html><b>" + esc(found.name()) + "</b> (" + found.pid() + ") on " + esc(link.where())
                        + " writes this log <span style='color:" + gray() + "'>· " + esc(why) + "</span></html>");
            }
            case CONNECTED -> {
                text.setIcon(Icons.of("cpu"));
                text.setText("<html><b>" + esc(link.program()) + "</b> (" + link.pid() + ") on " + esc(link.where())
                        + " <span style='color:" + gray() + "'>· connected</span></html>");
            }
            case REMEMBERED -> {
                text.setIcon(Icons.of("link"));
                String where = link.kind() == JvmLink.Kind.LOCAL ? "" : " on " + esc(link.where());
                text.setText("<html>Linked to <b>" + esc(link.program()) + "</b>" + where
                        + (link.kind() == JvmLink.Kind.LOCAL ? " <span style='color:" + gray() + "'>· not running now</span>"
                        : "") + "</html>");
                connectButton.setVisible(link.kind() != JvmLink.Kind.LOCAL);
                linkButton.setText("Link to a JVM…");
                linkButton.setVisible(link.kind() == JvmLink.Kind.LOCAL);
            }
            case SUGGESTED -> {
                text.setIcon(Icons.of("lightbulb"));
                StringBuilder sb = new StringBuilder("<html>");
                if (!hint.isEmpty()) {
                    sb.append("This log says it was written by <b>").append(esc(hint.label())).append("</b>.");
                } else {
                    sb.append("This log is on <b>").append(esc(fileServer)).append("</b>'s shared drive.");
                }
                if (fileServer != null && !hint.isEmpty()) {
                    sb.append(" <span style='color:").append(gray()).append("'>(on ").append(esc(fileServer))
                            .append("'s shared drive)</span>");
                }
                text.setText(sb.append(" Link it to its JVM to see its threads.</html>").toString());
                linkButton.setText("Find Its JVM…");
            }
            default -> { }
        }
        setVisible(s != State.HIDDEN && s != State.LOOKING && !dismissed);
        revalidateParent();
    }

    private void revalidateParent() {
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }

    private static String gray() {
        Color c = Theme.uiColor("Label.disabledForeground", Color.GRAY);
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private void moreMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem other = new JMenuItem("Link to Another JVM…", Icons.of("link"));
        other.addActionListener(e -> host.linkDialog(this, suggestion()));
        m.add(other);
        if (conn != null) {
            JMenuItem disc = new JMenuItem("Disconnect");
            disc.addActionListener(e -> disconnect());
            m.add(disc);
        }
        if (load() != null || link != null) {
            JMenuItem forget = new JMenuItem("Unlink", Icons.of("unlink"));
            forget.setToolTipText("Forget the JVM of this log (and of its folder)");
            forget.addActionListener(e -> unlink());
            m.add(forget);
        }
        m.show(more, 0, more.getHeight());
    }

    // ---------------------------------------------------------------- connecting

    /** The connection, shared by the bar and the Threads tabs taken from it. */
    private static final class Shared {
        final JvmConnector.Connection c;
        private final AtomicInteger users = new AtomicInteger(1);

        Shared(JvmConnector.Connection c) {
            this.c = c;
        }

        /** A capture for one more tab; closing it lets go of the connection. */
        ThreadsTab.Capture share() {
            users.incrementAndGet();
            return new ThreadsTab.Capture() {
                private boolean released;

                @Override
                public String label() {
                    return c.capture().label();
                }

                @Override
                public logsift.jvm.ThreadDump take() throws Exception {
                    return c.capture().take();
                }

                @Override
                public boolean isOpen() {
                    return !released && c.capture().isOpen();
                }

                @Override
                public synchronized void close() {
                    if (!released) {
                        released = true;
                        release();
                    }
                }
            };
        }

        void release() {
            if (users.decrementAndGet() == 0) {
                c.capture().close();
            }
        }
    }

    /** A connection made elsewhere (the link dialog) for this log: remembered, and used from now on. */
    void linked(JvmConnector.Connection c) {
        if (conn != null) {
            conn.release();
        }
        conn = new Shared(c);
        first = c.first();
        link = c.link();
        save(link);
        dismissed = false;
        setState(State.CONNECTED);
    }

    private boolean connecting;

    /** Runs {@code then} with a connection: the one there is, or a new one to the JVM found or linked. */
    private void withConnection(Consumer<Shared> then) {
        if (conn != null && conn.c.isOpen()) {
            then.accept(conn);
            return;
        }
        if (conn != null) {
            conn.release();
            conn = null;
        }
        if (connecting) {
            return;
        }
        JvmLink l = link;
        if (l == null) {
            host.linkDialog(this, suggestion());
            return;
        }
        boolean direct = state == State.FOUND && found != null
                || l.kind() == JvmLink.Kind.SSH && sftp != null && LogOrigin.sameHost(l.host(), sftp.host())
                || l.kind() == JvmLink.Kind.JMX && l.user().isEmpty();
        if (!direct) {
            host.linkDialog(this, suggestion()); // a password to ask for: the dialog has the fields
            return;
        }
        connecting = true;
        text.setText("<html>Connecting to <b>" + esc(l.label()) + "</b>…</html>");
        JvmProcess f = found;
        Thread t = new Thread(() -> {
            try {
                JvmConnector.Connection c = connect(l, f);
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    linked(c);
                    then.accept(conn);
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    setState(state);
                    text.setText("<html>Could not connect to <b>" + esc(l.label()) + "</b>: " + esc(e.getMessage())
                            + "</html>");
                    linkButton.setText("Link to a JVM…");
                    linkButton.setVisible(true);
                });
            }
        }, "logsift-jvm-connect");
        t.setDaemon(true);
        t.start();
    }

    private JvmConnector.Connection connect(JvmLink l, JvmProcess f) throws IOException {
        JvmConnector con = host.connector();
        switch (l.kind()) {
            case LOCAL -> {
                JvmProcess p = f != null ? f : l.pick(LocalJvms.list());
                if (p == null) {
                    throw new IOException(l.program() + " isn't running");
                }
                return con.local(p, PREFS.get("jvm.localJdk", ""));
            }
            case SSH -> {
                RemoteMirror.Target t = new RemoteMirror.Target(sftp.host(), sftp.port(), sftp.user(), "",
                        sftp.password(), sftp.privateKey(), sftp.passphrase());
                JvmProcess p = f;
                if (p == null) {
                    try (SshJvms ssh = SshJvms.connect(t, host.knownHosts(), host::trustHost)) {
                        p = l.pick(ssh.list());
                    }
                }
                if (p == null) {
                    throw new IOException(l.program() + " isn't running on " + l.host());
                }
                return con.ssh(t, p, new SshJvms.Options(PREFS.get("jvm.jdk." + t.host(), ""),
                        PREFS.getBoolean("jvm.sudo", true), null));
            }
            default -> {
                return con.jmx(new JmxJvms.Address(l.host(), l.port(), "", null, PREFS.getBoolean("jvm.jmx.ssl", false)));
            }
        }
    }

    private logsift.jvm.ThreadDump first;  // the dump taken when connecting, for the first Threads tab

    private void showThreads(Shared c) {
        if (threads != null && host.selectIfOpen(threads) && threads.isLive()) {
            threads.takeDumpNow();
            return;
        }
        logsift.jvm.ThreadDump d = first;
        first = null;
        if (d != null) {
            threads = host.openThreads(this, c.c.source(), d, c.share(), c.c.target());
            return;
        }
        threadsButton.setEnabled(false);
        Thread t = new Thread(() -> {
            try {
                logsift.jvm.ThreadDump dump = c.c.capture().take();
                SwingUtilities.invokeLater(() -> {
                    threadsButton.setEnabled(true);
                    threads = host.openThreads(this, c.c.source(), dump, c.share(), c.c.target());
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    threadsButton.setEnabled(true);
                    text.setText("<html>Could not take a dump: " + esc(e.getMessage()) + "</html>");
                });
            }
        }, "logsift-jvm-dump");
        t.setDaemon(true);
        t.start();
    }

    /** Takes a dump (connecting first) and shows it; then {@code then} runs with its tab. */
    void showThreads(Consumer<ThreadsTab> then) {
        withConnection(c -> {
            if (threads != null && host.selectIfOpen(threads)) {
                then.accept(threads);
                return;
            }
            logsift.jvm.ThreadDump d = first;
            first = null;
            if (d != null) {
                threads = host.openThreads(this, c.c.source(), d, c.share(), c.c.target());
                then.accept(threads);
                return;
            }
            Thread t = new Thread(() -> {
                try {
                    logsift.jvm.ThreadDump dump = c.c.capture().take();
                    SwingUtilities.invokeLater(() -> {
                        threads = host.openThreads(this, c.c.source(), dump, c.share(), c.c.target());
                        then.accept(threads);
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> text.setText("<html>Could not take a dump: " + esc(e.getMessage())
                            + "</html>"));
                }
            }, "logsift-jvm-dump");
            t.setDaemon(true);
            t.start();
        });
    }

    /** True if a JVM is linked or found (a dump can be taken). */
    boolean hasJvm() {
        return state == State.FOUND || state == State.CONNECTED || state == State.REMEMBERED;
    }

    private void disconnect() {
        if (conn != null) {
            conn.release();
            conn = null;
        }
        setState(found != null ? State.FOUND : State.REMEMBERED);
    }

    private void unlink() {
        PREFS.remove(key("jvm.link.", fileKey));
        PREFS.remove(key("jvm.linkDir.", dirKey));
        disconnect();
        link = null;
        found = null;
        setState(!hint.isEmpty() ? State.SUGGESTED : State.HIDDEN);
    }

    /** Lets go of the connection (the log was closed). */
    void close() {
        if (conn != null) {
            conn.release();
            conn = null;
        }
    }

    // ---------------------------------------------------------------- remembering

    private JvmLink load() {
        JvmLink l = JvmLink.decode(PREFS.get(key("jvm.link.", fileKey), null));
        return l != null ? l : JvmLink.decode(PREFS.get(key("jvm.linkDir.", dirKey), null));
    }

    private void save(JvmLink l) {
        PREFS.put(key("jvm.link.", fileKey), l.encode());
        PREFS.put(key("jvm.linkDir.", dirKey), l.encode());
    }

    /** A preferences key for a path (they are limited to 80 characters). */
    static String key(String prefix, String path) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8));
            return prefix + java.util.HexFormat.of().formatHex(h, 0, 12);
        } catch (java.security.NoSuchAlgorithmException e) {
            return prefix + Integer.toHexString(path.hashCode());
        }
    }
}
