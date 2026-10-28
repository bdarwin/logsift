package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Help → Use Logsift with Git…: the git settings that make Logsift git's difftool and mergetool,
 * to copy, or set for you (in your global git config) when you ask.
 */
final class GitSetup {
    private GitSetup() {}

    /** How Logsift is started: the app's own launcher, or "logsift" when run from a jar. */
    static String launcher() {
        String cmd = ProcessHandle.current().info().command().orElse("");
        String name = cmd.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        return cmd.isEmpty() || name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe")
                ? "logsift" : cmd;
    }

    /** The settings, as {@code git config} keys and values. */
    static List<String[]> settings(String launcher, boolean defaultDiff, boolean defaultMerge) {
        String exe = "\"" + launcher.replace("\\", "/") + "\"";
        List<String[]> out = new ArrayList<>();
        out.add(new String[] {"difftool.logsift.cmd", exe + " --diff \"$LOCAL\" \"$REMOTE\""});
        out.add(new String[] {"mergetool.logsift.cmd", exe + " --merge \"$LOCAL\" \"$REMOTE\" \"$BASE\" \"$MERGED\""});
        out.add(new String[] {"mergetool.logsift.trustExitCode", "true"});
        if (defaultDiff) {
            out.add(new String[] {"diff.tool", "logsift"});
        }
        if (defaultMerge) {
            out.add(new String[] {"merge.tool", "logsift"});
        }
        return out;
    }

    /** The settings as commands to paste in a terminal. */
    static String commands(List<String[]> settings) {
        StringBuilder b = new StringBuilder();
        for (String[] kv : settings) {
            b.append("git config --global ").append(kv[0]).append(" '").append(kv[1].replace("'", "'\\''"))
                    .append("'\n");
        }
        return b.toString();
    }

    static void show(Component parent) {
        String launcher = launcher();
        JCheckBox diff = new JCheckBox("Make it git's difftool (git difftool)", true);
        JCheckBox merge = new JCheckBox("Make it git's mergetool (git mergetool)", true);
        JTextArea text = new JTextArea(commands(settings(launcher, true, true)), 6, 72);
        text.setEditable(false);
        text.setFont(DiffPane.monoFont().deriveFont(12f));
        java.awt.event.ActionListener update = e -> text.setText(commands(settings(launcher, diff.isSelected(),
                merge.isSelected())));
        diff.addActionListener(update);
        merge.addActionListener(update);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        JLabel intro = new JLabel("<html><b>Use Logsift to compare and merge in git</b><br><br>"
                + "<b>git difftool</b> shows each changed file side by side in Logsift; <b>git mergetool</b> opens"
                + " each conflicted file<br>as a three-way merge: yours, the result, theirs. Save the merge and git"
                + " takes it; Abort leaves the file.<br><br>These are the settings (in your global git config, for"
                + " all your repositories):</html>");
        p.add(intro, BorderLayout.NORTH);
        p.add(new JScrollPane(text), BorderLayout.CENTER);
        JPanel opts = new JPanel();
        opts.setLayout(new BoxLayout(opts, BoxLayout.Y_AXIS));
        opts.add(diff);
        opts.add(merge);
        JLabel note = new JLabel("Set Up Git runs these commands for you. You can also copy them, or undo one with"
                + " git config --global --unset <name>.");
        note.setForeground(Theme.uiColor("Label.disabledForeground", Color.GRAY));
        note.setFont(note.getFont().deriveFont(note.getFont().getSize2D() - 1f));
        opts.add(note);
        p.add(opts, BorderLayout.SOUTH);
        Object[] buttons = {"Set Up Git", "Copy Commands", "Close"};
        int got = JOptionPane.showOptionDialog(parent, p, "Use Logsift with Git", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[0]);
        if (got == 1) {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(text.getText()), null);
        } else if (got == 0) {
            String out = apply(settings(launcher, diff.isSelected(), merge.isSelected()));
            JOptionPane.showMessageDialog(parent, out, "Use Logsift with Git", out.startsWith("Done")
                    ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Runs {@code git config --global} for each setting; says what happened. */
    static String apply(List<String[]> settings) {
        for (String[] kv : settings) {
            try {
                Process pr = new ProcessBuilder("git", "config", "--global", kv[0], kv[1]).redirectErrorStream(true)
                        .start();
                String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (!pr.waitFor(20, TimeUnit.SECONDS) || pr.exitValue() != 0) {
                    return "git config " + kv[0] + " failed" + (out.isEmpty() ? "" : ": " + out);
                }
            } catch (IOException e) {
                return "Couldn't run git (is it installed and on the PATH?): " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "Stopped";
            }
        }
        return "Done. Try git difftool, or git mergetool when a merge has conflicts.";
    }
}
