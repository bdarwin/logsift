package logmaster.ui;

import java.awt.BorderLayout;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/** Runs a long task in the background with a small progress window and a Cancel button. */
final class ProgressDialog {
    private ProgressDialog() {}

    interface Task<T> {
        T run(DoubleConsumer progress, BooleanSupplier cancelled) throws Exception;
    }

    /** Runs {@code task}; {@code done} gets the result on the EDT (not called if cancelled). */
    static <T> void run(Window owner, String message, Task<T> task, Consumer<T> done) {
        JDialog dlg = new JDialog(owner, message, java.awt.Dialog.ModalityType.MODELESS);
        JProgressBar bar = new JProgressBar(0, 1000);
        JButton cancel = new JButton("Cancel");
        AtomicBoolean cancelled = new AtomicBoolean();
        cancel.addActionListener(e -> cancelled.set(true));
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(new JLabel(message), BorderLayout.NORTH);
        p.add(bar, BorderLayout.CENTER);
        p.add(cancel, BorderLayout.EAST);
        dlg.setContentPane(p);
        dlg.pack();
        dlg.setSize(Math.max(dlg.getWidth(), 420), dlg.getHeight());
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);
        Thread t = new Thread(() -> {
            try {
                T result = task.run(f -> SwingUtilities.invokeLater(() -> bar.setValue((int) (f * 1000))),
                        cancelled::get);
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    if (!cancelled.get()) {
                        done.accept(result);
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    dlg.dispose();
                    if (!cancelled.get()) {
                        JOptionPane.showMessageDialog(owner, message + " failed: " + e.getMessage(),
                                "LogMaster", JOptionPane.ERROR_MESSAGE);
                    }
                });
            }
        }, "lm-task");
        t.setDaemon(true);
        t.start();
    }
}
