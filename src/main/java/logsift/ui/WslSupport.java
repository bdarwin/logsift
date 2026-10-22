package logsift.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Windows Subsystem for Linux: its files are reachable from Windows at
 * {@code \\wsl.localhost\<distribution>\...}, so they open (and are followed) like any file.
 */
final class WslSupport {
    private WslSupport() {}

    private static Path wslExe() {
        String root = System.getenv("SystemRoot");
        return Path.of(root == null ? "C:\\Windows" : root, "System32", "wsl.exe");
    }

    /** Whether this is Windows with WSL installed. */
    static boolean available() {
        return System.getProperty("os.name", "").startsWith("Windows") && Files.exists(wslExe());
    }

    /** Installed distributions, the default first. */
    static List<String> distributions() {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(wslExe().toString(), "--list", "--quiet").redirectErrorStream(true).start();
            byte[] b;
            try (InputStream in = p.getInputStream()) {
                b = in.readAllBytes();
            }
            p.waitFor(10, TimeUnit.SECONDS);
            // wsl.exe writes UTF-16LE
            String text = new String(b, StandardCharsets.UTF_16LE).replace("\u0000", "");
            for (String line : text.split("\\R")) {
                String d = line.strip();
                if (!d.isEmpty() && d.matches("[\\w.\\-]+")) {
                    out.add(d);
                }
            }
        } catch (IOException e) {
            // no WSL
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    /** The distribution's /home (or its root) as a Windows path. */
    static Path home(String distro) {
        for (String host : new String[] {"\\\\wsl.localhost\\", "\\\\wsl$\\"}) {
            Path root = Path.of(host + distro + "\\");
            if (Files.isDirectory(root)) {
                Path home = root.resolve("home");
                return Files.isDirectory(home) ? home : root;
            }
        }
        return Path.of("\\\\wsl.localhost\\" + distro + "\\home");
    }
}
