package logsift.crash;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Names for addresses in a Windows dump: {@code module!function+0x1f}, from a PDB when one is
 * found, else from the module's exported names (when the export starts the very function the
 * address is in), else {@code module+0x1a2b}. Also gives each module's image for the stack walk.
 */
public final class Symbols implements StackWalk.Images {

    /** A resolved address. */
    public record Name(String module, String function, long offset, String file, int line, boolean exact) {
        /** "kernelbase!RaiseException+0x6c", "app+0x1234". */
        public String text() {
            StringBuilder b = new StringBuilder();
            b.append(module == null ? "?" : module);
            if (function != null) {
                b.append('!').append(function);
            }
            if (offset != 0 || function == null) {
                b.append(function == null ? "+0x" : "+0x").append(Long.toHexString(offset));
            }
            return b.toString();
        }

        public String where() {
            return file == null ? null : file + (line > 0 ? ":" + line : "");
        }
    }

    private final Minidump dump;
    private final List<Path> folders;
    private final Map<Long, PeImage> images = new HashMap<>();
    private final Map<Long, Pdb> pdbs = new HashMap<>();
    private final Map<Long, String> pdbState = new HashMap<>();
    private final PdbFinder finder;

    /**
     * @param folders  extra places to look for program files and PDBs (the dump's own folder is
     *                 always looked in)
     * @param finder   finds PDBs (local folders, symbol caches, a download if allowed); null: none
     */
    public Symbols(Minidump dump, List<Path> folders, PdbFinder finder) {
        this.dump = dump;
        List<Path> f = new ArrayList<>();
        Path dir = dump.path().toAbsolutePath().getParent();
        if (dir != null) {
            f.add(dir);
        }
        if (folders != null) {
            f.addAll(folders);
        }
        this.folders = f;
        this.finder = finder;
    }

    /** Finds the PDB of a module; null if it can't. */
    public interface PdbFinder {
        Path find(Minidump.Module m, List<Path> folders);
    }

    /** The module's short name without .dll/.exe, as debuggers write it: "kernelbase". */
    public static String shortName(Minidump.Module m) {
        String n = m.name();
        String l = n.toLowerCase(Locale.ROOT);
        for (String ext : new String[] {".dll", ".exe", ".sys", ".drv", ".ocx"}) {
            if (l.endsWith(ext)) {
                return n.substring(0, n.length() - ext.length());
            }
        }
        return n;
    }

    @Override
    public synchronized PeImage image(Minidump.Module m) {
        if (images.containsKey(m.base())) {
            return images.get(m.base());
        }
        PeImage img = PeImage.inDump(dump, m.base());
        if (img == null || !img.hasUnwindTable() && dump.system().arch() == Minidump.Arch.AMD64) {
            PeImage file = fromDisk(m);
            if (file != null) {
                img = file;
            }
        }
        images.put(m.base(), img);
        return img;
    }

    /** The module's file: where it was loaded from (on this machine), or in a folder looked in. */
    private PeImage fromDisk(Minidump.Module m) {
        List<Path> tries = new ArrayList<>();
        try {
            Path p = Path.of(m.path());
            if (p.isAbsolute()) {
                tries.add(p);
            }
        } catch (RuntimeException e) {
            // not a path here (a Windows path on another system)
        }
        for (Path f : folders) {
            tries.add(f.resolve(m.name()));
        }
        for (Path p : tries) {
            try {
                if (Files.isRegularFile(p) && Files.size(p) < (512L << 20)) {
                    PeImage img = PeImage.fromFile(p);
                    if (img != null && sameBuild(img, m)) {
                        return img;
                    }
                }
            } catch (Exception e) {
                // try the next
            }
        }
        return null;
    }

    private static boolean sameBuild(PeImage img, Minidump.Module m) {
        return img.timeDateStamp() == m.timeDateStamp();
    }

    /** The PDB of a module, read once; null if none was found. */
    public synchronized Pdb pdb(Minidump.Module m) {
        if (pdbs.containsKey(m.base())) {
            return pdbs.get(m.base());
        }
        Pdb p = null;
        if (finder != null && m.pdbName() != null) {
            Path f = finder.find(m, folders);
            if (f != null) {
                try {
                    p = Pdb.open(f);
                    if (m.pdbId() != null && !m.pdbId().startsWith(p.guid())) {
                        pdbState.put(m.base(), "PDB doesn't match: " + f);
                        p = null;
                    } else {
                        pdbState.put(m.base(), "PDB: " + f);
                    }
                } catch (Exception e) {
                    pdbState.put(m.base(), "PDB can't be read: " + f + " (" + e.getMessage() + ")");
                }
            }
        }
        pdbs.put(m.base(), p);
        return p;
    }

    /** What is known about a module's names: "PDB: …", "exports only", … */
    public synchronized String symbolState(Minidump.Module m) {
        String s = pdbState.get(m.base());
        if (s != null) {
            return s;
        }
        PeImage img = images.get(m.base());
        return img != null && img.exportCount() > 0 ? "exported names only" : "no names";
    }

    /** Forgets the PDBs looked for, so a new search (more folders, a download) can find them. */
    public synchronized void forgetMissingPdbs() {
        pdbs.values().removeIf(java.util.Objects::isNull);
        pdbs.entrySet().removeIf(e -> e.getValue() == null);
    }

    /** Whether a frame is where every thread begins: nothing above it is real. */
    public boolean isThreadStart(long pc) {
        Name n = name(pc, true);
        if (n.module() == null || n.function() == null) {
            return false;
        }
        String f = n.function();
        return f.equals("RtlUserThreadStart") || f.equals("_RtlUserThreadStart") || f.equals("BaseProcessStart")
                || f.equals("BaseThreadStart") || f.equals("LdrInitializeThunk");
    }

    /** The name of an address; a return address is looked up one byte back (the call). */
    public Name name(long address, boolean returnAddress) {
        Minidump.Module m = dump.moduleAt(address);
        if (m == null) {
            Minidump.UnloadedModule u = dump.unloadedAt(address);
            if (u != null) {
                return new Name(u.name() + " (unloaded)", null, address - u.base(), null, 0, false);
            }
            return new Name(null, null, address, null, 0, false);
        }
        String mod = shortName(m);
        long rva = address - m.base();
        long look = returnAddress && rva > 0 ? rva - 1 : rva;
        Pdb pdb = pdb(m);
        if (pdb != null) {
            Pdb.Symbol s = pdb.symbolAt(look);
            if (s != null) {
                Pdb.Line line = pdb.lineAt(look);
                return new Name(mod, s.name(), rva - s.rva(), line == null ? null : line.file(),
                        line == null ? 0 : line.line(), true);
            }
        }
        PeImage img = image(m);
        if (img != null) {
            Object[] e = img.exportBefore(look);
            if (e != null) {
                long eRva = (long) e[0];
                PeImage.Function f = img.hasUnwindTable() ? img.functionAt(look) : null;
                PeImage.Function fe = f == null ? null : img.functionAt(eRva);
                // only when the export begins the function holding the address (or nothing says otherwise)
                boolean same = f == null ? rva - eRva < 0x4000 : fe != null && fe.begin() == f.begin()
                        || f.begin() == eRva;
                if (same) {
                    return new Name(mod, (String) e[1], rva - eRva, null, 0, f != null);
                }
            }
        }
        return new Name(mod, null, rva, null, 0, false);
    }
}
