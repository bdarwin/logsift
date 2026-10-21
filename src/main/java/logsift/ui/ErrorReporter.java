package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import logsift.core.Version;

/**
 * Unexpected errors: written to {@code ~/.logsift/errors.log} and shown in a small window with
 * the details, a Copy button and a link to report it. Logsift keeps running.
 */
final class ErrorReporter {
    private ErrorReporter() {}

    private static final long MAX_LOG = 1 << 20;
    private static JDialog open;
    private static int more;
    private static JLabel moreLabel;

    /** Catches errors from every thread (including the UI thread). */
    static void install() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> report(e));
    }

    static void report(Throwable e) {
        String details = details(e);
        log(details);
        SwingUtilities.invokeLater(() -> show(e, details));
    }

    static String details(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        String[] lines = trace.split("\\R");
        StringBuilder sb = new StringBuilder();
        sb.append("Logsift ").append(Version.current()).append(", ").append(HelpSupport.osText())
                .append(", ").append(HelpSupport.javaText()).append('\n');
        for (int i = 0; i < Math.min(lines.length, 60); i++) {
            sb.append(lines[i]).append('\n');
        }
        if (lines.length > 60) {
            sb.append("\t… ").append(lines.length - 60).append(" more lines\n");
        }
        return sb.toString();
    }

    private static synchronized void log(String details) {
        try {
            Path f = AppState.home().resolve("errors.log");
            Files.createDirectories(f.getParent());
            if (Files.exists(f) && Files.size(f) > MAX_LOG) {
                Files.move(f, f.resolveSibling("errors.old.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(f, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "  " + details + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // nowhere to write: the window still shows it
        }
    }

    /** Shows the error window, or counts the error if one is already showing. */
    static void show(Throwable e, String details) {
        if (open != null && open.isShowing()) {
            more++;
            moreLabel.setText("(" + more + " more error" + (more == 1 ? "" : "s") + " since; see errors.log)");
            return;
        }
        more = 0;
        boolean oom = e instanceof OutOfMemoryError;
        JFrame owner = null;
        for (java.awt.Frame f : java.awt.Frame.getFrames()) {
            if (f instanceof MainFrame mf && mf.isShowing()) {
                owner = mf;
            }
        }
        JDialog d = new JDialog(owner, "Something went wrong", false);
        JLabel head = new JLabel("<html><b>" + (oom ? "Logsift ran out of memory." : "Logsift hit an unexpected error.")
                + "</b><br>" + (oom ? "Close some tabs or searches and try again."
                        : "You can keep working. If something looks wrong, reopen the file.")
                + "<br>Reporting it helps fix it. The details are below (no log contents are included).</html>");
        head.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        JTextArea text = new JTextArea(details, 14, 80);
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        text.setCaretPosition(0);
        JButton report = new JButton("Report on GitHub");
        report.addActionListener(a -> HelpSupport.browse(d, HelpSupport.bugReportUrl(
                "Error: " + e.getClass().getSimpleName() + (e.getMessage() == null ? ""
                        : ": " + abbreviate(e.getMessage())), details)));
        JButton copy = new JButton("Copy Details");
        copy.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(details), null));
        JButton close = new JButton("Close");
        close.addActionListener(a -> d.dispose());
        moreLabel = new JLabel(" ");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(moreLabel);
        buttons.add(copy);
        buttons.add(report);
        buttons.add(close);
        d.add(head, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(text);
        sp.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        d.add(sp, BorderLayout.CENTER);
        d.add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(close);
        d.pack();
        d.setLocationRelativeTo(owner);
        open = d;
        d.setVisible(true);
    }

    private static String abbreviate(String s) {
        s = s.replaceAll("\\s+", " ");
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** The window that is showing, for tests. */
    static JDialog openForTest() {
        return open != null && open.isShowing() ? open : null;
    }
}
