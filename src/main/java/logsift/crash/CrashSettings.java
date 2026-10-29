package logsift.crash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Where crash dumps find names: folders with PDBs (and program files), and whether Windows' own
 * PDBs may be downloaded from Microsoft (off unless the person turns it on). Kept in
 * {@code crash.properties} in Logsift's folder, so the window and {@code logsift-mcp} share it.
 */
public record CrashSettings(List<Path> symbolFolders, boolean downloadMicrosoft) {

    public static final CrashSettings DEFAULT = new CrashSettings(List.of(), false);

    public CrashSettings {
        symbolFolders = List.copyOf(symbolFolders);
    }

    public static Path file(Path logsiftHome) {
        return logsiftHome.resolve("crash.properties");
    }

    /** Where downloaded PDBs are kept: in Logsift's folder, laid out as a symbol server's. */
    public static Path cache(Path logsiftHome) {
        return logsiftHome.resolve("symbols");
    }

    public static CrashSettings load(Path logsiftHome) {
        Path f = file(logsiftHome);
        if (!Files.isRegularFile(f)) {
            return DEFAULT;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f)) {
            p.load(in);
        } catch (IOException | RuntimeException e) {
            return DEFAULT;
        }
        List<Path> folders = new ArrayList<>();
        for (int i = 0; ; i++) {
            String v = p.getProperty("symbols.folder." + i);
            if (v == null) {
                break;
            }
            try {
                folders.add(Path.of(v));
            } catch (RuntimeException e) {
                // not a path here
            }
        }
        return new CrashSettings(folders, Boolean.parseBoolean(p.getProperty("symbols.microsoft", "false")));
    }

    public void save(Path logsiftHome) throws IOException {
        Properties p = new Properties();
        for (int i = 0; i < symbolFolders.size(); i++) {
            p.setProperty("symbols.folder." + i, symbolFolders.get(i).toString());
        }
        p.setProperty("symbols.microsoft", String.valueOf(downloadMicrosoft));
        Files.createDirectories(logsiftHome);
        try (OutputStream out = Files.newOutputStream(file(logsiftHome))) {
            p.store(out, "Logsift: crash dump symbols");
        }
    }

    public CrashSettings withDownload(boolean on) {
        return new CrashSettings(symbolFolders, on);
    }

    public CrashSettings withFolders(List<Path> folders) {
        return new CrashSettings(folders, downloadMicrosoft);
    }
}
