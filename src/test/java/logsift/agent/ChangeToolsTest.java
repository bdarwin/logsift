package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * The tools that change something: refused while changes are off, and when on they save only new
 * files inside the folders agents may read, never replacing one; what they keep (bookmarks, log
 * formats, log locations) is read back by the other tools.
 */
class ChangeToolsTest {
    @TempDir
    Path dir;
    Path logs;
    Path home;
    Workspace w;

    static final String LOG = """
            2026-11-20 10:00:00 INFO start
            2026-11-20 10:00:01 ERROR timed out
            java.io.IOException: timed out
            \tat a.B.c(B.java:1)
            2026-11-20 10:00:02 INFO fine
            2026-11-20 10:00:03 ERROR refused
            2026-11-20 10:00:04 INFO done
            """;

    @BeforeEach
    void start() throws Exception {
        logs = Files.createDirectories(dir.resolve("logs"));
        home = Files.createDirectories(dir.resolve("home"));
        Files.writeString(logs.resolve("app.log"), LOG);
        w = workspace(true);
    }

    private Workspace workspace(boolean changes) {
        Workspace ws = new Workspace(new Workspace.Options(new Scope(List.of(logs), dir), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 25_000, List.of(), null, home, changes));
        ws.setCatalog(() -> LogCatalog.load(home, null, List.of()));
        return ws;
    }

    @AfterEach
    void stop() {
        w.close();
    }

    private ToolCall call(Tool t, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return ToolCall.run(t, new Tool.Args(m), w);
    }

    private String ok(Tool t, Object... kv) {
        ToolCall c = call(t, kv);
        assertFalse(c.failed(), t.name() + ": " + c.text());
        return c.text();
    }

    private String failed(Tool t, Object... kv) {
        ToolCall c = call(t, kv);
        assertTrue(c.failed(), t.name() + " should fail: " + c.text());
        return c.text();
    }

    private String log() {
        return logs.resolve("app.log").toString();
    }

    @Test
    void everyToolThatChangesSomethingIsRefusedWhileChangesAreOff() throws Exception {
        w.close();
        w = workspace(false);
        Files.writeString(logs.resolve("base.txt"), "a\n");
        String out = logs.resolve("out.txt").toString();
        List<Object[]> calls = List.of(
                new Object[] {ChangeTools.EXPORT_LINES, "log", log(), "save_to", out},
                new Object[] {ChangeTools.EXPORT_DIFF, "left", log(), "right", log(), "save_to", out},
                new Object[] {ChangeTools.SAVE_MERGE3, "base", logs.resolve("base.txt").toString(), "yours",
                        logs.resolve("base.txt").toString(), "theirs", logs.resolve("base.txt").toString(), "save_to", out},
                new Object[] {ChangeTools.SET_BOOKMARK, "log", log(), "line", 2},
                new Object[] {ChangeTools.SAVE_LOG_FORMAT, "name", "x", "pattern", "{time} {message}", "sample",
                        "2026-11-20 10:00:00 hi"},
                new Object[] {ChangeTools.SAVE_LOG_LOCATION, "name", "orders", "folders", logs.toString()});
        for (Object[] c : calls) {
            Tool t = (Tool) c[0];
            assertFalse(t.readOnly(), t.name());
            Object[] kv = java.util.Arrays.copyOfRange(c, 1, c.length);
            assertTrue(failed(t, kv).contains("Let agents change things"), t.name());
            assertFalse(ToolCall.offered(LogTools.all(), w).contains(t), t.name() + " not offered");
        }
        assertFalse(Files.exists(logs.resolve("out.txt")));
        assertFalse(Files.exists(home.resolve("formats.properties")));
        assertFalse(Files.exists(LogCatalog.userFile(home)));
        assertFalse(Files.exists(home.resolve("bookmarks")));
        // the tools that only read still answer
        assertTrue(ToolCall.offered(LogTools.all(), w).contains(ChangeTools.LIST_BOOKMARKS));
        assertTrue(ToolCall.offered(LogTools.all(), w).contains(ChangeTools.LIST_LOG_FORMATS));
        assertTrue(ToolCall.offered(LogTools.all(), w).contains(ChangeTools.MERGE3));
        ok(ChangeTools.LIST_BOOKMARKS, "log", log());
    }

    @Test
    void exportLinesSavesARangeOrTheMatchesWithTheirEntriesAsANewFile() throws Exception {
        Path range = logs.resolve("range.log");
        String r = ok(ChangeTools.EXPORT_LINES, "log", log(), "save_to", range.toString(), "from_line", 2, "to_line", 3);
        assertTrue(r.startsWith("Saved 2 lines"), r);
        assertEquals("2026-11-20 10:00:01 ERROR timed out\njava.io.IOException: timed out\n", Files.readString(range));

        Path errors = logs.resolve("errors.log");
        String e = ok(ChangeTools.EXPORT_LINES, "log", log(), "save_to", errors.toString(), "query", "ERROR");
        assertTrue(e.contains("Saved 2 lines"), e);
        assertEquals("2026-11-20 10:00:01 ERROR timed out\n2026-11-20 10:00:03 ERROR refused\n", Files.readString(errors));

        Path entries = logs.resolve("entries.log");
        ok(ChangeTools.EXPORT_LINES, "log", log(), "save_to", entries.toString(), "query", "ERROR", "whole_entries", true);
        assertEquals("""
                2026-11-20 10:00:01 ERROR timed out
                java.io.IOException: timed out
                \tat a.B.c(B.java:1)
                2026-11-20 10:00:03 ERROR refused
                """, Files.readString(entries), "each error with its stack trace");

        String none = ok(ChangeTools.EXPORT_LINES, "log", log(), "save_to", logs.resolve("none.log").toString(),
                "query", "nothing like this");
        assertTrue(none.contains("Nothing was written"), none);
        assertFalse(Files.exists(logs.resolve("none.log")));

        // the saved file is a log like any other
        String opened = ok(LogTools.OPEN_LOG, "path", errors.toString());
        assertTrue(opened.contains("log:"), opened);
    }

    @Test
    void neverReplacesAFileNorWritesOutsideTheFolders() throws Exception {
        Path mine = logs.resolve("mine.log");
        Files.writeString(mine, "keep me\n");
        assertTrue(failed(ChangeTools.EXPORT_LINES, "log", log(), "save_to", mine.toString()).contains("never replaces"));
        assertEquals("keep me\n", Files.readString(mine));
        assertTrue(failed(ChangeTools.EXPORT_LINES, "log", log(), "save_to", log()).contains("never replaces"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        failed(ChangeTools.EXPORT_LINES, "log", log(), "save_to", outside.resolve("x.log").toString());
        assertFalse(Files.exists(outside.resolve("x.log")));
        failed(ChangeTools.EXPORT_DIFF, "left", log(), "right", log(), "save_to", mine.toString());
        assertEquals("keep me\n", Files.readString(mine));
    }

    @Test
    void exportDiffSavesTheTableDifferencesAsCsvAndTextAsAUnifiedDiff() throws Exception {
        Path a = logs.resolve("old.csv");
        Path b = logs.resolve("new.csv");
        Files.writeString(a, "sku,name,stock\nA-1,Tea,10\nA-2,Coffee,5\nA-4,Milk,2\n");
        Files.writeString(b, "sku,name,stock\nA-1,Tea,12\nA-2,Coffee,5\nA-5,Sugar,9\n");
        Path out = logs.resolve("changes.csv");
        String t = ok(ChangeTools.EXPORT_DIFF, "left", a.toString(), "right", b.toString(), "keys", "sku",
                "save_to", out.toString());
        assertTrue(t.startsWith("Saved 3 differing rows"), t);
        List<String> rows = Files.readAllLines(out);
        assertEquals("status,left line,right line,sku,name (left),name (right),stock (left),stock (right)", rows.get(0));
        assertTrue(rows.contains("changed,2,2,A-1,Tea,,10,12"), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("added,,4,A-5")), rows.toString());
        assertTrue(rows.stream().anyMatch(r -> r.startsWith("removed,4,,A-4")), rows.toString());
        assertEquals(4, rows.size());
        assertTrue(failed(ChangeTools.EXPORT_DIFF, "left", a.toString(), "right", b.toString(), "keys", "nope",
                "save_to", logs.resolve("x.csv").toString()).contains("The columns: "));

        Path c1 = logs.resolve("a.conf");
        Path c2 = logs.resolve("b.conf");
        Files.writeString(c1, "one\ntwo\nthree\n");
        Files.writeString(c2, "one\n2\nthree\nfour\n");
        Path diff = logs.resolve("conf.diff");
        String d = ok(ChangeTools.EXPORT_DIFF, "left", c1.toString(), "right", c2.toString(), "save_to", diff.toString());
        assertTrue(d.contains("unified diff"), d);
        String text = Files.readString(diff);
        assertTrue(text.startsWith("--- " + c1.toRealPath() + "\n+++ " + c2.toRealPath() + "\n"), text);
        assertTrue(text.contains("@@ -2,1 +2,1 @@\n-two\n+2\n"), text);
        assertTrue(text.contains("@@ -3,0 +4,1 @@\n+four\n"), text);
    }

    @Test
    void merge3ShowsNumberedConflictsAndSaveMerge3ResolvesThem() throws Exception {
        Path base = logs.resolve("base.conf");
        Path yours = logs.resolve("yours.conf");
        Path theirs = logs.resolve("theirs.conf");
        Files.writeString(base, "a=1\nb=2\nc=3\nd=4\ne=5\n");
        Files.writeString(yours, "a=1\nb=20\nc=3\nd=4\ne=50\n");
        Files.writeString(theirs, "a=10\nb=21\nc=3\nd=4\ne=51\n");
        String shown = ok(ChangeTools.MERGE3, "base", base.toString(), "yours", yours.toString(), "theirs", theirs.toString());
        assertTrue(shown.contains("2 conflicts"), shown);
        assertTrue(shown.contains("Conflict 1: base lines 1–2"), shown);
        assertTrue(shown.contains("  yours  | b=20") && shown.contains("  theirs | b=21"), shown);
        assertTrue(shown.contains("Conflict 2: base line 5") && shown.contains("  theirs | e=51"), shown);

        Path out = logs.resolve("merged.conf");
        String saved = ok(ChangeTools.SAVE_MERGE3, "base", base.toString(), "yours", yours.toString(), "theirs",
                theirs.toString(), "save_to", out.toString(), "choices", "1=theirs", "prefer", "yours");
        assertTrue(saved.contains("Conflicts: 1: theirs; 2: yours."), saved);
        assertEquals("a=10\nb=21\nc=3\nd=4\ne=50\n", Files.readString(out));

        Path marked = logs.resolve("marked.conf");
        String m = ok(ChangeTools.SAVE_MERGE3, "base", base.toString(), "yours", yours.toString(), "theirs",
                theirs.toString(), "save_to", marked.toString(), "choices", "2=both");
        assertTrue(m.contains("1 conflict keeps git's markers"), m);
        String text = Files.readString(marked);
        assertTrue(text.contains("<<<<<<< yours (yours.conf)\na=1\nb=20\n=======\na=10\nb=21\n>>>>>>> theirs (theirs.conf)\n"),
                text);
        assertTrue(text.endsWith("e=50\ne=51\n"), text);

        assertTrue(failed(ChangeTools.SAVE_MERGE3, "base", base.toString(), "yours", yours.toString(), "theirs",
                theirs.toString(), "save_to", logs.resolve("x.conf").toString(), "choices", "3=yours")
                .contains("There is no conflict 3"));
        assertTrue(failed(ChangeTools.SAVE_MERGE3, "base", base.toString(), "yours", yours.toString(), "theirs",
                theirs.toString(), "save_to", yours.toString()).contains("never replaces"));
        assertEquals("a=1\nb=20\nc=3\nd=4\ne=50\n", Files.readString(yours), "yours left as it was");
    }

    @Test
    void aMergeKeepsWindowsLineEnds() throws Exception {
        Path base = logs.resolve("b.txt");
        Path yours = logs.resolve("y.txt");
        Path theirs = logs.resolve("t.txt");
        Files.writeString(base, "x\r\ny\r\n");
        Files.writeString(yours, "x\r\nY\r\n");
        Files.writeString(theirs, "X\r\ny\r\n");
        Path out = logs.resolve("m.txt");
        String s = ok(ChangeTools.SAVE_MERGE3, "base", base.toString(), "yours", yours.toString(), "theirs",
                theirs.toString(), "save_to", out.toString());
        assertTrue(s.contains("0 conflicts") && s.contains("CRLF"), s);
        assertEquals("X\r\nY\r\n", Files.readString(out));
    }

    @Test
    void aBookmarkSetIsListedRemovedAndKeptWhereTheWindowKeepsIt() throws Exception {
        assertTrue(ok(ChangeTools.LIST_BOOKMARKS, "log", log()).startsWith("No bookmarks"));
        String set = ok(ChangeTools.SET_BOOKMARK, "log", log(), "line", 2, "note", "first timeout");
        assertTrue(set.startsWith("Bookmarked line 2 [first timeout]"), set);
        ok(ChangeTools.SET_BOOKMARK, "log", log(), "line", 6);
        String list = ok(ChangeTools.LIST_BOOKMARKS, "log", log());
        assertTrue(list.startsWith("2 bookmarks in app.log:"), list);
        assertTrue(list.contains("2 [first timeout]: 2026-11-20 10:00:01 ERROR timed out"), list);
        assertTrue(list.contains("6: 2026-11-20 10:00:03 ERROR refused"), list);
        // the window's store: line 1 (0-based) with its note
        Path abs = logs.resolve("app.log").toAbsolutePath().normalize();
        assertEquals(Map.of(1L, "first timeout", 5L, ""), logsift.core.BookmarkStore.load(home, abs, Files.size(abs)));
        String all = ok(ChangeTools.LIST_BOOKMARKS);
        assertTrue(all.contains(abs + ": 2 bookmarks"), all);

        assertTrue(ok(ChangeTools.SET_BOOKMARK, "log", log(), "line", 6, "remove", true).startsWith("Removed"));
        assertTrue(ok(ChangeTools.SET_BOOKMARK, "log", log(), "line", 4, "remove", true).contains("nothing to remove"));
        assertTrue(failed(ChangeTools.SET_BOOKMARK, "log", log(), "line", 99).contains("after the end"));
        assertEquals("2026-11-20 10:00:00 INFO start", Files.readAllLines(abs).get(0), "the log itself unchanged");
    }

    @Test
    void aLogFormatIsTriedOnTheSampleSavedAndUsedForLogsOpenedAfter() throws Exception {
        Path odd = logs.resolve("odd.log");
        Files.writeString(odd, "20.11.2026 10:00:01 E orders: timed out\n20.11.2026 10:00:02 I orders: fine\n");
        String bad = failed(ChangeTools.SAVE_LOG_FORMAT, "name", "Orders", "pattern", "{time} [{level}] {message}",
                "sample", "20.11.2026 10:00:01 E orders: timed out");
        assertTrue(bad.contains("Not saved: no sample line matches"), bad);
        assertFalse(Files.exists(home.resolve("formats.properties")));

        String saved = ok(ChangeTools.SAVE_LOG_FORMAT, "name", "Orders", "pattern", "{time} {level} {service}: {message}",
                "time_pattern", "dd.MM.yyyy HH:mm:ss", "files", "odd*.log",
                "sample", "20.11.2026 10:00:01 E orders: timed out\nnot a line of it");
        assertTrue(saved.startsWith("Added the log format “Orders” (columns: "), saved);
        assertTrue(saved.contains("1 of 2 sample lines match"), saved);
        assertTrue(saved.contains("service=orders") && saved.contains("message=timed out"), saved);
        assertTrue(ok(ChangeTools.LIST_LOG_FORMATS, "builtin", false).contains("- Orders: pattern {time} {level} {service}: {message}"));

        String opened = ok(LogTools.OPEN_LOG, "path", odd.toString());
        assertTrue(opened.contains("Orders"), "the new format is used: " + opened);
        String counted = ok(LogTools.QUERY, "log", "log:1", "sql", "SELECT service, count(*) AS n FROM log GROUP BY service");
        assertTrue(counted.contains("orders") && counted.contains("2"), counted);

        String changed = ok(ChangeTools.SAVE_LOG_FORMAT, "name", "orders", "pattern", "{time} {level} {app}: {message}",
                "time_pattern", "dd.MM.yyyy HH:mm:ss", "sample", "20.11.2026 10:00:01 E orders: timed out");
        assertTrue(changed.startsWith("Changed the log format"), changed);
        assertEquals(1, logsift.core.CustomFormat.load(home.resolve("formats.properties")).size(), "changed, not added");
    }

    @Test
    void aLogLocationIsAddedThenAddedToAndListLogsShowsIt() throws Exception {
        Path more = Files.createDirectories(logs.resolve("more"));
        Path outside = Files.createDirectories(dir.resolve("elsewhere"));
        String added = ok(ChangeTools.SAVE_LOG_LOCATION, "name", "orders", "description", "Order service",
                "folders", logs.toString(), "files", "app.log*=Application log", "time_zone", "Europe/Berlin");
        assertTrue(added.startsWith("Added orders in Log Locations"), added);
        String listed = ok(LogTools.LIST_LOGS);
        assertTrue(listed.contains("orders") && listed.contains("app.log"), listed);

        String more2 = ok(ChangeTools.SAVE_LOG_LOCATION, "name", "Orders", "folders", more + "; " + outside,
                "files", "gc.log*=GC log", "notes", "rotates at midnight");
        assertTrue(more2.startsWith("Changed orders"), more2);
        assertTrue(more2.contains("agents can't read it yet") && more2.contains("Settings → Agents"), more2);
        List<LogCatalog.Service> s = LogCatalog.read(LogCatalog.userFile(home));
        assertEquals(1, s.size());
        assertEquals(List.of(logs.toString(), more.toString(), outside.toString()), s.get(0).folders(), "added, none removed");
        assertEquals(2, s.get(0).files().size());
        assertEquals("Order service", s.get(0).description(), "kept");
        assertEquals("Europe/Berlin", s.get(0).timeZone(), "kept");
        assertFalse(w.options().scope().allows(outside.resolve("x.log")), "agents still can't read it");

        assertTrue(failed(ChangeTools.SAVE_LOG_LOCATION, "name", "x", "folders", "relative/logs").contains("full paths"));
        assertTrue(failed(ChangeTools.SAVE_LOG_LOCATION, "name", "x", "folders", logs.toString(), "time_zone", "Mars/Base")
                .contains("IANA"));
        assertEquals(1, LogCatalog.read(LogCatalog.userFile(home)).size(), "failed calls saved nothing");
    }

    @Test
    void theWindowCanSayNoToAChange() throws Exception {
        w.setGate(new Workspace.Gate() {
            public void allow(String what, Path where) {
            }

            @Override
            public void allowChange(String what, Path where) throws Tool.Failure {
                throw new Tool.Failure("The person said no to: " + what);
            }
        });
        assertTrue(failed(ChangeTools.SET_BOOKMARK, "log", log(), "line", 2).contains("bookmark line 2 of app.log"));
        assertFalse(Files.exists(home.resolve("bookmarks")));
        assertTrue(failed(ChangeTools.EXPORT_LINES, "log", log(), "save_to", logs.resolve("x.log").toString())
                .contains("said no"));
        assertFalse(Files.exists(logs.resolve("x.log")));
    }
}
