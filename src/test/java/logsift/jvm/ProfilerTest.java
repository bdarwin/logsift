package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Profiles, read and made: collapsed stacks, and real runs of every mode against
 * {@link FixtureApp} (with its memory-leaking threads), plus heap histograms and heap dumps.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfilerTest {
    @TempDir
    static Path dir;

    private Process app;
    private LocalJvms.Session session;
    private Profiler.Target target;

    @BeforeAll
    void start() throws Exception {
        app = FixtureApp.start(true);
        session = LocalJvms.attach(app.pid(), null);
        target = Profiler.local(new JvmProcess(app.pid(), "FixtureApp", "", "", "", false), session);
    }

    @AfterAll
    void stop() throws Exception {
        if (session != null) {
            session.close();
        }
        FixtureApp.stop(app);
    }

    /** The total of the frames with this name (outermost occurrences). */
    static long weight(Profile p, String frame) {
        return p.find(frame).stream().filter(n -> {
            for (Profile.Node a = n.parent(); a != null; a = a.parent()) {
                if (a.name().equals(frame)) {
                    return false;
                }
            }
            return true;
        }).mapToLong(Profile.Node::total).sum();
    }

    /** The spinning thread's method is among the CPU users, and the fixture's own code has a good share. */
    static void checkHotLoop(Profile p) {
        assertTrue(p.total() > 0, "samples: " + p.warnings());
        long spin = weight(p, "logsift.jvm.FixtureApp.lambda$main$8");
        assertTrue(spin > 0, "the hot loop: " + p.hottest(5));
        long fixture = fixtureWeight(p.root());
        // CI machines differ in how samples fall (Flight Recorder skips threads in native code)
        assertTrue(fixture * 5 > p.total(), "the fixture's threads use much of the CPU: " + fixture
                + " of " + p.total() + "; " + p.hottest(5));
    }

    /**
     * For Flight Recorder: the fixture's own code is sampled. (It samples only threads running
     * Java code; the busy loop is mostly in native code, so it may be missed altogether.)
     */
    static void checkFixtureSampled(Profile p) {
        assertTrue(p.total() > 0, "samples: " + p.warnings());
        assertTrue(fixtureWeight(p.root()) > 0, "the fixture's code: " + p.hottest(5));
    }

    /** The weight under the fixture's own frames (outermost ones). */
    private static long fixtureWeight(Profile.Node n) {
        long sum = 0;
        for (Profile.Node c : n.children()) {
            sum += c.name().startsWith("logsift.jvm.FixtureApp") ? c.total() : fixtureWeight(c);
        }
        return sum;
    }

    private static boolean asyncProfilerHere() {
        return Agents.has(Agents.localPlatform());
    }

    private List<Profile> run(Profiler.Engine e, Profiler.Mode m, int s) throws Exception {
        return target.run(Profiler.Request.of(e, m, s), null, () -> false);
    }

    @Test
    void readsCollapsedStacks() throws Exception {
        String text = """
                Profiling for 10 seconds
                java/lang/Thread.run_[j];com/acme/Api.handle_[j];com/acme/Db.query_[i];libc.so.6 5
                java/lang/Thread.run_[j];com/acme/Api.handle_[j];com/acme/Api.handle_[j];com/acme/Json.write_[0] 3
                java/lang/Thread.run_[j];com/acme/Api.handle_[j] 2
                thread_start;Thread::call_run;GCTaskThread::run_[k] 1
                Done
                """;
        Profile p = Profile.fromCollapsed(text, "t", "samples");
        assertEquals(11, p.total());
        Profile.Node run = p.root().children().get(0);
        assertEquals("java.lang.Thread.run", run.name());
        assertEquals(Profile.Kind.JAVA, run.kind());
        assertEquals(10, run.total());
        List<Profile.Method> hot = p.hottest(10);
        assertEquals("libc.so.6", hot.get(0).name(), "the leaf has the time of its own");
        assertEquals(5, hot.get(0).self());
        Profile.Method query = hot.stream().filter(m -> m.name().equals("com.acme.Db.query")).findFirst().orElseThrow();
        assertEquals(Profile.Kind.INLINED, query.kind());
        assertEquals(0, query.self());
        assertEquals(5, query.total());
        Profile.Method api = hot.stream().filter(m -> m.name().equals("com.acme.Api.handle")).findFirst().orElseThrow();
        assertEquals(2, api.self());
        assertEquals(10, api.total(), "a recursive call is counted once");
        assertEquals(Profile.Kind.NATIVE, p.find("libc.so.6").get(0).kind());
        assertEquals(Profile.Kind.KERNEL, p.find("GCTaskThread::run").get(0).kind());
        assertEquals(Profile.Kind.JAVA, Profile.kindOf("com.acme.Api.handle"));
        assertEquals(Profile.Kind.JAVA, Profile.kindOf("java/util/HashMap.get"));
        assertEquals(Profile.Kind.NATIVE, Profile.kindOf("Thread::call_run"));
        assertEquals("1,234 samples", p.amount(1234));
        assertEquals("3 calls", new Profile("x", "calls").amount(3));
        // written back and read again: the same tree
        Profile again = Profile.fromCollapsed(p.toCollapsed(), "t", "samples");
        assertEquals(p.toCollapsed(), again.toCollapsed());
        assertEquals(p.hottest(10), again.hottest(10));
        Path f = dir.resolve("saved.collapsed.txt");
        Files.writeString(f, p.toCollapsed());
        assertEquals(11, Profiler.open(f).get(0).total());
        assertEquals("12.0 MB", new Profile("x", "bytes").amount(12L * 1024 * 1024));

        // turned around: the innermost frames first
        Profile rev = p.reversed();
        assertEquals(11, rev.total());
        assertEquals(5, rev.root().children().stream().filter(n -> n.name().equals("libc.so.6")).findFirst()
                .orElseThrow().total());
        // only, and without, the stacks through a frame
        assertEquals(5, p.filtered("com.acme.Db.query", true).total());
        assertEquals(6, p.filtered("com.acme.Db.query", false).total());
        // who calls Api.handle: everything through it, Api.handle on top, then its callers
        Profile callers = p.callersOf("com.acme.Api.handle");
        assertEquals(10, callers.total());
        Profile.Node top = callers.root().children().get(0);
        assertEquals("com.acme.Api.handle", top.name());
        assertEquals(10, top.total(), "the recursive stack counts once, at its innermost call");
        // the same place in another profile
        Profile.Node db = p.find("com.acme.Db.query").get(0);
        assertEquals(db.total(), again.same(db).total());
        assertNull(rev.same(db));
    }

    @Test
    void buildsAsyncProfilerCommands() {
        long pid = 42;
        assertEquals(List.of("-d", "5", "-e", "alloc", "--live", "-o", "collapsed", "-a", "--total", "42"),
                Profiler.asprofArgs(Profiler.Request.of(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.LIVE, 5), pid, null));
        assertEquals(List.of("-d", "5", "--trace", "com.acme.Api.handle:20ms", "-t", "-o", "collapsed", "-a", "--total", "42"),
                Profiler.asprofArgs(new Profiler.Request(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.METHOD_LATENCY, 5,
                        true, "com.acme.Api.handle", 20, ""), pid, null));
        assertEquals(List.of("-d", "5", "--all", "-o", "jfr", "-f", "/tmp/x.jfr", "42"),
                Profiler.asprofArgs(Profiler.Request.of(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.ALL, 5), pid, "/tmp/x.jfr"));
        assertEquals(List.of("-d", "5", "-e", "cache-misses", "-o", "collapsed", "-a", "42"),
                Profiler.asprofArgs(new Profiler.Request(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.HARDWARE, 5,
                        false, "", 0, "cache-misses"), pid, null));
        // what can't run says why
        assertNotNull(new Profiler.Request(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.METHOD_CALLS, 5, false,
                "rm -rf /", 0, "").problem());
        assertNull(new Profiler.Request(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.METHOD_CALLS, 5, false,
                "java.io.FileInputStream.read*", 0, "").problem());
        assertNotNull(Profiler.Request.of(Profiler.Engine.JFR, Profiler.Mode.LIVE, 5).problem());
        // the hardware events async-profiler lists on Linux (placeholders left out)
        assertEquals(List.of("page-faults", "context-switches", "cycles", "cache-misses"), Profiler.perfEvents("""
                Basic events:
                  cpu
                  alloc
                Java method calls:
                  ClassName.methodName
                Perf events:
                  page-faults
                  context-switches
                  cycles
                  cache-misses
                  rNNN
                  pmu/event-descriptor/
                  mem:breakpoint
                  trace:tracepoint
                """));
        assertEquals("gcClassHistogram", LocalJvms.jmxName("GC.class_histogram"));
        assertEquals("threadPrint", LocalJvms.jmxName("Thread.print"));
        assertEquals("jfrStart", LocalJvms.jmxName("JFR.start"));
    }

    @Test
    void profilesWithAsyncProfiler() throws Exception {
        assumeTrue(asyncProfilerHere(), "async-profiler runs here");
        assertEquals(Profiler.Engine.ASYNC_PROFILER, target.engines().get(0));
        assertTrue(target.modes(Profiler.Engine.ASYNC_PROFILER).containsAll(List.of(Profiler.Mode.LIVE,
                Profiler.Mode.NATIVE_LEAKS, Profiler.Mode.METHOD_LATENCY, Profiler.Mode.ALL)));
        double[] progress = {0};
        List<Profile> p = target.run(Profiler.Request.of(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.CPU, 2),
                f -> progress[0] = f, () -> false);
        assertEquals(1, p.size());
        checkHotLoop(p.get(0));
        assertTrue(progress[0] > 0.5, "progress " + progress[0]);
        assertTrue(p.get(0).title().startsWith("CPU of FixtureApp"), p.get(0).title());
    }

    @Test
    void findsMemoryLeaks() throws Exception {
        assumeTrue(asyncProfilerHere(), "async-profiler runs here");
        // live objects: the kept byte arrays, allocated by the leaking thread
        Profile live = run(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.LIVE, 2).get(0);
        assertTrue(live.total() > 0, "live objects: " + live.warnings());
        assertTrue(weight(live, "logsift.jvm.FixtureApp.lambda$main$9") > 0, live.hottest(5).toString());
        assertTrue(live.find("byte[]").size() > 0, "the class allocated is the top frame");
        // native memory: direct buffers
        Profile nat = run(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.NATIVE_MEMORY, 2).get(0);
        assertTrue(weight(nat, "java.nio.ByteBuffer.allocateDirect") > 0, nat.hottest(8).toString());
        // native leaks: the same buffers, never freed
        List<Profile> leaks = run(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.NATIVE_LEAKS, 3);
        assertTrue(leaks.get(0).title().startsWith("Native memory leaks"), leaks.get(0).title());
        assertTrue(weight(leaks.get(0), "java.nio.ByteBuffer.allocateDirect") > 0,
                "leaked: " + leaks.get(0).hottest(8) + " " + leaks.get(0).warnings());
    }

    @Test
    void tracesAMethodAndSplitsByThread() throws Exception {
        assumeTrue(asyncProfilerHere(), "async-profiler runs here");
        Profile calls = target.run(new Profiler.Request(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.METHOD_CALLS, 2,
                false, "logsift.jvm.FixtureApp.work", 0, ""), null, () -> false).get(0);
        assertTrue(calls.total() > 50, "calls of work(): " + calls.total());
        assertEquals("calls", calls.unit());
        Profile slow = target.run(new Profiler.Request(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.METHOD_LATENCY, 2,
                false, "logsift.jvm.FixtureApp.work", 1, ""), null, () -> false).get(0);
        assertTrue(slow.total() > 1_000_000_000L, "about 2 s in work(), in ns: " + slow.total());
        Profile byThread = target.run(new Profiler.Request(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.CPU, 2,
                true, "", 0, ""), null, () -> false).get(0);
        Set<String> threads = byThread.root().children().stream().map(Profile.Node::name).collect(Collectors.toSet());
        assertTrue(threads.stream().anyMatch(t -> t.startsWith("[hot-loop")), "per thread: " + threads);
        assertEquals(Profile.Kind.THREAD, byThread.root().children().get(0).kind());
    }

    @Test
    void everythingAtOnce() throws Exception {
        assumeTrue(asyncProfilerHere(), "async-profiler runs here");
        List<Profile> views = run(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.ALL, 3);
        Set<String> kinds = views.stream().map(v -> v.title().replaceFirst(" of .*", "")).collect(Collectors.toSet());
        assertTrue(kinds.containsAll(Set.of("Wall clock", "Allocations", "Live objects (memory leaks)", "Native memory")),
                "views: " + kinds);
        assertTrue(views.stream().allMatch(v -> v.total() > 0 || v == views.get(0)));
    }

    @Test
    void profilesWithFlightRecorder() throws Exception {
        assertTrue(target.engines().contains(Profiler.Engine.JFR));
        List<Profile> views = run(Profiler.Engine.JFR, Profiler.Mode.CPU, 2);
        assertTrue(views.get(0).title().startsWith("CPU of"));
        checkFixtureSampled(views.get(0));
        assertTrue(views.stream().anyMatch(v -> v.title().startsWith("Allocations") && v.total() > 0),
                "the same recording has allocations too: " + views.stream().map(Profile::title).toList());
        // a recording opened from a file: every kind it has
        Path jfr = dir.resolve("rec.jfr");
        String name = "t" + System.nanoTime();
        session.diagnostic("JFR.start", "name=" + name, "settings=profile");
        Thread.sleep(1500);
        session.diagnostic("JFR.stop", "name=" + name, "filename=" + jfr);
        List<Profile> opened = Profiler.open(jfr);
        checkFixtureSampled(opened.get(0));
        assertTrue(opened.size() > 1, "several views: " + opened.stream().map(Profile::title).toList());
    }

    @Test
    void cancels() throws Exception {
        long start = System.currentTimeMillis();
        List<Profile> p = target.run(Profiler.Request.of(target.engines().get(0), Profiler.Mode.WALL, 30), null,
                () -> System.currentTimeMillis() - start > 1000);
        assertNull(p);
        assertTrue(System.currentTimeMillis() - start < 15_000, "stopped early");
    }

    @Test
    void heapHistogramsAndDumps() throws Exception {
        for (String f : List.of("histogram-25.txt", "histogram-8.txt")) {
            HeapHistogram h = HeapHistogram.parse(DumpParserTest.read(f), 0);
            assertTrue(h.rows().size() > 200, f);
            assertTrue(h.bytes() > 0 && h.instances() > 0, f);
            assertEquals(h.rows().get(0).bytes(), h.rows().stream().mapToLong(HeapHistogram.Row::bytes).max().orElse(0));
        }
        HeapHistogram h25 = HeapHistogram.parse(DumpParserTest.read("histogram-25.txt"), 0);
        assertEquals("byte[]", h25.rows().get(0).displayName());
        assertEquals("java.base@25.0.2", h25.rows().get(0).module());
        assertEquals("java.lang.Object[]", HeapHistogram.readable("[Ljava.lang.Object;"));
        assertEquals("int[][]", HeapHistogram.readable("[[I"));
        assertEquals("char[]", HeapHistogram.parse(DumpParserTest.read("histogram-8.txt"), 0).rows().get(0).displayName());

        // live: two histograms a little apart; the leak grows
        HeapHistogram a = HeapHistogram.parse(target.diagnostic("GC.class_histogram"), 0);
        Thread.sleep(1500);
        HeapHistogram b = HeapHistogram.parse(target.diagnostic("GC.class_histogram", "-all"), 1);
        assertTrue(a.rows().size() > 100);
        List<HeapHistogram.Change> grew = HeapHistogram.compare(a, b);
        assertTrue(grew.get(0).bytesGrown() >= grew.get(grew.size() - 1).bytesGrown(), "biggest growth first");
        assertTrue(target.diagnostic("GC.heap_info").contains("heap"));

        // a heap dump: an .hprof file
        Path hprof = dir.resolve("heap.hprof");
        target.heapDump(hprof, null, () -> false);
        byte[] head = new byte[12];
        try (var in = Files.newInputStream(hprof)) {
            assertEquals(12, in.read(head));
        }
        assertEquals("JAVA PROFILE", new String(head, java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(Files.size(hprof) > 1_000_000);
    }
}
