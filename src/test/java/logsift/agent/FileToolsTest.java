package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** list_files, profile_columns, merge_tables and show_entry as an agent calls them. */
class FileToolsTest {

    @TempDir
    Path dir;
    Path logs;
    Workspace w;

    @BeforeEach
    void start() throws IOException {
        logs = Files.createDirectories(dir.resolve("logs"));
        Path home = Files.createDirectories(dir.resolve("home"));
        w = new Workspace(new Workspace.Options(new Scope(List.of(logs), home), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 30_000, List.of(), null));
    }

    @AfterEach
    void stop() {
        w.close();
    }

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private String run(Tool t, Object... kv) throws Exception {
        return t.run(new Tool.Args(args(kv)), w);
    }

    private String fail(Tool t, Object... kv) {
        return assertThrows(Tool.Failure.class, () -> run(t, kv)).getMessage();
    }

    @Test
    void theToolsAreOfferedAndOnlyRead() {
        List<String> names = LogTools.all().stream().map(Tool::name).toList();
        for (String n : List.of("list_files", "profile_columns", "merge_tables", "show_entry")) {
            assertTrue(names.contains(n), n);
        }
        for (Tool t : FileTools.all()) {
            assertTrue(t.readOnly(), t.name());
            assertTrue(t.description().length() > 300, "a description a smaller model can follow: " + t.name());
        }
    }

    // ---------------------------------------------------------------- list_files

    private void files() throws IOException {
        Files.writeString(logs.resolve("app.log"), "2026-11-04 10:00:00 INFO started\n");
        Files.writeString(logs.resolve("orders.csv"), "id,amount\n1,10\n");
        Path gc = Files.createDirectories(logs.resolve("gc"));
        Files.writeString(gc.resolve("gc.log"), "[0.010s][info][gc] Using G1\n"
                + "[1.234s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 24M->4M(256M) 3.210ms\n"
                + "[2.345s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 30M->5M(256M) 2.100ms\n");
        Files.writeString(logs.resolve("threads.txt"), """
                2026-11-04 10:00:00
                Full thread dump OpenJDK 64-Bit Server VM (21.0.1+12 mixed mode):

                "main" #1 prio=5 os_prio=31 cpu=10.00ms elapsed=1.00s tid=0x1 nid=0x2 runnable  [0x3]
                   java.lang.Thread.State: RUNNABLE
                \tat java.lang.Thread.sleep(Native Method)
                """);
        Files.write(logs.resolve("heap.hprof"), "JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.ISO_8859_1));
        Files.setLastModifiedTime(logs.resolve("app.log"), FileTime.fromMillis(3_000_000_000_000L));
        Files.setLastModifiedTime(logs.resolve("orders.csv"), FileTime.fromMillis(1_000_000_000_000L));
    }

    @Test
    void listsAFolderWithWhatEachFileIsAndTheToolForIt() throws Exception {
        files();
        String r = run(FileTools.LIST_FILES, "path", logs.toString());
        assertTrue(r.startsWith(logs.toRealPath() + ": 5 files"), r);
        assertTrue(r.contains("- app.log ") && r.contains("log → open_log"), r);
        assertTrue(r.contains("- gc/gc.log ") && r.contains("GC log → analyze_gc"), r);
        assertTrue(r.contains("thread dump → analyze_threads"), r);
        assertTrue(r.contains("heap dump → analyze_heap_dump"), r);
        assertTrue(r.contains("table (CSV) → open_log, then profile_columns"), r);
        // by path: gc/ after app.log, before heap.hprof
        assertTrue(r.indexOf("- app.log") < r.indexOf("- gc/gc.log") && r.indexOf("- gc/gc.log") < r.indexOf("- heap.hprof"), r);
    }

    @Test
    void aPatternSortAndLimitNarrowTheList() throws Exception {
        files();
        String logsOnly = run(FileTools.LIST_FILES, "path", logs.toString(), "pattern", "*.LOG");
        assertTrue(logsOnly.startsWith(logs.toRealPath() + ": 2 files"), "case doesn't matter, subfolders count: " + logsOnly);
        String below = run(FileTools.LIST_FILES, "path", logs.toString(), "pattern", "gc/*.log");
        assertTrue(below.contains("1 file") && below.contains("gc/gc.log"), below);
        String braces = run(FileTools.LIST_FILES, "path", logs.toString(), "pattern", "*.{csv,hprof}");
        assertTrue(braces.contains("2 files") && braces.contains("orders.csv") && braces.contains("heap.hprof"), braces);
        String newest = run(FileTools.LIST_FILES, "path", logs.toString(), "sort", "newest", "limit", 1);
        assertTrue(newest.contains("the first 1 newest") && newest.contains("- app.log") && newest.contains("4 more"),
                newest);
        String none = run(FileTools.LIST_FILES, "path", logs.toString(), "pattern", "*.nothing");
        assertTrue(none.contains("No file matches the pattern"), none);
        assertTrue(fail(FileTools.LIST_FILES, "path", logs.toString(), "pattern", "*.{log").contains("{ without a }"));
        assertTrue(fail(FileTools.LIST_FILES, "path", logs.toString(), "limit", 0).contains("limit must be between"));
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void listsAZipAndTheZipsInsideItWithPathsTheOtherToolsOpen() throws Exception {
        byte[] inner = zip(Map.of("x.csv", "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> outer = new LinkedHashMap<>();
        outer.put("logs/app.log", "2026-11-04 10:00:00 ERROR boom\n".getBytes(StandardCharsets.UTF_8));
        outer.put("dumps/core.hprof", new byte[] {1, 2});
        outer.put("inner.zip", inner);
        Path z = Files.write(logs.resolve("bundle.zip"), zip(outer)).toRealPath();

        String r = run(FileTools.LIST_FILES, "path", z.toString());
        assertTrue(r.startsWith(z + " (zip): 4 files"), r);
        assertTrue(r.contains("- logs/app.log ") && r.contains("open_log with path=\"" + z.resolve("logs/app.log") + "\""), r);
        assertTrue(r.contains("- inner.zip › x.csv ") && r.contains(z.resolve("inner.zip/x.csv").toString()), r);
        assertTrue(r.contains("heap dump → analyze_heap_dump"), r);
        assertTrue(r.contains("zip → list_files"), r);

        // the path shown opens the file
        String opened = LogTools.OPEN_LOG.run(new Tool.Args(args("path", z.resolve("logs/app.log").toString())), w);
        assertTrue(opened.contains("log:1"), opened);

        String nested = run(FileTools.LIST_FILES, "path", z.resolve("inner.zip").toString());
        assertTrue(nested.contains("1 file") && nested.contains("- x.csv"), nested);
        assertTrue(fail(FileTools.LIST_FILES, "path", z.resolve("other.zip").toString()).contains("list_files(path="));
    }

    @Test
    void saysWhatToDoWithAFileATarOrNothing() throws Exception {
        files();
        assertTrue(fail(FileTools.LIST_FILES, "path", logs.resolve("gc/gc.log").toString())
                .contains("a file, not a folder or zip. Open it with analyze_gc"));
        Files.write(logs.resolve("old.tar.gz"), new byte[] {1});
        assertTrue(fail(FileTools.LIST_FILES, "path", logs.resolve("old.tar.gz").toString()).contains("not .tar"));
        assertTrue(fail(FileTools.LIST_FILES, "path", logs.resolve("gone").toString()).contains("Nothing at"));
        ToolCall outside = ToolCall.run(FileTools.LIST_FILES, new Tool.Args(args("path", dir.toString())), w);
        assertTrue(outside.failed() && !outside.text().contains("home"), "outside the folders agents may read: "
                + outside.text());
        assertTrue(fail(FileTools.LIST_FILES).contains("Missing argument: path"));
    }

    @Test
    void kindsFromNamesAndStarts() {
        assertEquals("crash file (hs_err)", FileTools.kind("hs_err_pid42.log", null));
        assertEquals("log (gzipped)", FileTools.kind("app.log.gz", null));
        assertEquals("table (CSV, gzipped)", FileTools.kind("sales.csv.gz", null));
        assertEquals("crash dump", FileTools.kind("x.bin", "MDMP....".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("profile", FileTools.kind("rec", "FLR\0abc".getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals("binary", FileTools.kind("blob.dat", new byte[100]));
        assertEquals("log", FileTools.kind("notes.txt", "hello\n".getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------- profile_columns

    private Path sales() throws IOException {
        StringBuilder b = new StringBuilder("time,region,status,ms,order_id,note\n");
        String[] regions = {"North", "North", "North", "South", "South", "East"};
        for (int i = 0; i < 60; i++) {
            b.append(String.format("2026-10-28 10:%02d:00,%s,%s,%d,ord-%04d,%s%n", i, regions[i % 6],
                    i % 10 == 0 ? "err" : "ok", 10 + i * 5, i, i % 3 == 0 ? "" : "note " + i));
        }
        return Files.writeString(logs.resolve("sales.csv"), b.toString());
    }

    @Test
    void profilesEveryColumnWithKindsCountsAndTopValues() throws Exception {
        Path f = sales();
        String r = run(FileTools.PROFILE_COLUMNS, "log", f.toString());
        assertTrue(r.startsWith("log:1 (sales.csv): 6 columns, 60 rows."), r);
        assertTrue(r.contains("1. time — time") && r.contains("from 2026-10-28 10:00:00 to 2026-10-28 10:59:00"), r);
        assertTrue(r.contains("2. region — category: 60 values, 3 different"), r);
        assertTrue(r.contains("most frequent: North (30), South (20), East (10)"), r);
        assertTrue(r.contains("4. ms — number") && r.contains("min 10") && r.contains("max 305"), r);
        assertTrue(r.contains("5. order_id — ID: 60 values, 60 different"), r);
        assertTrue(r.contains("6. note — ") && r.contains("20 empty (33%)"), r);
        assertTrue(r.contains("Use these exact column names in pivot"), r);
    }

    @Test
    void picksColumnsCapsTopAndLimitAndNamesTheColumnsWhenOneIsWrong() throws Exception {
        Path f = sales();
        String two = run(FileTools.PROFILE_COLUMNS, "log", f.toString(), "columns", "STATUS, region", "top", 1);
        assertTrue(two.contains("1. status") && two.contains("2. region") && !two.contains(". ms"), two);
        assertTrue(two.contains("most frequent: ok (54)") && !two.contains("err ("), "top=1: " + two);
        String one = run(FileTools.PROFILE_COLUMNS, "log", f.toString(), "limit", 1);
        assertTrue(one.contains("1. time") && one.contains("5 more columns"), one);
        String bad = fail(FileTools.PROFILE_COLUMNS, "log", f.toString(), "columns", "regoin");
        assertTrue(bad.contains("No column regoin") && bad.contains("time, region, status, ms, order_id, note"), bad);
        assertTrue(fail(FileTools.PROFILE_COLUMNS, "log", f.toString(), "top", 99).contains("top must be between"));
    }

    @Test
    void aPlainLogWithoutColumnsSaysWhatToUseInstead() throws Exception {
        Path f = Files.writeString(logs.resolve("plain.txt"), "just some words\nand more words\n");
        String r = fail(FileTools.PROFILE_COLUMNS, "log", f.toString());
        assertTrue(r.contains("no columns") && r.contains("insights"), r);
    }

    // ---------------------------------------------------------------- merge_tables

    @Test
    void mergesTablesByTimeWithASourceColumnTheOtherToolsRead() throws Exception {
        Files.writeString(logs.resolve("jan.csv"), """
                time,region,amount
                2026-01-01 10:00:00,North,5
                2026-01-01 12:00:00,South,7
                """);
        Files.writeString(logs.resolve("feb.csv"), """
                Time,Amount,channel
                2026-01-01 11:00:00,3,web
                """);
        String r = run(FileTools.MERGE_TABLES, "files", logs.resolve("jan.csv") + ", " + logs.resolve("feb.csv"));
        assertTrue(r.startsWith("Merged 2 tables into log:3: 5 columns (source first, then time, region, amount, "
                + "channel), 3 rows."), r);
        assertTrue(r.contains("time order"), r);
        assertTrue(r.contains("jan.csv (3 columns; empty in its rows: channel)"), r);
        assertTrue(r.contains("feb.csv (3 columns; empty in its rows: region)"), r);

        String p = TableTools.PIVOT.run(new Tool.Args(args("log", "log:3", "rows", "source", "values", "sum(amount)")), w);
        assertTrue(p.contains("jan") && p.contains("12") && p.contains("feb") && p.contains("3"), p);
        String lines = LogTools.READ_LINES.run(new Tool.Args(args("log", "log:3", "from", 1, "count", 4)), w);
        assertTrue(lines.indexOf("10:00:00") < lines.indexOf("11:00:00")
                && lines.indexOf("11:00:00") < lines.indexOf("12:00:00"), "rows in time order: " + lines);
        assertFalse(Files.list(logs).anyMatch(x -> x.getFileName().toString().startsWith("merged")),
                "nothing written next to the files");
    }

    @Test
    void takesAJsonListAndRefusesLogsAndSingleFiles() throws Exception {
        Files.writeString(logs.resolve("a.csv"), "id,v\n1,2\n");
        Files.writeString(logs.resolve("b.csv"), "id,v\n3,4\n");
        Files.writeString(logs.resolve("app.log"), "2026-11-04 10:00:00 INFO a\n2026-11-04 10:00:01 INFO b\n");
        String r = run(FileTools.MERGE_TABLES, "files", List.of(logs.resolve("a.csv").toString(),
                logs.resolve("b.csv").toString()).toString());
        assertTrue(r.contains("Merged 2 tables") && r.contains("one file after the other"), r);
        String json = run(FileTools.MERGE_TABLES, "files", "[\"" + logs.resolve("a.csv") + "\", \""
                + logs.resolve("b.csv") + "\"]");
        assertTrue(json.contains("Merged 2 tables"), json);
        assertTrue(fail(FileTools.MERGE_TABLES, "files", logs.resolve("a.csv").toString()).contains("two or more"));
        assertTrue(fail(FileTools.MERGE_TABLES, "files", logs.resolve("a.csv") + "," + logs.resolve("app.log"))
                .contains("merge_logs"));
        assertEquals(List.of("a b.csv", "c.csv"), FileTools.items("'a b.csv', \"c.csv\""));
    }

    // ---------------------------------------------------------------- show_entry

    @Test
    void showsAWholeEntryItsFieldsAndItsJson() throws Exception {
        Path f = Files.writeString(logs.resolve("app.log"), """
                2026-11-04 10:00:00.000 INFO  [main] app.Start - started
                2026-11-04 10:00:01.000 ERROR [worker-1] app.Orders - failed {"order": 7, "payload": "{\\"sku\\": \\"A1\\"}"}
                java.lang.IllegalStateException: boom
                \tat app.Orders.place(Orders.java:42)
                \tat app.Main.main(Main.java:10)
                2026-11-04 10:00:02.000 INFO  [main] app.Start - done
                """);
        String r = run(FileTools.SHOW_ENTRY, "log", f.toString(), "line", 4);
        assertTrue(r.startsWith("log:1 lines 2 – 5: an entry of 4 lines (line 4 is in it):"), r);
        assertTrue(r.contains("3: java.lang.IllegalStateException: boom") && r.contains("5: \tat app.Main.main"), r);
        assertFalse(r.contains("done"), r);
        assertTrue(r.contains("The JSON in it") && r.contains("\"sku\": \"A1\""), "JSON in a string opened up: " + r);
        String one = run(FileTools.SHOW_ENTRY, "log", f.toString(), "line", 1);
        assertTrue(one.startsWith("log:1 line 1:") && !one.contains("boom"), one);
        String cut = run(FileTools.SHOW_ENTRY, "log", f.toString(), "line", 2, "max_lines", 2);
        assertTrue(cut.contains("more lines: read_lines"), cut);
        assertTrue(fail(FileTools.SHOW_ENTRY, "log", f.toString(), "line", 99).contains("Give a line from 1 to 6"));
    }
}
