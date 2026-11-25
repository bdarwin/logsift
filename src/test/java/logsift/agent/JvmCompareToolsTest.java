package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import logsift.heap.LeakApp;
import logsift.jvm.FixtureApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two of the same thing from a JVM compared as the window compares them (heap dumps, histograms,
 * profiles, thread dumps), and a short Flight Recorder recording, only after the person's yes.
 */
class JvmCompareToolsTest {
    @TempDir
    Path dir;
    Workspace w;
    final List<String> asked = new ArrayList<>();

    @BeforeEach
    void start() {
        w = new Workspace(new Workspace.Options(new Scope(List.of(dir), dir), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 120_000, List.of(), null));
    }

    @AfterEach
    void stop() throws Exception {
        w.close();
        Files.deleteIfExists(JvmRules.file(logsift.core.CacheSettings.home()));
    }

    private String call(Tool t, Map<String, Object> args) {
        ToolCall c = ToolCall.run(t, new Tool.Args(args), w);
        return (c.failed() ? "FAILED " : "") + c.text();
    }

    private void saying(boolean yes) {
        w.setGate(new Workspace.Gate() {
            @Override
            public void allow(String what, Path where) {
            }

            @Override
            public void approve(String what, String key, String detail) throws Tool.Failure {
                asked.add(what + " | key=" + key);
                if (!yes) {
                    throw new Tool.Failure("The person said no to: " + what + ".");
                }
            }
        });
    }

    @Test
    void everyToolIsOfferedWithItsArguments() {
        List<String> names = LogTools.all().stream().map(Tool::name).toList();
        for (String n : List.of("compare_heap_dumps", "compare_histograms", "compare_profiles", "compare_thread_dumps",
                "record_profile")) {
            assertTrue(names.contains(n), n + " in " + names);
        }
        for (Tool t : JvmCompareTools.all()) {
            assertTrue(t.readOnly(), t.name() + " only reads");
            assertTrue(t.description().contains("Example:"), t.name() + " shows an example for weaker models");
        }
    }

    @Test
    void twoHeapDumpsSayWhatGrewAndWhereItIsKept() throws Exception {
        Path early = dir.resolve("early.hprof");
        Path late = dir.resolve("late.hprof");
        LeakApp.run(early, dir.resolve("early.txt"), 5_000);
        LeakApp.run(late, dir.resolve("late.txt"), 20_000);
        // given the wrong way round: put in the order they were taken
        String r = call(JvmCompareTools.COMPARE_HEAP_DUMPS, Map.of("before", late.toString(), "after", early.toString()));
        assertFalse(r.startsWith("FAILED"), r);
        assertTrue(r.startsWith("early.hprof → late.hprof"), r);
        assertTrue(r.contains("In use (strongly reachable): "), r);
        assertTrue(r.contains("in static LeakApp.CACHE grew by"), r);
        assertTrue(r.contains("5,000 entries → 20,000 entries"), r);
        assertTrue(r.contains("LeakApp$Session: 5,000 → 20,000 objects (+15,000)")
                || r.contains("LeakApp.Session: 5,000 → 20,000 objects (+15,000)"), r);
        String same = call(JvmCompareTools.COMPARE_HEAP_DUMPS, Map.of("before", late.toString(), "after", late.toString()));
        assertTrue(same.startsWith("FAILED") && same.contains("same file"), same);
    }

    private static String histogram(long strings, long sessions) {
        return """
                 num     #instances         #bytes  class name (module)
                -------------------------------------------------------
                   1:         %d       %d  java.lang.String (java.base@21)
                   2:          %d        %d  com.acme.Session
                   3:          100        4000  java.lang.Object (java.base@21)
                Total         %d       %d
                """.formatted(strings, strings * 24, sessions, sessions * 64, strings + sessions + 100,
                strings * 24 + sessions * 64 + 4000);
    }

    @Test
    void twoHistogramsListWhatGrewMost() throws Exception {
        Path a = Files.writeString(dir.resolve("h1.txt"), histogram(1000, 10));
        Path b = Files.writeString(dir.resolve("h2.txt"), histogram(1200, 50_000));
        String r = call(JvmCompareTools.COMPARE_HISTOGRAMS, Map.of("before", a.toString(), "after", b.toString()));
        assertTrue(r.startsWith("h1.txt → h2.txt: 1,110 → 51,300 objects (+50,190)"), r);
        int session = r.indexOf("| com.acme.Session | 50,000 | +49,990 |");
        int string = r.indexOf("| java.lang.String | 1,200 | +200 |");
        assertTrue(session > 0 && string > session, "biggest growth first: " + r);
        assertFalse(r.contains("java.lang.Object |"), "what didn't grow isn't listed: " + r);

        // the window's histograms are logs: their handles work too
        String h1 = w.openGenerated(a);
        String h2 = w.openGenerated(b);
        assertTrue(call(JvmCompareTools.COMPARE_HISTOGRAMS, Map.of("before", h1, "after", h2)).contains("com.acme.Session"));
        Path notOne = Files.writeString(dir.resolve("app.log"), "2026-11-26 10:00:00 INFO hello\n");
        String bad = call(JvmCompareTools.COMPARE_HISTOGRAMS, Map.of("before", notOne.toString(), "after", b.toString()));
        assertTrue(bad.startsWith("FAILED") && bad.contains("not a heap histogram"), bad);
        String gone = call(JvmCompareTools.COMPARE_HISTOGRAMS, Map.of("before", dir.resolve("no.txt").toString(),
                "after", b.toString()));
        assertTrue(gone.startsWith("FAILED") && gone.contains("Not found") && gone.contains("no.txt"), gone);
    }

    @Test
    void twoProfilesSayWhatTakesABiggerShareNow() throws Exception {
        Path good = Files.writeString(dir.resolve("good.collapsed"), """
                java.lang.Thread.run;com.acme.Api.handle;com.acme.Db.query 50
                java.lang.Thread.run;com.acme.Api.handle;com.acme.Json.write 50
                """);
        Path slow = Files.writeString(dir.resolve("slow.collapsed"), """
                java.lang.Thread.run;com.acme.Api.handle;com.acme.Db.query 90
                java.lang.Thread.run;com.acme.Api.handle;com.acme.Json.write 10
                """);
        String r = call(JvmCompareTools.COMPARE_PROFILES, Map.of("before", good.toString(), "after", slow.toString()));
        assertTrue(r.startsWith("good.collapsed → slow.collapsed"), r);
        int bigger = r.indexOf("Bigger share now");
        int smaller = r.indexOf("Smaller share now");
        int db = r.indexOf("com.acme.Db.query  50.0% → 90.0% (+40.0 points)");
        int json = r.indexOf("com.acme.Json.write  50.0% → 10.0% (−40.0 points)");
        assertTrue(bigger >= 0 && db > bigger && smaller > db && json > smaller, r);
    }

    /** The fixture's three dumps (18:56:23 to :25), a minute later if {@code later}, its hot-loop thread another one. */
    private Path dump(String name, boolean later) throws Exception {
        String text = new String(getClass().getResourceAsStream("/threaddumps/jstack-21.txt").readAllBytes(),
                StandardCharsets.UTF_8);
        if (later) {
            text = text.replace("2026-10-22 18:56:2", "2026-10-22 18:57:2").replace("\"hot-loop\" #33 ",
                    "\"hot-loop-2\" #99 ");
        }
        return Files.writeString(dir.resolve(name), text);
    }

    @Test
    void threadDumpsInSeparateFilesShowWhatStaysStuckAndWhatChanged() throws Exception {
        Path later = dump("t2.txt", true);
        Path first = dump("t1.txt", false);
        String r = call(JvmCompareTools.COMPARE_THREAD_DUMPS, Map.of("logs", later + ", " + first));
        assertFalse(r.startsWith("FAILED"), r);
        assertTrue(r.startsWith("6 thread dumps:\n1. 2026-10-22 18:56:23"), "in time order, as written: " + r);
        assertTrue(r.contains("\n6. 2026-10-22 18:57:25, 32 threads, from log:1 t2.txt"), r);
        assertTrue(r.contains("Stuck in every dump"), r);
        assertTrue(r.contains("http-nio-8080-exec-"), "the blocked pool threads stay stuck: " + r);
        assertTrue(r.contains("1 new, 1 gone"), r);
        assertTrue(r.contains("New:\n- \"hot-loop-2\"") && r.contains("Gone:\n- \"hot-loop\""), r);
        assertTrue(r.contains("Lock waits in the last dump"), r);
        String one = call(JvmCompareTools.COMPARE_THREAD_DUMPS, Map.of("logs", first.toString()));
        assertTrue(one.startsWith("FAILED") && one.contains("analyze_threads"), one);
    }

    @Test
    void aRecordingOnlyWithThePersonsYesThenExplainedAndSaved() throws Exception {
        Process app = RecordingApp.start();
        try {
            LogCatalog.Service orders = new LogCatalog.Service("orders", "", List.of(dir.toString()), List.of(), "UTC",
                    "", List.of(new LogCatalog.JvmPlace("logsift.agent.RecordingApp", "", "")));
            LogCatalog c = LogCatalog.of(List.of(new LogCatalog.Entry(orders, dir.resolve("logs.json"), false)));
            w.setCatalog(() -> c);
            String nobody = call(JvmCompareTools.RECORD_PROFILE, Map.of("jvm", "orders"));
            assertTrue(nobody.startsWith("FAILED") && nobody.contains("can't be asked here"), nobody);
            saying(false);
            String no = call(JvmCompareTools.RECORD_PROFILE, Map.of("jvm", "orders", "seconds", 5));
            assertTrue(no.contains("The person said no to: record cpu of logsift.agent.RecordingApp on this computer for 5 "
                    + "seconds (Flight Recorder)"), no);
            assertTrue(asked.get(0).endsWith("key=null"), "asked every time, never for the chat: " + asked);

            JvmRules.DEFAULT.with(JvmRules.Action.PROFILE, JvmRules.Rule.NEVER).save(logsift.core.CacheSettings.home());
            String never = call(JvmCompareTools.RECORD_PROFILE, Map.of("jvm", "orders", "seconds", 5));
            assertTrue(never.startsWith("FAILED") && never.contains("doesn't let agents record"), never);
            assertEquals(JvmRules.Rule.ASK, JvmRules.DEFAULT.with(JvmRules.Action.PROFILE, JvmRules.Rule.ALLOW).profile(),
                    "never allowed without asking");
            Files.delete(JvmRules.file(logsift.core.CacheSettings.home()));

            String badMode = call(JvmCompareTools.RECORD_PROFILE, Map.of("jvm", "orders", "mode", "trace"));
            assertTrue(badMode.startsWith("FAILED") && badMode.contains("cpu, wall, alloc or lock"), badMode);
            String tooLong = call(JvmCompareTools.RECORD_PROFILE, Map.of("jvm", "orders", "seconds", 600));
            assertTrue(tooLong.startsWith("FAILED") && tooLong.contains("between 5 and 60"), tooLong);

            saying(true);
            String r = call(JvmCompareTools.RECORD_PROFILE, Map.of("jvm", "orders", "mode", "wall", "seconds", 5));
            assertFalse(r.startsWith("FAILED"), r);
            assertTrue(r.contains("Most time of their own:"), "explained like analyze_profile: " + r);
            assertTrue(r.contains("Saved (collapsed stacks"), r);
            String file = r.lines().filter(l -> l.startsWith("- Wall clock: ")).findFirst().orElseThrow()
                    .substring("- Wall clock: ".length());
            Path saved = Path.of(file);
            try {
                assertTrue(saved.startsWith(JvmCompareTools.profilesDir()), saved.toString());
                assertTrue(Files.size(saved) > 0);
                String again = call(TraceTools.ANALYZE_PROFILE, Map.of("path", saved.toString()));
                assertTrue(again.contains("Most time of their own"), "made for the agent, so readable: " + again);
                String known = call(JvmCompareTools.RECORD_PROFILE, Map.of("recording", "rec:999"));
                assertTrue(known.startsWith("FAILED") && known.contains("No recording rec:999"), known);
            } finally {
                try (var s = Files.list(JvmCompareTools.profilesDir())) {
                    for (Path p : s.toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        } finally {
            FixtureApp.stop(app);
        }
    }
}
