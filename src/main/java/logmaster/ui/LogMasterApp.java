package logmaster.ui;

import java.nio.file.Path;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;

/** Entry point: {@code logmaster [file...]}. */
public final class LogMasterApp {
    private LogMasterApp() {}

    public static void main(String[] args) {
        if (MainFrame.isMac()) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", "LogMaster");
        }
        // Dock icon on macOS when run from Gradle or a jar (the packaged app has its own)
        if (java.awt.Taskbar.isTaskbarSupported()
                && java.awt.Taskbar.getTaskbar().isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
            java.awt.Taskbar.getTaskbar().setIconImage(logmaster.tools.IconGenerator.render(512));
        }
        SwingUtilities.invokeLater(() -> {
            boolean dark = AppState.prefs().getBoolean("dark", false);
            Theme.apply(dark);
            MainFrame f = new MainFrame();
            f.setVisible(true);
            if (args.length == 0) {
                f.restoreSession(); // reopen what was open last time
            }
            for (String a : args) {
                f.openFile(Path.of(a));
            }
        });
    }
}
