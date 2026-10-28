package logsift.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where an agent may read: under the given folders, never in places that hold keys and
 * passwords (~/.ssh, ~/.aws, key stores…), whatever the agent asks for. Links are followed
 * first, so a link can't lead out.
 */
public final class Scope {
    private final List<Path> roots = new ArrayList<>();
    private final Path home;

    /** Folders under home that hold credentials. */
    private static final List<String> SECRET_DIRS = List.of(".ssh", ".aws", ".gnupg", ".kube", ".azure",
            ".config/gcloud", ".config/gh", ".docker", ".password-store", ".gradle/gradle.properties",
            ".m2/settings.xml", ".m2/settings-security.xml", ".netrc", ".git-credentials", ".npmrc", ".pypirc",
            "Library/Keychains", ".logsift", ".logsift-beta");

    /** Names of files that hold keys. */
    private static final List<String> SECRET_SUFFIXES = List.of(".pem", ".key", ".p12", ".pfx", ".jks",
            ".keystore", ".kdbx", ".keychain", ".keychain-db", ".ppk", ".asc", ".gpg");

    public Scope(List<Path> roots, Path home) {
        this.home = real(home);
        for (Path r : roots) {
            this.roots.add(real(r));
        }
    }

    /** The folders reads are limited to. */
    public List<Path> roots() {
        return List.copyOf(roots);
    }

    /**
     * The file or folder as it really is (links followed), if an agent may read it.
     *
     * @throws IOException saying why not
     */
    public Path check(Path p) throws IOException {
        Path abs = p.toAbsolutePath().normalize();
        if (!Files.exists(abs)) {
            throw new IOException("Not found: " + abs);
        }
        Path r = real(abs);
        if (isSecret(r) || isSecret(abs)) {
            throw new IOException("Logsift doesn't give agents files that hold keys or passwords: " + abs);
        }
        for (Path root : roots) {
            if (r.startsWith(root)) {
                return r;
            }
        }
        throw new IOException("Outside the folders Logsift may read for agents (" + describe() + "): " + abs
                + ". Start logsift-mcp with --root <folder> to allow more.");
    }

    /** Whether a file found while walking a folder may be read (no exception). */
    public boolean allows(Path p) {
        Path r = real(p.toAbsolutePath().normalize());
        return !isSecret(r) && roots.stream().anyMatch(r::startsWith);
    }

    public String describe() {
        return String.join(", ", roots.stream().map(Path::toString).toList());
    }

    private boolean isSecret(Path p) {
        if (home != null && p.startsWith(home)) {
            Path rel = home.relativize(p);
            String s = rel.toString().replace('\\', '/');
            for (String d : SECRET_DIRS) {
                if (s.equals(d) || s.startsWith(d + "/")) {
                    return true;
                }
            }
        }
        Path name = p.getFileName();
        if (name == null) {
            return false;
        }
        String n = name.toString().toLowerCase(Locale.ROOT);
        if (n.startsWith("id_rsa") || n.startsWith("id_ed25519") || n.startsWith("id_ecdsa") || n.startsWith("id_dsa")
                || n.equals(".netrc") || n.equals(".git-credentials") || n.equals("credentials")
                || n.equals(".env") || n.startsWith(".env.")) {
            return true;
        }
        for (String suf : SECRET_SUFFIXES) {
            if (n.endsWith(suf)) {
                return true;
            }
        }
        return false;
    }

    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
