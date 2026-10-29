package logsift.crash;

import static logsift.crash.Scenario.APP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Function names, sizes and source lines from a PDB, and names in a dump from it. */
class PdbTest {
    @TempDir
    Path dir;

    private PdbWriter sample() {
        PdbWriter w = new PdbWriter();
        w.publics.add(new PdbWriter.Pub("?run@Worker@app@@QEAAXXZ", 1, 0x100, true));
        w.publics.add(new PdbWriter.Pub("_tick@8", 1, 0x300, false));
        w.publics.add(new PdbWriter.Pub("?table@@3PAHA", 2, 0x10, false)); // data: not a function
        w.procs.add(new PdbWriter.Proc("app::Order::total", 1, 0x200, 0x40));
        w.linesOffset = 0x200;
        w.lines.add(new PdbWriter.LineRow(0, 141));
        w.lines.add(new PdbWriter.LineRow(0x10, 142));
        return w;
    }

    @Test
    void namesSizesAndLines() throws Exception {
        try (Pdb p = Pdb.open(sample().write(dir.resolve("app.pdb")))) {
            assertEquals("0123456789ABCDEF0123456789ABCDEF", p.guid());
            assertEquals(1, p.age());
            assertEquals(3, p.symbolCount(), "two code publics and a function");
            Pdb.Symbol run = p.symbolAt(0x1150);
            assertEquals("app::Worker::run", run.name(), "undecorated");
            assertEquals(0x1100, run.rva());
            Pdb.Symbol total = p.symbolAt(0x1210);
            assertEquals("app::Order::total", total.name());
            assertEquals(0x40, total.size());
            assertNull(p.symbolAt(0x1250), "past the end of the function");
            assertEquals("tick", p.symbolAt(0x1300).name(), "a code section public, stdcall decoration removed");
            assertNull(p.symbolAt(0x0fff));
            assertTrue(p.hasLines());
            assertEquals(new Pdb.Line("C:\\src\\order.cpp", 141), p.lineAt(0x1204));
            assertEquals(new Pdb.Line("C:\\src\\order.cpp", 142), p.lineAt(0x1215));
            assertNull(p.lineAt(0x1150), "the nearest line is in another function");
        }
    }

    @Test
    void namesInADumpFromThePdb() throws Exception {
        Path pdb = sample().write(dir.resolve("app.pdb"));
        try (Minidump d = Minidump.open(Scenario.standard().write(dir.resolve("t.dmp")))) {
            Symbols s = new Symbols(d, List.of(), (m, folders) -> m.name().equals("app.exe") ? pdb : null);
            Symbols.Name n = s.name(APP + 0x1215, false);
            assertEquals("app!app::Order::total+0x15", n.text());
            assertEquals("C:\\src\\order.cpp:142", n.where());
            assertTrue(s.symbolState(d.mainModule()).startsWith("PDB: "), s.symbolState(d.mainModule()));
        }
    }

    @Test
    void aPdbOfAnotherBuildIsNotUsed() throws Exception {
        PdbWriter w = sample();
        w.guid = new byte[16];
        Path pdb = w.write(dir.resolve("app.pdb"));
        try (Minidump d = Minidump.open(Scenario.standard().write(dir.resolve("t.dmp")))) {
            Symbols s = new Symbols(d, List.of(), (m, folders) -> m.name().equals("app.exe") ? pdb : null);
            assertEquals("app+0x1215", s.name(APP + 0x1215, false).text());
            assertTrue(s.symbolState(d.mainModule()).startsWith("PDB doesn't match"));
        }
    }

    @Test
    void notAPdb() throws Exception {
        Path f = dir.resolve("x.pdb");
        Files.write(f, new byte[1024]);
        assertThrows(IOException.class, () -> Pdb.open(f));
    }

    @Test
    void undecoratedNames() {
        assertEquals("app::Worker::run", Pdb.undecorate("?run@Worker@app@@QEAAXXZ"));
        assertEquals("Worker::Worker", Pdb.undecorate("??0Worker@@QEAA@XZ"));
        assertEquals("Worker::~Worker", Pdb.undecorate("??1Worker@@QEAA@XZ"));
        assertEquals("??_GWorker@@UEAAPEAXI@Z", Pdb.undecorate("??_GWorker@@UEAAPEAXI@Z"), "special names stay");
        assertEquals("?push@?$vector@H@std@@QEAAXH@Z", Pdb.undecorate("?push@?$vector@H@std@@QEAAXH@Z"), "templates stay");
        assertEquals("WinMain", Pdb.undecorate("_WinMain@16"));
        assertEquals("fast", Pdb.undecorate("@fast@8"));
        assertEquals("memcpy", Pdb.undecorate("memcpy"));
        assertEquals("", Pdb.undecorate(""));
    }
}
