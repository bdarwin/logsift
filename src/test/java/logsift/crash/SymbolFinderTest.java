package logsift.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where PDBs are looked for; never the network here (downloads off). */
class SymbolFinderTest {
    @TempDir
    Path dir;

    private static final String ID = "0123456789ABCDEF0123456789ABCDEF1";

    private static Minidump.Module module(String path, String pdb) {
        return new Minidump.Module(0x140000000L, 0x5000, 0, 0, path, "", pdb, ID);
    }

    @Test
    void localFoldersOfASymbolPath() {
        assertEquals(List.of(Path.of("C:\\sym"), Path.of("D:\\cache"), Path.of("E:\\plain")),
                SymbolFinder.localDirs("srv*C:\\sym*https://msdl.microsoft.com/download/symbols;cache*D:\\cache;"
                        + "E:\\plain;https://symbols.example.com;symsrv*symsrv.dll*https://x"));
        assertEquals(List.of(), SymbolFinder.localDirs(null));
        assertEquals(List.of(), SymbolFinder.localDirs("  "));
        assertEquals("app.pdb", SymbolFinder.baseName("D:\\build\\Release\\app.pdb"));
        assertEquals("app.pdb", SymbolFinder.baseName("/build/app.pdb"));
    }

    @Test
    void nextToTheDumpInAFolderOrInASymbolCache() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("pdbs"));
        Path cache = dir.resolve("cache");
        SymbolFinder f = new SymbolFinder(cache, false, null, null);
        Minidump.Module app = module("C:\\app\\app.exe", "D:\\build\\app.pdb");
        assertNull(f.find(app, List.of(folder)));
        Path plain = Files.writeString(folder.resolve("app.pdb"), "x");
        assertEquals(plain, f.find(app, List.of(folder)));
        Files.delete(plain);
        // a symbol server's layout in a folder: name/ID/name
        Path layout = Files.createDirectories(folder.resolve("app.pdb").resolve(ID)).resolve("app.pdb");
        Files.writeString(layout, "x");
        assertEquals(layout, f.find(app, List.of(folder)));
        // Logsift's cache and _NT_SYMBOL_PATH folders use the same layout
        Path inCache = Files.createDirectories(cache.resolve("ntdll.pdb").resolve(ID)).resolve("ntdll.pdb");
        Files.writeString(inCache, "x");
        Minidump.Module nt = module("C:\\Windows\\System32\\ntdll.dll", "ntdll.pdb");
        assertEquals(inCache, f.find(nt, List.of()));
        Path sym = dir.resolve("sym");
        Path inSym = Files.createDirectories(sym.resolve("kernel32.pdb").resolve(ID)).resolve("kernel32.pdb");
        Files.writeString(inSym, "x");
        SymbolFinder withPath = new SymbolFinder(null, false, "srv*" + sym + "*https://msdl.microsoft.com/download/symbols",
                null);
        assertEquals(inSym, withPath.find(module("C:\\Windows\\System32\\kernel32.dll", "kernel32.pdb"), List.of()));
        // no PDB named, nothing to find; a Windows module with downloads off isn't fetched
        assertNull(f.find(module("C:\\x.dll", null), List.of(folder)));
        assertNull(f.find(module("C:\\Windows\\System32\\user32.dll", "user32.pdb"), List.of()));
        assertEquals(0, f.downloaded());
        assertNull(f.lastProblem());
    }
}
