package logsift.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds the person's {@code copilot} program. An app started from the Dock or Start menu doesn't
 * get the shell's PATH, so the places it's usually installed (npm, Homebrew, nvm, WinGet) are
 * looked in too.
 */
public final class CopilotLocator {
    private CopilotLocator() {
    }

    /** The program, or null if it isn't installed. {@code chosen}: the person's own choice, first. */
    public static Path find(String chosen) {
        return find(chosen, System.getenv("PATH"), Path.of(System.getProperty("user.home")), windows(),
                System.getenv("APPDATA"), System.getenv("LOCALAPPDATA"));
    }

    static Path find(String chosen, String pathVar, Path home, boolean windows, String appData, String localAppData) {
        if (chosen != null && !chosen.isBlank()) {
            Path p = Path.of(chosen.strip());
            return Files.isRegularFile(p) ? p : null;
        }
        for (Path p : candidates(pathVar, home, windows, appData, localAppData)) {
            if (Files.isRegularFile(p) && (windows || Files.isExecutable(p))) {
                return p;
            }
        }
        return null;
    }

    /** Where to look, in order. */
    static List<Path> candidates(String pathVar, Path home, boolean windows, String appData, String localAppData) {
        List<String> names = windows ? List.of("copilot.exe", "copilot.cmd") : List.of("copilot");
        List<Path> dirs = new ArrayList<>();
        if (pathVar != null) {
            for (String d : pathVar.split(java.io.File.pathSeparator)) {
                if (!d.isBlank()) {
                    dirs.add(Path.of(d));
                }
            }
        }
        if (windows) {
            if (appData != null) {
                dirs.add(Path.of(appData, "npm"));
            }
            if (localAppData != null) {
                dirs.add(Path.of(localAppData, "Microsoft", "WinGet", "Links"));
                dirs.add(Path.of(localAppData, "Programs", "copilot"));
            }
        } else {
            dirs.add(Path.of("/opt/homebrew/bin"));
            dirs.add(Path.of("/usr/local/bin"));
            dirs.add(home.resolve(".local/bin"));
            dirs.add(home.resolve(".npm-global/bin"));
            dirs.addAll(nvm(home));
            dirs.add(Path.of("/usr/bin"));
        }
        List<Path> out = new ArrayList<>();
        for (Path d : dirs) {
            for (String n : names) {
                Path p = d.resolve(n);
                if (!out.contains(p)) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** nvm's Node versions, newest first. */
    private static List<Path> nvm(Path home) {
        Path root = home.resolve(".nvm/versions/node");
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.sorted(Comparator.comparing(CopilotLocator::version).reversed()).map(p -> p.resolve("bin")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** "v22.22.3" as a number to sort by. */
    static long version(Path p) {
        String[] parts = p.getFileName().toString().replaceAll("^v", "").split("\\.");
        long v = 0;
        for (int i = 0; i < 3; i++) {
            long n = 0;
            if (i < parts.length) {
                try {
                    n = Long.parseLong(parts[i].replaceAll("\\D.*", ""));
                } catch (NumberFormatException e) {
                    n = 0;
                }
            }
            v = v * 100_000 + Math.min(n, 99_999);
        }
        return v;
    }

    /**
     * The environment to start it in: this one, with the program's own folder first on the PATH,
     * so that an npm install finds its {@code node} however Logsift was started.
     */
    public static Map<String, String> environment(Path cli) {
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        String key = env.keySet().stream().filter(k -> k.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
        Path dir = cli.toAbsolutePath().getParent();
        String old = env.getOrDefault(key, "");
        if (dir != null) {
            env.put(key, dir + (old.isEmpty() ? "" : java.io.File.pathSeparator + old));
        }
        env.put("COPILOT_TELEMETRY_DISABLED", "true"); // Logsift sends nothing of its own either
        return env;
    }

    static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
