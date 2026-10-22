package logsift.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * async-profiler, packed into Logsift for Linux (x64, arm64) and macOS: its launcher
 * {@code asprof} (which also runs diagnostic commands in a JVM, like {@code jcmd}, without a JDK)
 * and its library. Copied to this computer or to a server when needed.
 */
public final class Agents {
    /** The async-profiler version packed in (see build.gradle.kts). */
    public static final String VERSION = "4.5";

    private Agents() {
    }

    /**
     * The platform name for {@code uname -s} and {@code uname -m}: "linux-x64", "linux-arm64",
     * "macos", or null where async-profiler doesn't run (Windows, other CPUs).
     */
    public static String platform(String os, String cpu) {
        String o = os.trim().toLowerCase(Locale.ROOT);
        String c = cpu.trim().toLowerCase(Locale.ROOT);
        if (o.startsWith("darwin") || o.startsWith("mac")) {
            return "macos";
        }
        if (o.startsWith("linux")) {
            if (c.equals("x86_64") || c.equals("amd64")) {
                return "linux-x64";
            }
            if (c.equals("aarch64") || c.equals("arm64")) {
                return "linux-arm64";
            }
        }
        return null;
    }

    /** This computer's platform, or null (Windows). */
    public static String localPlatform() {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** The library's file name on a platform. */
    public static String library(String platform) {
        return platform.equals("macos") ? "libasyncProfiler.dylib" : "libasyncProfiler.so";
    }

    /** The files of a platform: the launcher (bin/asprof) and the library (lib/…). */
    public static List<String> files(String platform) {
        return List.of("bin/asprof", "lib/" + library(platform));
    }

    /** True if this build has async-profiler for the platform. */
    public static boolean has(String platform) {
        return platform != null && Agents.class.getResource("/logsift/agents/" + platform + "/asprof") != null;
    }

    /** One of a platform's files ({@code bin/asprof} or {@code lib/…}), from inside Logsift. */
    public static InputStream open(String platform, String file) throws IOException {
        String name = file.substring(file.lastIndexOf('/') + 1);
        InputStream in = Agents.class.getResourceAsStream("/logsift/agents/" + platform + "/" + name);
        if (in == null) {
            throw new IOException("this Logsift has no async-profiler for " + platform);
        }
        return in;
    }

    /** The size of a packed file (to see if a copy is complete). */
    public static long size(String platform, String file) throws IOException {
        try (InputStream in = open(platform, file)) {
            return in.transferTo(java.io.OutputStream.nullOutputStream());
        }
    }

    /**
     * {@code asprof} for this computer, copied out of Logsift into its folder
     * ({@code ~/.logsift/agents/4.5/macos/bin/asprof}); null where async-profiler doesn't run.
     */
    public static synchronized Path localAsprof() throws IOException {
        String p = localPlatform();
        if (!has(p)) {
            return null;
        }
        String home = System.getProperty("logsift.home");
        Path base = (home != null ? Path.of(home) : Path.of(System.getProperty("user.home"), ".logsift"))
                .resolve("agents").resolve(VERSION).resolve(p);
        for (String f : files(p)) {
            Path target = base.resolve(f);
            if (!Files.isRegularFile(target) || Files.size(target) != size(p, f)) {
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                try (InputStream in = open(p, f)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            target.toFile().setExecutable(true, false);
        }
        return base.resolve("bin/asprof");
    }
}
