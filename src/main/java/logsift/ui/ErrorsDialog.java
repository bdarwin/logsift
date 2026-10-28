package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;

/**
 * This session's unexpected errors (Help → Show Errors…, or the "⚠ 2 errors" button): when, a
 * line about each, and the details of the one selected, to copy or report.
 */
final class ErrorsDialog {
    private ErrorsDialog() {}

    private static JDialog open;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static String headText = "";

    static JDialog openForTest() {
        return open != null && open.isShowing() ? open : null;
    }

    static String headForTest() {
        return headText;
    }

    /** Clicks a button of the window, by its text. For tests. */
    static void clickForTest(String text) {
        click(open.getContentPane(), text);
    }

    private static void click(java.awt.Container c, String text) {
        for (java.awt.Component k : c.getComponents()) {
            if (k instanceof JButton b && b.getText().equals(text)) {
                b.doClick();
                return;
            }
            if (k instanceof java.awt.Container cc) {
                click(cc, text);
            }
        }
    }

    /** Shows the window (or brings it to the front, up to date). */
    static void show(JFrame owner, Runnable openErrorLog) {
        if (open != null && open.isShowing()) {
            open.dispose();
        }
        SessionErrors errors = ErrorReporter.session();
        List<SessionErrors.Entry> list = errors.list();
        JDialog d = new JDialog(owner, "Errors in This Session", false);
        JLabel head = new JLabel();
        head.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        JTextArea text = new JTextArea(14, 80);
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        DefaultListModel<String> model = new DefaultListModel<>();
        for (int i = list.size() - 1; i >= 0; i--) { // newest first
            SessionErrors.Entry e = list.get(i);
            model.addElement(e.time().format(TIME) + "   " + e.summary());
        }
        JList<String> rows = new JList<>(model);
        rows.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        rows.addListSelectionListener(ev -> {
            int i = rows.getSelectedIndex();
            text.setText(i < 0 ? "" : list.get(list.size() - 1 - i).details());
            text.setCaretPosition(0);
        });
        JButton report = new JButton("Report on GitHub");
        report.addActionListener(a -> {
            int i = rows.getSelectedIndex();
            if (i >= 0) {
                SessionErrors.Entry e = list.get(list.size() - 1 - i);
                HelpSupport.browse(d, HelpSupport.bugReportUrl("Error: " + e.summary(), e.details()));
            }
        });
        JButton copy = new JButton("Copy Details");
        copy.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text.getText()), null));
        JButton log = new JButton("Open errors.log");
        log.setToolTipText("Every error so far, also from earlier sessions");
        log.addActionListener(a -> openErrorLog.run());
        JButton clear = new JButton("Clear");
        clear.setToolTipText("Hide these errors until the next one (errors.log keeps them)");
        clear.addActionListener(a -> {
            errors.clear();
            d.dispose();
        });
        JButton close = new JButton("Close");
        close.addActionListener(a -> d.dispose());
        boolean none = list.isEmpty();
        head.setText(none ? "<html><b>No errors this session.</b><br>Earlier ones, if any, are in errors.log.</html>"
                : "<html><b>" + SessionErrors.countText(list.size()) + " in this session.</b> Logsift kept running;"
                        + " if a view looks wrong, reopen the file.<br>Reporting an error helps fix it. The details are"
                        + " a stack trace and the versions of Logsift, Java and the system. Check the text before"
                        + " sending:<br>an error's message can quote part of a log line.</html>");
        headText = head.getText();
        report.setEnabled(!none);
        copy.setEnabled(!none);
        clear.setEnabled(!none);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(log);
        buttons.add(clear);
        buttons.add(copy);
        buttons.add(report);
        buttons.add(close);
        d.add(head, BorderLayout.NORTH);
        if (!none) {
            JScrollPane listPane = new JScrollPane(rows);
            listPane.setPreferredSize(new Dimension(700, 110));
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listPane, new JScrollPane(text));
            split.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            d.add(split, BorderLayout.CENTER);
            rows.setSelectedIndex(0);
        }
        d.add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(close);
        d.pack();
        d.setLocationRelativeTo(owner);
        open = d;
        d.setVisible(true);
    }
}
