package logsift.ui;

import java.awt.Component;
import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import logsift.core.Exceptions;
import logsift.core.SourceFinder;

/**
 * Opens the source of a stack frame at its line in the user's editor: the file is looked up in
 * the source folders from Settings, then opened with IntelliJ IDEA, PyCharm, Visual Studio
 * Code, a command of the user's, or the system's app for the file.
 */
final class SourceOpener {
    private SourceOpener() {}

    enum Editor {
        AUTO("Automatic (IntelliJ IDEA or PyCharm, else Visual Studio Code)"),
        INTELLIJ("IntelliJ IDEA"),
        PYCHARM("PyCharm"),
        VSCODE("Visual Studio Code"),
        SYSTEM("The system's app for the file (without the line)"),
        CUSTOM("My own command");

        final String label;

        Editor(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final Preferences PREFS = AppState.prefs();
    private static SourceFinder finder;
    /** Tests: gets the command instead of running it. */
    static Consumer<List<String>> launchedForTest;

    // ---------------------------------------------------------------- settings

    static List<Path> roots() {
        List<Path> out = new ArrayList<>();
        for (String s : PREFS.get("source.roots", "").split("\n")) {
            if (!s.isBlank()) {
                out.add(Path.of(s.strip()));
            }
        }
        return out;
    }

    static synchronized void setRoots(List<Path> roots) {
        StringBuilder sb = new StringBuilder();
        for (Path p : roots) {
            sb.append(p.toAbsolutePath().normalize()).append('\n');
        }
        PREFS.put("source.roots", sb.toString());
        finder = null;
    }

    static Editor editor() {
        try {
            return Editor.valueOf(PREFS.get("source.editor", Editor.AUTO.name()));
        } catch (IllegalArgumentException e) {
            return Editor.AUTO;
        }
    }

    static void setEditor(Editor e) {
        PREFS.put("source.editor", e.name());
    }

    static String command() {
        return PREFS.get("source.command", "");
    }

    static void setCommand(String c) {
        PREFS.put("source.command", c == null ? "" : c);
    }

    /** Forgets what was found in the folders (they may have changed). */
    static synchronized void rescan() {
        finder = null;
    }

    static synchronized Path find(Exceptions.Frame f) {
        if (finder == null) {
            finder = new SourceFinder(roots());
        }
        return finder.find(f);
    }

    // ---------------------------------------------------------------- opening

    /**
     * Finds the frame's file (in the background: the folders may be big), asking for a source
     * folder if none has it, and opens it at its line. Call on the event thread.
     */
    static void open(Component parent, Exceptions.Frame f) {
        new javax.swing.SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() {
                return find(f);
            }

            @Override
            protected void done() {
                Path file;
                try {
                    file = get();
                } catch (Exception e) {
                    file = null;
                }
                if (file != null) {
                    openFound(parent, file, f.line());
                } else if (askForFolder(parent, f)) {
                    open(parent, f);
                }
            }
        }.execute();
    }

    private static void openFound(Component parent, Path file, int line) {
        try {
            launch(file, Math.max(1, line));
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(parent, "Could not open " + file + ":\n" + e.getMessage()
                    + "\n\nChoose another editor in Settings → Source Code.", "Open Source", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Says the file wasn't found and offers to add a source folder; true if one was added. */
    private static boolean askForFolder(Component parent, Exceptions.Frame f) {
        String name = f.cls() != null ? f.cls().substring(f.cls().lastIndexOf('.') + 1) + " ("
                + (f.file() == null ? "?" : f.file()) + ")" : f.file();
        Object[] options = {"Add a Source Folder…", "Cancel"};
        int choice = JOptionPane.showOptionDialog(parent,
                "<html><b>" + esc(name) + "</b> is not in your source folders.<br><br>"
                        + (roots().isEmpty() ? "Add the folder of your project (its source code) once,<br>"
                                + "and Logsift opens stack lines in your editor.<br>"
                                : "Source folders: " + esc(roots().toString()) + "<br>")
                        + "<small>Settings → Source Code lists them and the editor to use.</small></html>",
                "Open Source", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options,
                options[0]);
        if (choice != 0) {
            return false;
        }
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("The folder of your project's source code");
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION || fc.getSelectedFile() == null) {
            return false;
        }
        List<Path> roots = new ArrayList<>(roots());
        roots.add(fc.getSelectedFile().toPath());
        setRoots(roots);
        return true;
    }

    /** Opens a file at a line with the chosen editor. */
    static void launch(Path file, int line) throws IOException {
        Editor e = editor();
        boolean python = file.getFileName().toString().endsWith(".py");
        if (e == Editor.AUTO) {
            Path jb = jetBrains(python ? "pycharm" : "idea");
            if (jb == null) {
                jb = jetBrains(python ? "idea" : "pycharm");
            }
            if (jb != null) {
                run(List.of(jb.toString(), "--line", Integer.toString(line), file.toString()));
                return;
            }
            Path code = vsCode();
            if (code != null) {
                run(List.of(code.toString(), "-g", file + ":" + line));
                return;
            }
            system(file);
            return;
        }
        switch (e) {
            case INTELLIJ, PYCHARM -> {
                Path jb = jetBrains(e == Editor.INTELLIJ ? "idea" : "pycharm");
                if (jb == null) {
                    throw new IOException(e.label + " was not found. Is it installed?");
                }
                run(List.of(jb.toString(), "--line", Integer.toString(line), file.toString()));
            }
            case VSCODE -> {
                Path code = vsCode();
                if (code != null) {
                    run(List.of(code.toString(), "-g", file + ":" + line));
                } else {
                    browse(URI.create("vscode://file" + toUriPath(file) + ":" + line));
                }
            }
            case CUSTOM -> {
                List<String> cmd = custom(command(), file, line);
                if (cmd.isEmpty()) {
                    throw new IOException("No command is set.");
                }
                run(cmd);
            }
            default -> system(file);
        }
    }

    /** The user's command with {file} and {line} filled in, split like a shell would (quotes kept together). */
    static List<String> custom(String command, Path file, int line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any || cur.length() > 0) {
                    out.add(cur.toString());
                }
                cur.setLength(0);
                any = false;
            } else {
                cur.append(c);
            }
        }
        if (any || cur.length() > 0) {
            out.add(cur.toString());
        }
        out.replaceAll(s -> s.replace("{file}", file.toString()).replace("{line}", Integer.toString(line)));
        if (!command.contains("{file}")) {
            out.add(file.toString());
        }
        return out;
    }

    private static String toUriPath(Path file) {
        String p = file.toAbsolutePath().toString().replace('\\', '/');
        return (p.startsWith("/") ? "" : "/") + p.replace(" ", "%20");
    }

    private static void run(List<String> cmd) throws IOException {
        if (launchedForTest != null) {
            launchedForTest.accept(cmd);
            return;
        }
        new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    private static void system(Path file) throws IOException {
        if (launchedForTest != null) {
            launchedForTest.accept(List.of("open", file.toString()));
            return;
        }
        Desktop.getDesktop().open(file.toFile());
    }

    private static void browse(URI uri) throws IOException {
        if (launchedForTest != null) {
            launchedForTest.accept(List.of("browse", uri.toString()));
            return;
        }
        Desktop.getDesktop().browse(uri);
    }

    // ---------------------------------------------------------------- finding editors

    private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    /** IntelliJ IDEA's ("idea") or PyCharm's ("pycharm") command-line launcher, or null. */
    static Path jetBrains(String tool) {
        String home = System.getProperty("user.home");
        String app = tool.equals("idea") ? "IntelliJ IDEA" : "PyCharm";
        if (OS.contains("mac")) {
            for (Path dir : List.of(Path.of("/Applications"), Path.of(home, "Applications"),
                    Path.of(home, "Applications", "JetBrains Toolbox"))) {
                Path found = newest(dir, app, ".app", "Contents/MacOS/" + tool);
                if (found != null) {
                    return found;
                }
            }
        } else if (OS.contains("win")) {
            String local = System.getenv().getOrDefault("LOCALAPPDATA", home + "\\AppData\\Local");
            Path script = Path.of(local, "JetBrains", "Toolbox", "scripts", tool + ".cmd");
            if (Files.isRegularFile(script)) {
                return script;
            }
            for (Path dir : List.of(Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "JetBrains"),
                    Path.of(local, "Programs"))) {
                Path found = newest(dir, app, "", "bin\\" + tool + "64.exe");
                if (found != null) {
                    return found;
                }
            }
        } else {
            Path script = Path.of(home, ".local", "share", "JetBrains", "Toolbox", "scripts", tool);
            if (Files.isExecutable(script)) {
                return script;
            }
            Path onPath = onPath(tool);
            if (onPath != null) {
                return onPath;
            }
            Path sh = onPath(tool + ".sh");
            if (sh != null) {
                return sh;
            }
            for (Path dir : List.of(Path.of("/opt"), Path.of(home))) {
                Path found = newest(dir, tool.equals("idea") ? "idea" : "pycharm", "", "bin/" + tool + ".sh");
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** VS Code's command-line launcher, or null. */
    static Path vsCode() {
        Path onPath = onPath(OS.contains("win") ? "code.cmd" : "code");
        if (onPath != null) {
            return onPath;
        }
        String home = System.getProperty("user.home");
        List<Path> places = OS.contains("mac")
                ? List.of(Path.of("/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code"),
                        Path.of(home, "Applications/Visual Studio Code.app/Contents/Resources/app/bin/code"))
                : OS.contains("win")
                        ? List.of(Path.of(System.getenv().getOrDefault("LOCALAPPDATA", home + "\\AppData\\Local"),
                                "Programs", "Microsoft VS Code", "bin", "code.cmd"),
                                Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"),
                                        "Microsoft VS Code", "bin", "code.cmd"))
                        : List.of(Path.of("/usr/bin/code"), Path.of("/snap/bin/code"));
        for (Path p : places) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** In {@code dir}, the last (by name: newest version) folder starting with {@code prefix}, with {@code inside}. */
    private static Path newest(Path dir, String prefix, String suffix, String inside) {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        Path best = null;
        try (var s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.startsWith(prefix) && n.endsWith(suffix)) {
                    Path exe = p.resolve(inside);
                    if (Files.isRegularFile(exe) && (best == null || n.compareTo(best.getFileName().toString()) > 0)) {
                        best = p;
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return best == null ? null : best.resolve(inside);
    }

    private static Path onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path p = Path.of(dir, name);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
