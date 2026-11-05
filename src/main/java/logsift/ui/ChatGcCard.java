package logsift.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import logsift.jvm.GcLog;
import logsift.jvm.GcReport;

/**
 * The heap chart under the assistant's {@code analyze_gc} call: drawn by Logsift from the log, so
 * the answer needn't draw one in text and no token is spent on it. A click on a point shows its
 * line; Show GC Charts opens them all in a tab.
 */
final class ChatGcCard extends JPanel {
    /** Larger than this, the log isn't read again for the card: the button opens the charts. */
    static final long MAX_BYTES = 256L << 20;

    /** Reports read lately, by file, size and time: a chat scrolled again doesn't read them again. */
    private static final Map<String, GcReport> READ = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, GcReport> e) {
            return size() > 4;
        }
    };

    private final JPanel chart = new JPanel(new BorderLayout());
    private final JLabel note = new JLabel("Drawing the heap…");
    private GcReport report;

    ChatGcCard(Path file, Runnable showAll, LongConsumer showLine) {
        super(new BorderLayout(0, 4));
        setName("gcCard");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 18, 4, 0));
        chart.setOpaque(false);
        note.setForeground(Theme.dimmedForeground());
        note.setFont(note.getFont().deriveFont(note.getFont().getSize2D() - 1f));
        JButton all = new JButton("Show GC Charts", Icons.of("gauge", ChatColors.accent(), 14));
        all.setName("showGcCharts");
        all.setToolTipText("Heap, pauses, allocation and time in GC, in a tab beside the log");
        all.addActionListener(e -> showAll.run());
        JPanel below = new JPanel(new BorderLayout(8, 0));
        below.setOpaque(false);
        below.add(all, BorderLayout.WEST);
        below.add(note, BorderLayout.CENTER); // shortened with … in a narrow chat
        add(chart, BorderLayout.CENTER);
        add(below, BorderLayout.SOUTH);
        load(file, showLine);
    }

    GcReport reportForTest() {
        return report;
    }

    private void load(Path file, LongConsumer showLine) {
        String key;
        long size;
        try {
            if (file == null || !Files.isRegularFile(file)) {
                note.setText(""); // in a zip, or gone: the button opens it
                return;
            }
            size = Files.size(file);
            key = file + "|" + size + "|" + Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            note.setText("");
            return;
        }
        GcReport known;
        synchronized (READ) {
            known = READ.get(key);
        }
        if (known != null) {
            show(known, showLine);
            return;
        }
        if (size > MAX_BYTES) {
            note.setText("A large log: the charts are a click away.");
            return;
        }
        Thread t = new Thread(() -> {
            GcReport r = null;
            try (logsift.core.LogDocument doc = logsift.core.LogDocument.open(file)) {
                while (doc.isIndexing()) {
                    Thread.sleep(20);
                }
                r = GcReport.of(GcLog.parse(GcLog.of(doc), null, () -> false));
            } catch (Exception | OutOfMemoryError e) {
                r = null;
            }
            GcReport done = r;
            if (done != null) {
                synchronized (READ) {
                    READ.put(key, done);
                }
            }
            SwingUtilities.invokeLater(() -> show(done, showLine));
        }, "logsift-chat-gc");
        t.setDaemon(true);
        t.start();
    }

    private void show(GcReport r, LongConsumer showLine) {
        report = r;
        chart.removeAll();
        if (r == null || r.withHeap().size() < 2) {
            note.setText("");
        } else {
            GcChart c = GcTab.heapChart(r, showLine);
            c.setName("gcHeap");
            c.setTitle("Heap"); // short: its legend says the rest, in a narrow chat too
            c.setPreferredSize(new Dimension(520, 190));
            chart.add(c, BorderLayout.CENTER);
            note.setText("Paused " + GcReport.pct(r.pausedPercent()) + " of the time · longest "
                    + GcReport.ms(r.pauses().max()) + " · click a point for its line");
        }
        revalidate();
        repaint();
    }
}
