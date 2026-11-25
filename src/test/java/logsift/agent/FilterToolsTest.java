package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import logsift.core.FilterEngine;
import logsift.core.Formula;
import logsift.core.LogAnalysis;
import logsift.core.SearchQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filters, the timeline and following a growing log: the tools as an agent calls them. */
class FilterToolsTest {

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

    private ToolCall call(Tool t, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return ToolCall.run(t, new Tool.Args(m), w);
    }

    private String run(Tool t, Object... kv) {
        ToolCall c = call(t, kv);
        assertFalse(c.failed(), c.text());
        return c.text();
    }

    private String fails(Tool t, Object... kv) {
        ToolCall c = call(t, kv);
        assertTrue(c.failed(), "should fail: " + c.text());
        return c.text();
    }

    /** The 1-based numbers of the lines an answer lists ("12: …" matches, "13- …" context). */
    private static List<Long> lines(String answer, boolean matchesOnly) {
        List<Long> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^([\\d,]+)([:-]) ").matcher(answer);
        while (m.find()) {
            if (!matchesOnly || m.group(2).equals(":")) {
                out.add(Long.parseLong(m.group(1).replace(",", "")));
            }
        }
        return out;
    }

    /** Ten lines over ten minutes: two errors (one with a stack trace), a warning, health checks. */
    private String appLog() throws IOException {
        return Files.writeString(logs.resolve("app.log"), """
                2026-11-04 10:00:00.000 INFO  [main] c.a.App - started
                2026-11-04 10:00:05.000 WARN  [http-1] c.a.Api - slow call 1200 ms
                2026-11-04 10:01:00.000 ERROR [http-2] c.a.Api - request timeout order=1
                java.lang.IllegalStateException: timeout
                \tat c.a.Api.call(Api.java:10)
                2026-11-04 10:01:30.000 INFO  [http-1] c.a.Api - health check ok
                2026-11-04 10:02:00.000 ERROR [http-3] c.a.Db - connection refused
                2026-11-04 10:05:00.000 INFO  [http-1] c.a.Api - retry timeout order=2
                2026-11-04 10:05:10.000 INFO  [http-2] c.a.Api - health check timeout
                2026-11-04 10:10:00.000 DEBUG [main] c.a.App - done
                """).toString();
    }

    @Test
    void showAndHideKeepWhatTheyShould() throws Exception {
        String log = appLog();
        String a = run(FilterTools.FILTER, "log", log, "show", List.of("timeout"), "whole_entries", false);
        assertTrue(a.startsWith("4 lines match (showing “timeout”) of 10 lines:"), a);
        assertEquals(List.of(3L, 4L, 8L, 9L), lines(a, false));
        String hidden = run(FilterTools.FILTER, "log", log, "show", List.of("timeout"), "hide", List.of("health"),
                "whole_entries", false);
        assertEquals(List.of(3L, 4L, 8L), lines(hidden, false), hidden);
        assertTrue(hidden.contains("showing “timeout”; hiding “health”"), hidden);
        // only hide: every other line
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 7L, 8L, 10L),
                lines(run(FilterTools.FILTER, "log", log, "hide", List.of("health")), false));
        // several shows: any of them; a terminal gives a list as lines or as JSON text
        assertEquals(List.of(3L, 4L, 7L, 8L, 9L), lines(run(FilterTools.FILTER, "log", log, "show",
                "timeout\nrefused", "whole_entries", false), false));
        assertEquals(List.of(3L, 4L, 7L, 8L, 9L), lines(run(FilterTools.FILTER, "log", log, "show",
                "[\"timeout\", \"refused\"]", "whole_entries", "false"), false));
    }

    @Test
    void wholeEntriesKeepAStackTraceWithItsLine() throws Exception {
        String log = appLog();
        String a = run(FilterTools.FILTER, "log", log, "levels", List.of("ERROR"));
        assertEquals(List.of(3L, 4L, 5L, 7L), lines(a, true), "the exception's lines belong to its entry: " + a);
        String alone = run(FilterTools.FILTER, "log", log, "levels", "ERROR", "whole_entries", false);
        assertEquals(List.of(3L, 7L), lines(alone, false), alone);
        assertTrue(alone.startsWith("2 lines match (levels ERROR)"), alone);
        // levels as the agent spells them
        assertEquals(List.of(2L, 3L, 7L), lines(run(FilterTools.FILTER, "log", log, "levels", "error, warning",
                "whole_entries", false), false));
        assertTrue(fails(FilterTools.FILTER, "log", log, "levels", List.of("BAD")).contains("Use ERROR, WARN"));
    }

    @Test
    void termsCombineByTheFormula() throws Exception {
        String log = appLog();
        String a = run(FilterTools.FILTER, "log", log, "terms", List.of("timeout", "order", "health"),
                "formula", "(A AND B) OR C", "whole_entries", false);
        assertEquals(List.of(3L, 6L, 8L, 9L), lines(a, false), a);
        assertTrue(a.contains("(A AND B) OR C with A = “timeout”, B = “order”, C = “health”"), a);
        assertEquals(List.of(3L, 4L, 8L), lines(run(FilterTools.FILTER, "log", log, "terms", List.of("timeout",
                "health"), "formula", "a and not b", "whole_entries", false), false), "lower case and words as in the window");
        String bad = fails(FilterTools.FILTER, "log", log, "terms", List.of("x"), "formula", "A AND B");
        assertTrue(bad.contains("uses B but there is 1 term (A)"), bad);
        assertTrue(fails(FilterTools.FILTER, "log", log, "formula", "A OR B").contains("A formula needs terms"));
        assertTrue(fails(FilterTools.FILTER, "log", log, "terms", List.of("x"), "formula", "A AND (").contains(
                "Not a valid formula"));
        assertTrue(fails(FilterTools.FILTER, "log", log, "show", List.of("x"), "terms", List.of("y")).contains(
                "not both"));
    }

    @Test
    void aTimeRangeKeepsWholeMinutesAndCombinesWithTheRest() throws Exception {
        String log = appLog();
        String a = run(FilterTools.FILTER, "log", log, "from_time", "2026-11-04 10:01", "to_time", "10:02");
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), lines(a, false), "minute 10:02 included: " + a);
        assertTrue(a.contains("logged 2026-11-04 10:01 … 10:02"), a);
        assertEquals(List.of(7L), lines(run(FilterTools.FILTER, "log", log, "levels", List.of("ERROR"),
                "from_time", "10:01:30", "whole_entries", false), false));
        assertEquals(List.of(8L, 9L, 10L), lines(run(FilterTools.FILTER, "log", log, "from_time", "10:03"), false),
                "to the end");
        assertTrue(fails(FilterTools.FILTER, "log", log, "from_time", "10:05", "to_time", "10:01").contains(
                "Nothing was logged"));
        assertTrue(fails(FilterTools.FILTER, "log", log, "from_time", "soon").contains("Not a time"));
    }

    @Test
    void contextLinesAreMarkedApart() throws Exception {
        String log = appLog();
        String a = run(FilterTools.FILTER, "log", log, "show", List.of("connection refused"), "context", 1);
        assertTrue(a.startsWith("1 line matches (showing “connection refused”), 3 shown with 1 line of context"), a);
        assertTrue(a.contains("6- 2026-11-04 10:01:30.000 INFO"), a);
        assertTrue(a.contains("7: 2026-11-04 10:02:00.000 ERROR"), a);
        assertTrue(a.contains("8- 2026-11-04 10:05:00.000 INFO"), a);
        assertTrue(fails(FilterTools.FILTER, "log", log, "show", List.of("x"), "context", 99).contains("between 0 and 50"));
    }

    @Test
    void pagesThroughWhatItKeeps() throws Exception {
        String log = appLog();
        String first = run(FilterTools.FILTER, "log", log, "show", List.of("timeout"), "whole_entries", false,
                "limit", 2);
        assertEquals(List.of(3L, 4L), lines(first, false));
        assertTrue(first.endsWith("… 2 more. Next: from_line=8"), first);
        String next = run(FilterTools.FILTER, "log", log, "show", List.of("timeout"), "whole_entries", false,
                "limit", 2, "from_line", 8);
        assertEquals(List.of(8L, 9L), lines(next, false));
        assertTrue(next.startsWith("4 lines match"), "the count is for the whole log: " + next);
        assertTrue(run(FilterTools.FILTER, "log", log, "show", List.of("timeout"), "from_line", 10).contains(
                "None at or after line 10"));
        assertTrue(run(FilterTools.FILTER, "log", log, "show", List.of("nowhere")).startsWith("0 lines match"));
    }

    @Test
    void columnFiltersOnATable() throws Exception {
        Path csv = Files.writeString(logs.resolve("orders.csv"), """
                id,status,duration,user
                1,200,120 ms,ann
                2,500,2.5 s,bob
                3,200,3 s,bobby
                4,404,10 ms,carl
                """);
        String a = run(FilterTools.FILTER, "log", csv.toString(), "columns", List.of("status = 500"));
        assertEquals(List.of(3L), lines(a, false), a);
        assertEquals(List.of(3L, 4L), lines(run(FilterTools.FILTER, "log", csv.toString(), "columns",
                List.of("duration > 2 s")), false), "units compare");
        assertEquals(List.of(3L, 4L), lines(run(FilterTools.FILTER, "log", csv.toString(), "columns",
                List.of("user contains bob")), false));
        assertEquals(List.of(4L), lines(run(FilterTools.FILTER, "log", csv.toString(), "columns",
                List.of("user contains bob", "status != 500")), false), "all of them, and != for not");
        assertEquals(List.of(2L, 4L), lines(run(FilterTools.FILTER, "log", csv.toString(), "columns",
                List.of("STATUS between 200..200")), false), "any case");
        assertTrue(fails(FilterTools.FILTER, "log", csv.toString(), "columns", List.of("nope = 1")).contains(
                "no column “nope”. Its columns: id, status, duration, user"));
        assertTrue(fails(FilterTools.FILTER, "log", csv.toString(), "columns", List.of("status")).contains(
                "Write COLUMN OP VALUE"));
        assertTrue(fails(FilterTools.FILTER, "log", csv.toString(), "columns", List.of("status > many")).contains(
                "Not a number"));
    }

    @Test
    void theAnswerIsWhatTheWindowsFiltersKeep() throws Exception {
        String log = appLog();
        String a = run(FilterTools.FILTER, "log", log, "show", List.of("timeout", "refused"), "hide",
                List.of("health"), "context", 1, "limit", 500);
        // the window: Show A, Show B, Hide C, Keep stack traces, Context 1
        try (LogAnalysis la = LogAnalysis.open(Path.of(log), List.of())) {
            la.awaitReady(() -> false);
            try (FilterEngine e = new FilterEngine(la.document())) {
                e.setEntryFormat(la.formats().entries());
                e.setContext(1);
                e.configure(List.of(new FilterEngine.Spec('A', new SearchQuery("timeout", false, false, false)),
                        new FilterEngine.Spec('B', new SearchQuery("refused", false, false, false)),
                        new FilterEngine.Spec('C', new SearchQuery("health", false, false, false))),
                        Formula.auto(List.of('A', 'B'), List.of('C')));
                while (!e.awaitIdle(200) || e.isRunning()) {
                    Thread.onSpinWait();
                }
                var r = e.result();
                List<Long> shown = new ArrayList<>();
                List<Long> matched = new ArrayList<>();
                for (long row = 0; row < r.count(); row++) {
                    long l = r.lineAt(row);
                    shown.add(l + 1);
                    if (r.isMatch(l)) {
                        matched.add(l + 1);
                    }
                }
                assertEquals(shown, lines(a, false), a);
                assertEquals(matched, lines(a, true), a);
                assertTrue(a.startsWith(matched.size() + " lines match"), a);
            }
        }
    }

    @Test
    void sayingNothingToKeepIsAMistakeToFix() throws Exception {
        String log = appLog();
        assertTrue(fails(FilterTools.FILTER, "log", log).contains("Say which lines to keep"));
        assertTrue(fails(FilterTools.FILTER, "log", log, "show", List.of("(x"), "regex", true).contains(
                "Not a valid regular expression in show"));
    }

    // ---------------------------------------------------------------- timeline

    @Test
    void timelineCountsLinesErrorsAndWarningsPerBucket() throws Exception {
        String log = appLog();
        String a = run(FilterTools.TIMELINE, "log", log, "bucket", "5m");
        assertTrue(a.startsWith("Lines per 5 minutes, 2026-11-04 10:00 … 2026-11-04 10:10 (3 buckets"), a);
        assertTrue(a.contains("\n10:00 | 7 | 2 | 1\n"), "lines, errors, warnings: " + a);
        assertTrue(a.contains("\n10:05 | 2 | 0 | 0\n"), a);
        assertTrue(a.contains("\n10:10 | 1 | 0 | 0\n"), a);
        assertTrue(a.contains("Busiest: 10:00 (7 lines, from line 1). Most errors: 10:00 (2, from line 1)."), a);
        String m = run(FilterTools.TIMELINE, "log", log, "bucket", "1m");
        assertTrue(m.contains("\n10:03 … 10:04 | nothing logged (2 buckets)\n"), "gaps in one row: " + m);
        assertTrue(m.contains("\n10:01 | 4 | 1 | 0\n"), "a stack trace counts with its line: " + m);
    }

    @Test
    void timelineCountsMatchingLinesToo() throws Exception {
        String log = appLog();
        String a = run(FilterTools.TIMELINE, "log", log, "bucket", "5m", "query", "timeout");
        assertTrue(a.contains("time | lines | errors | warnings | “timeout”"), a);
        assertTrue(a.contains("\n10:00 | 7 | 2 | 1 | 2\n"), a);
        assertTrue(a.contains("\n10:05 | 2 | 0 | 0 | 2\n"), a);
        String errs = run(FilterTools.TIMELINE, "log", log, "bucket", "5m", "query", "timeout", "level", "ERROR");
        assertTrue(errs.contains("\n10:00 | 7 | 2 | 1 | 1\n") && errs.contains("\n10:05 | 2 | 0 | 0 | 0\n"), errs);
        String window = run(FilterTools.TIMELINE, "log", log, "bucket", "1m", "from_time", "10:01", "to_time", "10:02");
        assertTrue(window.contains("(2 buckets"), window);
        assertTrue(window.contains("\n10:01 | 4 | 1 | 0\n") && window.contains("\n10:02 | 1 | 1 | 0\n"), window);
        String auto = run(FilterTools.TIMELINE, "log", log);
        assertTrue(auto.startsWith("Lines per 30 seconds"), "about 30 buckets for ten minutes: " + auto);
    }

    @Test
    void timelineMistakesSayHowToFixThem() throws Exception {
        String log = appLog();
        assertTrue(fails(FilterTools.TIMELINE, "log", log, "bucket", "minute").contains("Use a number and a unit"));
        assertTrue(fails(FilterTools.TIMELINE, "log", log, "bucket", "1s").contains("is too many (at most 500)"));
        Path plain = Files.writeString(logs.resolve("plain.txt"), "no times\nat all\n");
        assertTrue(fails(FilterTools.TIMELINE, "log", plain.toString()).contains("no timestamps"));
    }

    // ---------------------------------------------------------------- follow

    @Test
    void followReadsWhatAGrowingLogAdded() throws Exception {
        Path f = Files.writeString(logs.resolve("live.log"), "one\ntwo\nthree\n");
        String start = run(FilterTools.FOLLOW, "log", f.toString());
        assertTrue(start.startsWith("The log has 3 lines now; the last ones:"), start);
        assertTrue(start.endsWith("Next: after_line=3"), start);
        Files.writeString(f, "four\nfive\n", StandardOpenOption.APPEND);
        String more = run(FilterTools.FOLLOW, "log", f.toString(), "after_line", 3);
        assertTrue(more.startsWith("2 new lines after line 3 (the log has 5 lines now):"), more);
        assertTrue(more.contains("4: four\n5: five"), more);
        assertTrue(more.endsWith("Next: after_line=5"), more);
        String none = run(FilterTools.FOLLOW, "log", f.toString(), "after_line", 5);
        assertTrue(none.startsWith("No new lines after line 5"), none);
        // paging through many new lines
        Files.writeString(f, "a\nb\nc\n", StandardOpenOption.APPEND);
        String page = run(FilterTools.FOLLOW, "log", f.toString(), "after_line", 5, "limit", 2);
        assertTrue(page.endsWith("… 1 more. Next: after_line=7"), page);
    }

    @Test
    void followWaitsForNewLines() throws Exception {
        Path f = Files.writeString(logs.resolve("wait.log"), "one\n");
        run(FilterTools.FOLLOW, "log", f.toString());
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(700);
                Files.writeString(f, "two\n", StandardOpenOption.APPEND);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long t0 = System.currentTimeMillis();
        writer.start();
        String a = run(FilterTools.FOLLOW, "log", f.toString(), "after_line", 1, "wait_seconds", 20);
        writer.join();
        assertTrue(a.contains("2: two"), a);
        assertTrue(System.currentTimeMillis() - t0 < 15_000, "answers once something came");
        assertTrue(fails(FilterTools.FOLLOW, "log", f.toString(), "wait_seconds", 61).contains("between 0 and 60"));
    }

    @Test
    void followSaysWhenTheFileWasRotated() throws Exception {
        Path f = Files.writeString(logs.resolve("rot.log"), "old 1\nold 2\nold 3\nold 4\n");
        run(FilterTools.FOLLOW, "log", f.toString());
        Files.writeString(f, "new 1\n"); // a new file in its place, shorter
        String a = run(FilterTools.FOLLOW, "log", f.toString(), "after_line", 4);
        assertTrue(a.startsWith("The file was replaced or cut since line 4"), a);
        assertTrue(a.contains("1: new 1"), a);
    }

    @Test
    void theToolsAreOfferedWithTheOthers() {
        List<String> names = LogTools.all().stream().map(Tool::name).toList();
        assertTrue(names.containsAll(List.of("filter", "timeline", "follow")), names.toString());
        for (Tool t : FilterTools.all()) {
            assertTrue(t.readOnly(), t.name());
            assertTrue(t.description().length() > 600, "detailed for weaker models: " + t.name());
        }
    }
}
