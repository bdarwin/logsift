package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/** Profiles, read and made: collapsed stacks, and real runs against {@link FixtureApp}. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProfilerTest {
    @TempDir
    static Path dir;

    private Process app;
    private LocalJvms.Session session;
    private JvmProcess jvm;

    @BeforeAll
    void start() throws Exception {
        app = LocalJvmsTest.startFixture();
        session = LocalJvms.attach(app.pid(), null);
        jvm = new JvmProcess(app.pid(), "FixtureApp", "", "", "", false);
    }

    @AfterAll
    void stop() throws Exception {
        if (session != null) {
            session.close();
        }
        app.getOutputStream().close();
        app.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        app.destroyForcibly();
    }

    /** The spinning thread's method takes most of the CPU. */
    static void checkHotLoop(Profile p) {
        assertTrue(p.total() > 0, "samples: " + p.warnings());
        long spin = p.find("logsift.jvm.FixtureApp.lambda$main$8").stream().mapToLong(Profile.Node::total).sum();
        assertTrue(spin * 2 > p.total(), "the hot loop has most samples: " + spin + " of " + p.total()
                + "; " + p.hottest(5));
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
        // written back and read again: the same tree
        Profile again = Profile.fromCollapsed(p.toCollapsed(), "t", "samples");
        assertEquals(p.toCollapsed(), again.toCollapsed());
        assertEquals(p.hottest(10), again.hottest(10));
        // saved and opened
        Path f = dir.resolve("saved.collapsed.txt");
        Files.writeString(f, p.toCollapsed());
        assertEquals(11, Profiler.open(f).total());
        assertEquals("12.0 MB", new Profile("x", "bytes").amount(12L * 1024 * 1024));
    }

    @Test
    void profilesWithAsyncProfiler() throws Exception {
        assumeTrue(Agents.has(Agents.localPlatform()), "async-profiler runs here");
        Profiler.Target t = Profiler.local(jvm, session);
        assertEquals(Profiler.Engine.ASYNC_PROFILER, t.engines().get(0));
        double[] progress = {0};
        Profile p = t.run(Profiler.Engine.ASYNC_PROFILER, Profiler.Mode.CPU, 2, f -> progress[0] = f, () -> false);
        assertNotNull(p);
        checkHotLoop(p);
        assertTrue(progress[0] > 0.5, "progress " + progress[0]);
        assertTrue(p.title().startsWith("CPU of FixtureApp"), p.title());
    }

    @Test
    void profilesWithFlightRecorder() throws Exception {
        Profiler.Target t = Profiler.local(jvm, session);
        assertTrue(t.engines().contains(Profiler.Engine.JFR));
        Profile p = t.run(Profiler.Engine.JFR, Profiler.Mode.CPU, 2, null, () -> false);
        checkHotLoop(p);
        // a recording opened from a file
        Path jfr = dir.resolve("rec.jfr");
        String name = "t" + System.nanoTime();
        session.diagnostic("jfrStart", "name=" + name, "settings=profile");
        Thread.sleep(1500);
        session.diagnostic("jfrStop", "name=" + name, "filename=" + jfr);
        checkHotLoop(Profiler.open(jfr));
    }

    @Test
    void cancels() throws Exception {
        Profiler.Target t = Profiler.local(jvm, session);
        long start = System.currentTimeMillis();
        Profile p = t.run(t.engines().get(0), Profiler.Mode.WALL, 30, null,
                () -> System.currentTimeMillis() - start > 1000);
        assertEquals(null, p);
        assertTrue(System.currentTimeMillis() - start < 15_000, "stopped early");
    }
}
