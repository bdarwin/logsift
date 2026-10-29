package logsift.crash;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import logsift.core.UrlFetch;

/**
 * Finds the PDB of a module: where the build put it (on this machine), next to the dump, in the
 * folders chosen, in symbol caches ({@code _NT_SYMBOL_PATH}, Logsift's own), and, when allowed,
 * downloads Windows' own from Microsoft's symbol server. Only Windows' modules are ever asked
 * for, so the names of the program's own files never leave the computer.
 */
public final class SymbolFinder implements Symbols.PdbFinder {

    public static final String MICROSOFT = "https://msdl.microsoft.com/download/symbols";

    private final Path cache;
    private final boolean download;
    private final BooleanSupplier cancelled;
    private final List<Path> caches = new ArrayList<>();
    private final Set<String> missing = new HashSet<>();
    private volatile String lastProblem;
    private int downloaded;

    /**
     * @param cache     Logsift's symbol cache (downloads go there); null: none
     * @param download  whether Windows' PDBs may be downloaded from Microsoft
     * @param symbolPath the {@code _NT_SYMBOL_PATH} value, or null
     */
    public SymbolFinder(Path cache, boolean download, String symbolPath, BooleanSupplier cancelled) {
        this.cache = cache;
        this.download = download && cache != null;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        if (cache != null) {
            caches.add(cache);
        }
        caches.addAll(localDirs(symbolPath));
    }

    /**
     * The local folders of a symbol path: {@code srv*C:\sym*https://…} gives C:\sym,
     * {@code cache*C:\c} gives C:\c, a plain folder is itself. Servers are never used from it.
     */
    static List<Path> localDirs(String symbolPath) {
        List<Path> out = new ArrayList<>();
        if (symbolPath == null || symbolPath.isBlank()) {
            return out;
        }
        for (String part : symbolPath.split(";")) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            String[] bits = p.split("\\*");
            String first = bits[0].toLowerCase(java.util.Locale.ROOT);
            if (first.equals("srv") || first.equals("symsrv") || first.equals("cache")) {
                for (int i = 1; i < bits.length; i++) {
                    String b = bits[i];
                    if (!b.isEmpty() && !b.contains("://") && !b.toLowerCase(java.util.Locale.ROOT).endsWith(".dll")) {
                        add(out, b);
                    }
                }
            } else if (!p.contains("://")) {
                add(out, p);
            }
        }
        return out;
    }

    private static void add(List<Path> out, String s) {
        try {
            out.add(Path.of(s));
        } catch (RuntimeException e) {
            // not a path on this system
        }
    }

    /** The PDB file's name: the last part of the path the build wrote. */
    static String baseName(String pdbPath) {
        int i = Math.max(pdbPath.lastIndexOf('\\'), pdbPath.lastIndexOf('/'));
        return pdbPath.substring(i + 1);
    }

    @Override
    public Path find(Minidump.Module m, List<Path> folders) {
        if (m.pdbName() == null || m.pdbName().isBlank()) {
            return null;
        }
        String name = baseName(m.pdbName());
        String id = m.pdbId();
        List<Path> tries = new ArrayList<>();
        try {
            Path built = Path.of(m.pdbName());
            if (built.isAbsolute()) {
                tries.add(built);
            }
        } catch (RuntimeException e) {
            // a Windows path elsewhere
        }
        for (Path f : folders) {
            tries.add(f.resolve(name));
            if (id != null) {
                tries.add(f.resolve(name).resolve(id).resolve(name));
            }
        }
        for (Path c : caches) {
            if (id != null) {
                tries.add(c.resolve(name).resolve(id).resolve(name));
            }
        }
        for (Path p : tries) {
            try {
                if (Files.isRegularFile(p)) {
                    return p;
                }
            } catch (RuntimeException e) {
                // next
            }
        }
        if (download && id != null && m.isSystem() && safe(name) && safe(id)) {
            return fetch(name, id);
        }
        return null;
    }

    private static boolean safe(String s) {
        return s.matches("[A-Za-z0-9._-]{1,200}") && !s.startsWith(".");
    }

    private synchronized Path fetch(String name, String id) {
        String key = name + "/" + id;
        Path target = cache.resolve(name).resolve(id).resolve(name);
        if (Files.isRegularFile(target)) {
            return target;
        }
        if (missing.contains(key) || cancelled.getAsBoolean()) {
            return null;
        }
        try {
            Path tmp = Files.createDirectories(cache.resolve(".download"));
            Path got = UrlFetch.download(URI.create(MICROSOFT + "/" + name + "/" + id + "/" + name), null, tmp, null,
                    cancelled);
            if (got == null) {
                return null;
            }
            Files.createDirectories(target.getParent());
            Files.move(got, target, StandardCopyOption.REPLACE_EXISTING);
            downloaded++;
            return target;
        } catch (IOException | RuntimeException e) {
            missing.add(key);
            lastProblem = e.getMessage();
            return null;
        }
    }

    /** How many PDBs were downloaded. */
    public synchronized int downloaded() {
        return downloaded;
    }

    /** The last download that failed, and why; null if none did. */
    public String lastProblem() {
        return lastProblem;
    }
}
