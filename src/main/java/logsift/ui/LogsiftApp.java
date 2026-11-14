package logsift.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import logsift.core.SingleInstance;
import logsift.core.StdinCapture;
import logsift.core.Version;

/**
 * Entry point: {@code logsift [file...]}, or {@code command | logsift -} to view piped output.
 * Only one Logsift runs per user: launching it again (or double-clicking a log) opens the
 * files in the window that is already open.
 */
public final class LogsiftApp {
    private LogsiftApp() {}

    private static MainFrame frame;
    /** Files that arrive before the window, with what to do with them. */
    private static final List<SingleInstance.Request> pending = new ArrayList<>();
    /** Compare from Explorer arrives as a launch per file, a moment apart: the two are gathered. */
    private static final logsift.core.Gather COMPARE = new logsift.core.Gather(java.time.Duration.ofMillis(1500),
            java.time.Duration.ofSeconds(10), paths -> SwingUtilities.invokeLater(() -> frame.compareFromMenu(paths)));

    public static void main(String[] args) throws IOException {
        // an office's proxy, and signing in to it: before anything connects (updates, URLs)
        logsift.core.Proxies.init();
        logsift.core.Channel.apply(); // a beta keeps its own settings and window
        List<Path> files = new ArrayList<>();
        boolean stdin = false;
        List<String> tool = null; // diff or merge, for git
        String action = SingleInstance.OPEN; // or what Explorer's or the Finder's Logsift menu asked
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--diff") && i + 2 < args.length) {
                tool = List.of("diff", abs(args[i + 1]), abs(args[i + 2]));
                i += 2;
                continue;
            }
            if (a.equals("--merge") && i + 4 < args.length) {
                tool = List.of("merge", abs(args[i + 1]), abs(args[i + 2]), abs(args[i + 3]), abs(args[i + 4]));
                i += 4;
                continue;
            }
            switch (a) {
                case "-", "--stdin" -> stdin = true;
                case "--analyze" -> action = ANALYZE;
                case "--compare" -> action = COMPARE_ACTION;
                case "--search" -> action = SEARCH;
                case "--version", "-v" -> {
                    System.out.println("Logsift " + Version.current());
                    return;
                }
                case "--help", "-h" -> {
                    System.out.println("""
                            Usage: logsift [file...]        open log files
                                   command | logsift -      view another program's output as it is written
                                   logsift --analyze file...
                                                            each file in the view that explains it (GC log,
                                                            thread dump, heap dump, crash, recording)
                                   logsift --compare A [B]  compare two files or folders (Explorer sends one
                                                            per launch: they are paired up)
                                   logsift --search folder  search in a folder
                                   logsift --diff LEFT RIGHT
                                                            compare two files (waits until the comparison is closed)
                                   logsift --merge LOCAL REMOTE BASE MERGED
                                                            merge, as git's mergetool: exits 0 when saved, 1 if not
                            """);
                    return;
                }
                default -> {
                    if (!a.startsWith("-psn_")) { // ignore macOS process ids
                        files.add(Path.of(a));
                    }
                }
            }
        }
        // a Logsift is already running: give it the files and stop (piped input stays here)
        boolean single = !Boolean.getBoolean("logsift.multiInstance");
        if (tool != null && single) {
            int code = SingleInstance.command(AppState.home(), tool);
            if (code >= 0) {
                System.exit(code); // git waits for this
                return;
            }
        }
        List<String> toolCommand = tool;
        String startAction = action;
        if (single && !stdin && toolCommand == null) { // a window for piped input (or git) stays separate
            try {
                SingleInstance si = SingleInstance.openOrListen(AppState.home(), action, files,
                        r -> SwingUtilities.invokeLater(() -> handle(r)), LogsiftApp::instanceCommand,
                        java.time.Duration.ofSeconds(20));
                if (si == null) {
                    // the running Logsift took the files: end now (the launcher's splash screen
                    // would otherwise keep this process, and its splash, alive)
                    MainFrame.menuDid("handed over " + action + " (pid " + ProcessHandle.current().pid() + ")");
                    System.exit(0);
                    return;
                }
                Runtime.getRuntime().addShutdownHook(new Thread(si::close));
            } catch (IOException e) {
                // can't listen: still works, just as separate windows
            }
        }
        Path capture = stdin ? StdinCapture.start(System.in, AppState.home().resolve("stdin")) : null;

        ErrorReporter.install();
        if (MainFrame.isMac()) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", logsift.core.Channel.appName());
        }
        installDesktopHandlers();
        // Dock icon on macOS when run from Gradle or a jar (the packaged app has its own)
        if (java.awt.Taskbar.isTaskbarSupported()
                && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
            java.awt.Taskbar.getTaskbar().setIconImage(logsift.tools.IconGenerator.render(512));
        }
        SwingUtilities.invokeLater(() -> {
            boolean dark = AppState.prefs().getBoolean("dark", false);
            Theme.setFontFamily(AppState.prefs().get("fontFamily", null));
            LogView.setTabWidth(AppState.prefs().getInt("tabWidth", 4));
            Theme.apply(dark);
            MainFrame.exitOnClose = true;
            MainFrame f = new MainFrame();
            f.setVisible(true);
            frame = f;
            if (toolCommand != null) {
                // started for git: this window is for the comparison or merge, and ends with it
                f.runTool(toolCommand, code -> System.exit(code));
                return;
            }
            if (files.isEmpty() && capture == null && pending.isEmpty()
                    && AppState.prefs().getBoolean("restoreSession", true)) {
                f.restoreSession(); // reopen what was open last time
            }
            if (!files.isEmpty()) {
                handle(new SingleInstance.Request(startAction, files));
            }
            if (capture != null) {
                f.openPiped(capture);
            }
            List<SingleInstance.Request> early = List.copyOf(pending);
            pending.clear();
            early.forEach(LogsiftApp::handle);
            if (pendingTools.isEmpty() && capture == null) {
                f.openAssistantAtStart(); // unless Settings → Assistant says not to
            }
            for (Object[] t : pendingTools) {
                @SuppressWarnings("unchecked")
                List<String> cmd = (List<String>) t[0];
                f.runTool(cmd, (java.util.function.IntConsumer) t[1]);
            }
            pendingTools.clear();
        });
    }

    private static String abs(String p) {
        return Path.of(p).toAbsolutePath().normalize().toString();
    }

    private static final List<Object[]> pendingTools = new ArrayList<>();

    /** A command from a second launch (git): run it in this window when there is one. */
    private static void runTool(List<String> cmd, java.util.function.IntConsumer reply) {
        if (frame == null) {
            pendingTools.add(new Object[] {cmd, reply});
        } else {
            frame.runTool(cmd, reply);
        }
    }

    /** A command from another Logsift process: an agent's audit or question, or git's diff and merge. */
    private static void instanceCommand(List<String> cmd, java.util.function.IntConsumer reply) {
        if (!cmd.isEmpty() && cmd.get(0).equals("audit")) {
            // an agent reading the assistant's audit: answered quietly, the window stays as it is
            Thread t = new Thread(() -> reply.accept(logsift.chat.AuditTools.run(
                    logsift.chat.ChatService.get().home(), logsift.chat.ChatService.get().store(), cmd)),
                    "logsift-audit-answer");
            t.setDaemon(true);
            t.start();
            return;
        }
        if (cmd.size() == 5 && cmd.get(0).equals(logsift.agent.mcp.McpServer.ASK)) {
            // an agent outside Logsift wants to touch a running JVM, and can't ask its person
            SwingUtilities.invokeLater(() -> {
                if (frame == null || !frame.isDisplayable()) {
                    reply.accept(1);
                    return;
                }
                new AgentAsk(frame, cmd.get(1), cmd.get(2), cmd.get(3), cmd.get(4).equals("session"),
                        reply).ask();
            });
            return;
        }
        SwingUtilities.invokeLater(() -> runTool(cmd, reply));
    }

    static final String ANALYZE = "analyze";
    static final String COMPARE_ACTION = "compare";
    static final String SEARCH = "search";

    /** Files from the system (double-click, Open With, the Dock) or a second launch. */
    private static void open(List<Path> paths) {
        handle(new SingleInstance.Request(SingleInstance.OPEN, paths));
    }

    /** Files with what to do with them (open, or the right-click menu's Analyze, Compare, Search). */
    private static void handle(SingleInstance.Request r) {
        if (frame == null) {
            pending.add(r);
            return;
        }
        switch (r.action()) {
            case ANALYZE -> frame.analyzeFiles(r.paths());
            case COMPARE_ACTION -> {
                if (r.paths().size() >= 2) {
                    frame.compareFromMenu(r.paths()); // all in one launch (the Finder sends them together)
                } else {
                    COMPARE.add(r.paths());
                }
            }
            case SEARCH -> frame.searchFromMenu(r.paths());
            default -> frame.openFiles(r.paths());
        }
    }

    /** macOS sends files and About through these rather than the command line. */
    private static void installDesktopHandlers() {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        Desktop d = Desktop.getDesktop();
        if (d.isSupported(Desktop.Action.APP_OPEN_FILE)) {
            d.setOpenFileHandler(e -> {
                List<Path> paths = new ArrayList<>();
                e.getFiles().forEach(f -> paths.add(f.toPath()));
                SwingUtilities.invokeLater(() -> open(paths));
            });
        }
        if (d.isSupported(Desktop.Action.APP_ABOUT)) {
            d.setAboutHandler(e -> HelpSupport.showAbout(frame));
        }
        if (d.isSupported(Desktop.Action.APP_PREFERENCES)) {
            d.setPreferencesHandler(e -> SwingUtilities.invokeLater(() -> {
                if (frame != null) {
                    frame.showSettings(null);
                }
            }));
        }
    }
}
