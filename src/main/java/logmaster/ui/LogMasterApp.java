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
        SwingUtilities.invokeLater(() -> {
            boolean dark = Preferences.userNodeForPackage(MainFrame.class).getBoolean("dark", false);
            Theme.apply(dark);
            MainFrame f = new MainFrame();
            f.setVisible(true);
            for (String a : args) {
                f.openFile(Path.of(a));
            }
        });
    }
}
