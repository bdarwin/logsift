package logsift.ui;

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
        run(owner, message, task, done, e -> JOptionPane.showMessageDialog(owner,
                message + " failed: " + e.getMessage(), "Logsift", JOptionPane.ERROR_MESSAGE), () -> { });
    }

    /**
     * Like {@link #run(Window, String, Task, Consumer)}, with what to do (on the EDT) when it
     * fails, and when it is cancelled.
     */
    static <T> void run(Window owner, String message, Task<T> task, Consumer<T> done,
                        Consumer<Exception> failed, Runnable cancelledThen) {
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
        // quick tasks (a small file) finish before the window would flash up
        AtomicBoolean finished = new AtomicBoolean();
        javax.swing.Timer show = new javax.swing.Timer(300, e -> {
            if (!finished.get()) {
                dlg.setVisible(true);
            }
        });
        show.setRepeats(false);
        show.start();
        Thread t = new Thread(() -> {
            try {
                T result = task.run(f -> SwingUtilities.invokeLater(() -> bar.setValue((int) (f * 1000))),
                        cancelled::get);
                SwingUtilities.invokeLater(() -> {
                    finished.set(true);
                    dlg.dispose();
                    if (!cancelled.get()) {
                        done.accept(result);
                    } else {
                        cancelledThen.run();
                    }
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    finished.set(true);
                    dlg.dispose();
                    if (!cancelled.get()) {
                        failed.accept(e);
                    } else {
                        cancelledThen.run();
                    }
                });
            }
        }, "logsift-task");
        t.setDaemon(true);
        t.start();
    }
}
