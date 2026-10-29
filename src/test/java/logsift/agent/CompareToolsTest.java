package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Comparing for agents: tables by key, texts line by line, folders, and a bad run against a good one. */
class CompareToolsTest {

    @TempDir
    Path dir;
    Path files;
    Workspace w;

    @BeforeEach
    void start() throws Exception {
        files = Files.createDirectories(dir.resolve("files"));
        Path home = Files.createDirectories(dir.resolve("home"));
        w = new Workspace(new Workspace.Options(new Scope(List.of(files), home), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 25_000, List.of(), null));
    }

    @AfterEach
    void stop() {
        w.close();
    }

    private String run(Tool t, Object... kv) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return t.run(new Tool.Args(m), w);
    }

    @Test
    void tablesAreMatchedByAKeyWhateverTheOrder() throws Exception {
        Path a = files.resolve("stock-old.csv");
        Path b = files.resolve("stock-new.csv");
        Files.writeString(a, "sku,name,stock\nA-1,Tea,10\nA-2,Coffee,5\nA-3,Cake,7\nA-4,Milk,2\n");
        Files.writeString(b, "sku,name,stock\nA-3,Cake,7\nA-1,Tea,12\nA-5,Sugar,9\nA-2,Coffee,5\n");
        String t = run(CompareTools.COMPARE_FILES, "left", a.toString(), "right", b.toString());
        assertTrue(t.startsWith("Tables: Compared stock-old.csv (4 rows) with stock-new.csv (4 rows), by key sku."), t);
        assertTrue(t.contains("Added: 1   Removed: 1   Changed: 1   Same: 2"), t);
        assertTrue(t.contains("(suggested: "), "which key, and why: " + t);
        assertTrue(t.contains("- key A-1, line 2 → 3: stock: 10 → 12"), t);
        assertTrue(t.contains("+ line 4: A-5, Sugar, 9"), t);
        assertTrue(t.contains("- line 5: A-4, Milk, 2"), t);

        String byName = run(CompareTools.COMPARE_FILES, "left", a.toString(), "right", b.toString(), "keys", "name");
        assertTrue(byName.contains("by key name (the key asked for)"), byName);
        Tool.Failure f = assertThrows(Tool.Failure.class, () -> run(CompareTools.COMPARE_FILES, "left", a.toString(),
                "right", b.toString(), "keys", "nope"));
        assertTrue(f.getMessage().startsWith("No column nope in both files. The columns: sku, name, stock"),
                f.getMessage());
        String same = run(CompareTools.COMPARE_FILES, "left", a.toString(), "right", a.toString());
        assertTrue(same.endsWith("The tables are the same, row for row."), same);
    }

    @Test
    void textsLineByLineWithTimesMasked() throws Exception {
        Path a = files.resolve("run1.log");
        Path b = files.resolve("run2.log");
        Files.writeString(a, "2026-10-28 10:00:00 INFO start\n2026-10-28 10:00:01 INFO load config\n"
                + "2026-10-28 10:00:02 INFO ready\n");
        Files.writeString(b, "2026-10-29 11:30:00 INFO start\n2026-10-29 11:30:01 WARN load config: missing key\n"
                + "2026-10-29 11:30:02 INFO ready\n2026-10-29 11:30:03 INFO extra\n");
        String plain = run(CompareTools.COMPARE_FILES, "left", a.toString(), "right", b.toString());
        assertTrue(plain.contains("4 lines changed") || plain.contains("lines changed"), plain);
        String masked = run(CompareTools.COMPARE_FILES, "left", a.toString(), "right", b.toString(), "mask_times", true);
        assertTrue(masked.startsWith("Text: run1.log (3 lines) with run2.log (4 lines), times masked."), masked);
        assertTrue(masked.contains("1 lines changed, 1 added, 0 removed, in 2 places"), masked);
        assertTrue(masked.contains("@ left line 2, right line 2 (changed)\n- 2026-10-28 10:00:01 INFO load config\n"
                + "+ 2026-10-29 11:30:01 WARN load config: missing key"), masked);
        assertTrue(masked.contains("@ left after line 3, right line 4 (added)\n+ 2026-10-29 11:30:03 INFO extra"), masked);

        Path bin1 = files.resolve("a.bin");
        Path bin2 = files.resolve("b.bin");
        Files.write(bin1, new byte[] {0, 1, 2, 3});
        Files.write(bin2, new byte[] {0, 1, 2, 4});
        assertTrue(run(CompareTools.COMPARE_FILES, "left", bin1.toString(), "right", bin2.toString())
                .startsWith("Binary files: they differ"));
    }

    @Test
    void foldersFileByFile() throws Exception {
        Path a = Files.createDirectories(files.resolve("release-1.0"));
        Path b = Files.createDirectories(files.resolve("release-1.1"));
        for (Path p : List.of(a, b)) {
            Files.createDirectories(p.resolve("conf"));
            Files.writeString(p.resolve("README.txt"), "the app\n");
        }
        Files.writeString(a.resolve("conf/app.conf"), "threads = 8\n");
        Files.writeString(b.resolve("conf/app.conf"), "threads = 16\n");
        Files.writeString(a.resolve("conf/legacy.conf"), "old\n");
        Files.writeString(b.resolve("conf/feature.conf"), "new\n");
        String t = run(CompareTools.COMPARE_FOLDERS, "left", a.toString(), "right", b.toString());
        assertTrue(t.startsWith("4 files: 1 the same, 1 different, 1 only in release-1.0, 1 only in release-1.1."), t);
        assertTrue(t.contains("≠ conf/app.conf  12 B"), t);
        assertTrue(t.contains("▶ conf/feature.conf  only right"), t);
        assertTrue(t.contains("◀ conf/legacy.conf  only left"), t);
        assertThrows(Tool.Failure.class, () -> run(CompareTools.COMPARE_FOLDERS, "left", a.resolve("README.txt").toString(),
                "right", b.toString()));
    }

    @Test
    void aBadRunAgainstAGoodOne() throws Exception {
        Path good = files.resolve("good.log");
        Path bad = files.resolve("bad.log");
        StringBuilder g = new StringBuilder();
        StringBuilder x = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            String t = String.format("2026-10-28 10:%02d:%02d.000", i / 60, i % 60);
            g.append(t).append(" INFO  [main] c.a.Orders - order ").append(1000 + i).append(" placed\n");
            x.append(t).append(" INFO  [main] c.a.Orders - order ").append(2000 + i).append(" placed\n");
            if (i % 10 == 0) {
                x.append(t).append(" ERROR [main] c.a.Pay - card declined for order ").append(2000 + i).append('\n');
            }
            if (i % 20 == 0) {
                g.append(t).append(" INFO  [main] c.a.Cache - cache warmed in ").append(i).append(" ms\n");
            }
        }
        Files.writeString(good, g);
        Files.writeString(bad, x);
        String t = run(CompareTools.COMPARE_LOGS, "bad", bad.toString(), "good", good.toString());
        assertTrue(t.startsWith("Bad run log:1 ("), t);
        assertTrue(t.contains("New in the bad run (1):\n- 20 × in bad, 0 × in good: ERROR "), t);
        assertTrue(t.contains("card declined for order"), t);
        assertTrue(t.contains("(first: bad line 2)"), t);
        assertTrue(t.contains("Gone from the bad run (1):"), t);
        assertTrue(t.contains("cache warmed in"), t);
        assertFalse(t.contains("order … placed  (first"), "the same kind isn't listed: " + t);
    }

    @Test
    void outsideTheFoldersAndMissingArgumentsAreRefused() throws Exception {
        Path outside = Files.writeString(dir.resolve("secret.csv"), "a,b\n1,2\n");
        Path inside = Files.writeString(files.resolve("in.csv"), "a,b\n1,2\n");
        Exception e = assertThrows(Exception.class, () -> run(CompareTools.COMPARE_FILES, "left", outside.toString(),
                "right", inside.toString()));
        assertTrue(e.getMessage().startsWith("Outside the folders"), e.getMessage());
        assertThrows(Exception.class, () -> run(CompareTools.COMPARE_FOLDERS, "left", dir.toString(), "right",
                files.toString()));
        Tool.Failure f = assertThrows(Tool.Failure.class, () -> run(CompareTools.COMPARE_FILES, "left", inside.toString()));
        assertEquals("Missing argument: right", f.getMessage());
        assertThrows(Tool.Failure.class, () -> run(CompareTools.COMPARE_LOGS, "bad", inside.toString()));
        assertEquals(List.of("compare_files", "compare_folders", "compare_logs"),
                CompareTools.all().stream().map(Tool::name).toList());
    }
}
