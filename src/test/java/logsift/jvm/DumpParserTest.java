package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;
import javax.management.ObjectName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Thread dumps taken from real JVMs (8, 11, 17, 21, 25) running the program in
 * {@code threaddumps/Fixture.java.txt}: two deadlocks (monitors, ReentrantLocks), five threads
 * queueing for a lock held by a sleeping thread, an idle pool and a thread spinning on the CPU.
 * Each jstack file holds three dumps about a second apart.
 */
class DumpParserTest {

    static String read(String name) throws IOException {
        try (InputStream in = DumpParserTest.class.getResourceAsStream("/threaddumps/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JThread named(ThreadDump d, String name) {
        return d.threads().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no thread " + name));
    }

    /** Thread headers in the first dump of a jstack file, counted the simple way. */
    private static int headers(String text) {
        int start = text.indexOf("Full thread dump");
        int end = text.indexOf("JNI global", start);
        int n = 0;
        for (String l : text.substring(start, end).split("\n")) {
            if (l.startsWith("\"") && (l.contains(" prio=") || l.contains(" tid="))) {
                n++;
            }
        }
        return n;
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 11, 17, 21, 25})
    void readsJstackOfEveryVersion(int version) throws IOException {
        String text = read("jstack-" + version + ".txt");
        assertTrue(DumpParser.looksLikeDump(text));
        List<ThreadDump> dumps = DumpParser.parse(text);
        assertEquals(3, dumps.size(), "three dumps");
        for (ThreadDump d : dumps) {
            assertNotEquals(ThreadDump.NO_TIME, d.time(), "time of each dump");
            assertEquals(headers(text), d.threads().size(), "every thread, none from the deadlock report");
            assertTrue(d.vm().contains("Server VM"), d.vm());
        }
        assertTrue(dumps.get(1).time() > dumps.get(0).time());
        ThreadDump d = dumps.get(2);
        Set<String> keys = new HashSet<>();
        d.threads().forEach(t -> assertTrue(keys.add(t.key()), "unique " + t.key()));

        JThread a = named(d, "worker-A");
        JThread b = named(d, "worker-B");
        assertEquals(JThread.State.BLOCKED, a.state());
        assertEquals("on object monitor", a.stateDetail());
        assertTrue(a.daemon());
        assertEquals(5, a.priority());
        assertEquals("Fixture.lambda$main$0", a.top().method());
        assertEquals("Fixture.java:14", a.top().location());
        assertEquals(a.blockedOn(), b.held().get(0), "A waits for what B holds");
        assertEquals(b.blockedOn(), a.held().get(0));
        assertEquals("java.lang.Object", a.blockedOn().type());

        JThread l1 = named(d, "lock-1");
        assertEquals(JThread.State.WAITING, l1.state());
        assertEquals(1, l1.owned().size(), "ownable synchronizers (-l)");
        assertTrue(l1.blockedOn().type().contains("ReentrantLock"));

        JThread writer = named(d, "db-writer");
        assertEquals(JThread.State.TIMED_WAITING, writer.state());
        assertEquals("sleeping", writer.stateDetail());
        JThread exec = named(d, "http-nio-8080-exec-3");
        assertEquals(exec.blockedOn(), writer.held().get(0));
        assertTrue(named(d, "Thread-0").isIdle(), "idle pool thread");
        assertFalse(exec.isIdle());
        if (version >= 11) {
            assertTrue(named(d, "hot-loop").cpuMs() > 0, "cpu=");
            assertTrue(named(d, "hot-loop").elapsedMs() > 0, "elapsed=");
        }
        if (version >= 21) {
            assertEquals("java.base@" + d.vm().replaceAll(".*\\((\\S+) .*", "$1"),
                    named(d, "main").top().module(), "module of a JDK frame");
        }
        // a lambda's hidden class keeps its name (the part after / is not a method of a module)
        JThread.Frame lambda = named(d, "hot-loop").frames().get(1);
        assertTrue(lambda.method().startsWith("Fixture$$Lambda"), lambda.method());
        assertEquals("", lambda.module());
        // JVM threads without a Java stack
        assertTrue(d.threads().stream().anyMatch(JThread::isJvmInternal));
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 11, 17, 21, 25})
    void findsWhatStandsOut(int version) throws IOException {
        List<ThreadDump> dumps = DumpParser.parse(read("jstack-" + version + ".txt"));
        ThreadDump d = dumps.get(2);
        List<List<JThread>> deadlocks = ThreadAnalysis.deadlocks(d);
        assertEquals(2, deadlocks.size(), "monitor and ReentrantLock deadlocks");
        Set<Set<String>> names = deadlocks.stream()
                .map(c -> c.stream().map(JThread::name).collect(Collectors.toSet())).collect(Collectors.toSet());
        assertEquals(Set.of(Set.of("worker-A", "worker-B"), Set.of("lock-1", "lock-2")), names);

        ThreadAnalysis.Contention c = ThreadAnalysis.contention(d).get(0);
        assertEquals(5, c.waiters().size());
        assertEquals("db-writer", c.holder().name());

        ThreadAnalysis.Report r = ThreadAnalysis.analyze(dumps);
        List<ThreadAnalysis.Kind> kinds = r.findings().stream().map(ThreadAnalysis.Finding::kind).toList();
        assertEquals(ThreadAnalysis.Kind.DEADLOCK, kinds.get(0));
        assertEquals(ThreadAnalysis.Kind.DEADLOCK, kinds.get(1));
        assertEquals(ThreadAnalysis.Kind.CONTENTION, kinds.get(2));
        ThreadAnalysis.Finding queue = r.findings().get(2);
        assertEquals("5 threads wait for an Object held by db-writer", queue.title());
        assertTrue(queue.detail().contains("sleeping"), queue.detail());
        assertTrue(queue.detail().contains("last 3 dumps"), queue.detail());
        assertEquals(6, queue.keys().size(), "holder and waiters");
        assertTrue(kinds.contains(ThreadAnalysis.Kind.BUSY_POOL), "all http threads busy: " + kinds);
        if (version >= 11) {
            // CPU use is known: one finding for the spinning thread, saying both
            ThreadAnalysis.Finding hot = r.findings().stream().filter(f -> f.kind() == ThreadAnalysis.Kind.HOT)
                    .findFirst().orElseThrow(() -> new AssertionError("hot thread: " + kinds));
            assertTrue(hot.title().startsWith("hot-loop uses "), hot.title());
            assertTrue(hot.detail().endsWith("in the same place in all 3 dumps"), hot.detail());
            assertTrue(r.findings().stream().noneMatch(f -> f.kind() == ThreadAnalysis.Kind.STUCK
                    && f.keys().contains(named(d, "hot-loop").key())));
        } else {
            assertTrue(r.findings().stream().anyMatch(f -> f.kind() == ThreadAnalysis.Kind.STUCK
                    && f.title().startsWith("hot-loop stuck in")), "spinning in one place");
        }
        assertTrue(r.problems() >= 4);
        assertTrue(r.summary().startsWith("Dump 3 of 3 · "), r.summary());

        // one dump: no "stuck", and the deadlocks still show
        ThreadAnalysis.Report one = ThreadAnalysis.analyze(List.of(d));
        assertTrue(one.findings().stream().noneMatch(f -> f.kind() == ThreadAnalysis.Kind.STUCK));
        assertEquals(ThreadAnalysis.Kind.DEADLOCK, one.findings().get(0).kind());
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 17, 25})
    void writesDumpsThatReadBackTheSame(int version) throws IOException {
        List<ThreadDump> dumps = DumpParser.parse(read("jstack-" + version + ".txt"));
        List<ThreadDump> again = DumpParser.parse(DumpText.write(dumps));
        assertEquals(dumps.size(), again.size());
        for (int i = 0; i < dumps.size(); i++) {
            assertEquals(dumps.get(i).time(), again.get(i).time());
            assertEquals(dumps.get(i).vm(), again.get(i).vm());
            assertEquals(dumps.get(i).threads(), again.get(i).threads());
        }
    }

    @Test
    void readsJsonDumps() throws IOException {
        for (String f : List.of("dump-21.json", "dump-25.json")) {
            String text = read(f);
            assertTrue(DumpParser.looksLikeDump(text), f);
            List<ThreadDump> dumps = DumpParser.parse(text);
            assertEquals(1, dumps.size(), f);
            ThreadDump d = dumps.get(0);
            assertNotEquals(ThreadDump.NO_TIME, d.time());
            assertTrue(d.vm().startsWith("Java 2"), d.vm());
            JThread a = named(d, "worker-A");
            assertTrue(a.top().method().equals("Fixture.lambda$main$0"), a.top().method());
            assertEquals("Fixture.java:14", a.top().location());
            if (f.contains("25")) {
                assertEquals(JThread.State.BLOCKED, a.state());
                assertEquals("java.lang.Object", a.blockedOn().type());
                assertEquals(1, ThreadAnalysis.deadlocks(d).size(), "the monitor deadlock (JSON has no lock owners)");
                assertEquals("db-writer", ThreadAnalysis.contention(d).get(0).holder().name());
                assertEquals("java.base", named(d, "main").top().module());
            } else {
                assertEquals(JThread.State.UNKNOWN, a.state(), "JDK 21 JSON has no states");
            }
        }
    }

    @Test
    void readsPlainDumpToFile() throws IOException {
        for (int v : new int[] {21, 25}) {
            String text = read("dump-" + v + "-plain.txt");
            assertTrue(DumpParser.looksLikeDump(text));
            List<ThreadDump> dumps = DumpParser.parse(text);
            assertEquals(1, dumps.size());
            ThreadDump d = dumps.get(0);
            assertEquals(DumpParser.parse(read("dump-" + v + ".json")).get(0).threads().size(), d.threads().size(),
                    "same threads as the JSON file");
            assertEquals("Java " + (v == 21 ? "21.0.11" : "25.0.2"), d.vm());
            JThread a = named(d, "worker-A");
            assertEquals("Fixture.lambda$main$0", a.top().method());
            if (v == 25) {
                assertEquals(JThread.State.BLOCKED, a.state());
                JThread fin = named(d, "Finalizer");
                assertEquals("java.lang.ref.ReferenceQueue$Lock", fin.waitingOn().type());
                assertTrue(fin.held().isEmpty(), "the lock it waits on is let go");
            }
        }
    }

    @Test
    void readsVirtualThreads() throws IOException {
        for (String f : List.of("virt-21.json", "virt-25.json", "virt-21.txt", "virt-25.txt")) {
            ThreadDump d = DumpParser.parse(read(f)).get(0);
            List<JThread> virt = d.threads().stream().filter(JThread::virtual).toList();
            assertEquals(4, virt.size(), f);
            assertTrue(virt.stream().anyMatch(t -> t.name().equals("named-virtual")), f);
            assertTrue(virt.stream().anyMatch(t -> t.displayName().startsWith("virtual #")), f);
            assertTrue(ThreadAnalysis.summary(List.of(d), 0).endsWith("4 virtual"), f);
            assertTrue(ThreadAnalysis.families(d).stream()
                    .anyMatch(g -> g.name().equals("(virtual threads)") && g.threads().size() == 3), f);
        }
    }

    @Test
    void findsDumpsInsideALog() throws IOException {
        String dump = read("jstack-17.txt");
        int one = dump.indexOf("Full thread dump");
        int two = dump.indexOf("Full thread dump", one + 1);
        String first = dump.substring(0, dump.lastIndexOf('\n', two - 22));
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            log.append("2026-10-22 10:00:").append(String.format("%02d", i)).append(".123 INFO  [main] app: request ")
                    .append(i).append('\n');
        }
        log.append(first.substring(first.indexOf("Full thread dump"))); // kill -3: no time line
        for (int i = 0; i < 50; i++) {
            log.append("2026-10-22 10:01:00.000 ERROR [http-1] app: \"quoted\" prio=1 failed ").append(i).append('\n');
        }
        List<ThreadDump> dumps = DumpParser.parse(log.toString());
        assertEquals(1, dumps.size());
        assertEquals(headers(dump), dumps.get(0).threads().size());
        assertEquals(50, dumps.get(0).line(), "where it starts");

        // docker logs --timestamps: a time in front of every line (its digits vary in length)
        StringBuilder docker = new StringBuilder();
        int n = 0;
        for (String l : dump.split("\n", -1)) {
            docker.append("2026-10-22T10:56:30.").append(123456 + n * 7919 % 900000)
                    .append(n % 3 == 0 ? "1" : "").append("Z ").append(l).append('\n');
            n++;
        }
        List<ThreadDump> dd = DumpParser.parse(docker.toString());
        assertEquals(3, dd.size());
        assertEquals(headers(dump), dd.get(0).threads().size());
        assertEquals(JThread.State.BLOCKED, named(dd.get(0), "worker-A").state());
        assertEquals(1, named(dd.get(0), "lock-1").owned().size());
    }

    @Test
    void textThatIsNotADump() {
        assertFalse(DumpParser.looksLikeDump("2026-10-22 10:00:00 INFO hello\n#1 not a thread\n"));
        assertTrue(DumpParser.parse("just a log\n\"x\" said prio=5\n").isEmpty());
        assertTrue(DumpParser.parse("").isEmpty());
        assertTrue(DumpParser.parse("{\"threadDump\": 3}").isEmpty());
    }

    @Test
    void namesThreadFamilies() {
        assertEquals("http-nio-8080-exec-∗", JThread.family("http-nio-8080-exec-12"));
        assertEquals("pool-3-thread-∗", JThread.family("pool-3-thread-7"));
        assertEquals("GC task thread#∗ (ParallelGC)", JThread.family("GC task thread#0 (ParallelGC)"));
        assertEquals("GC Thread#∗", JThread.family("GC Thread#3"));
        assertEquals("Thread-∗", JThread.family("Thread-3"));
        assertEquals("main", JThread.family("main"));
        assertEquals("C2 CompilerThread∗", JThread.family("C2 CompilerThread1"));
        assertEquals("http2Handler", JThread.family("http2Handler"));
        assertEquals("(unnamed)", JThread.family(""));
    }

    @Test
    void comparesTwoDumps() throws IOException {
        List<ThreadDump> dumps = DumpParser.parse(read("jstack-25.txt"));
        ThreadDump a = dumps.get(0);
        // a dump with one thread gone and one new
        List<JThread> ts = new java.util.ArrayList<>(dumps.get(2).threads());
        JThread gone = ts.remove(0);
        ts.add(new JThread("new-one", 9999, "1", false, false, 5, JThread.State.RUNNABLE, "",
                List.of(new JThread.Frame("x.Y.z", "Y.java:1", "", List.of())), List.of(), -1, -1));
        ThreadDump b = new ThreadDump(dumps.get(2).time(), "", ts, -1);
        List<ThreadAnalysis.Change> ch = ThreadAnalysis.compare(a, b);
        assertEquals(ThreadAnalysis.ChangeKind.NEW, ch.get(0).kind());
        assertEquals("new-one", ch.get(0).thread().name());
        assertTrue(ch.stream().anyMatch(c -> c.kind() == ThreadAnalysis.ChangeKind.GONE
                && c.before().key().equals(gone.key())));
        assertTrue(ch.stream().anyMatch(c -> c.kind() == ThreadAnalysis.ChangeKind.SAME
                && c.thread().name().equals("worker-A")));
        assertEquals(a.threads().size() + 1, ch.size());
    }

    @Test
    void readsThisJvmsOwnDumpWithADeadlock() throws Exception {
        Object x = new Object();
        Object y = new Object();
        CountDownLatch both = new CountDownLatch(2);
        Thread t1 = new Thread(() -> {
            synchronized (x) {
                both.countDown();
                await(both);
                synchronized (y) {
                    both.getCount();
                }
            }
        }, "test-deadlock-1");
        Thread t2 = new Thread(() -> {
            synchronized (y) {
                both.countDown();
                await(both);
                synchronized (x) {
                    both.getCount();
                }
            }
        }, "test-deadlock-2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
        long until = System.currentTimeMillis() + 5000;
        while ((t1.getState() != Thread.State.BLOCKED || t2.getState() != Thread.State.BLOCKED)
                && System.currentTimeMillis() < until) {
            Thread.sleep(10);
        }
        String text = (String) java.lang.management.ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "threadPrint",
                new Object[] {new String[] {"-l"}}, new String[] {String[].class.getName()});
        ThreadDump d = DumpParser.parse(text).get(0);
        assertNull(d.find("nope"));
        List<List<JThread>> cycles = ThreadAnalysis.deadlocks(d);
        assertTrue(cycles.stream().anyMatch(c -> c.stream().map(JThread::name).collect(Collectors.toSet())
                .equals(Set.of("test-deadlock-1", "test-deadlock-2"))), "found: " + cycles);
        JThread me = d.threads().stream().filter(t -> t.name().equals(Thread.currentThread().getName()))
                .findFirst().orElseThrow();
        assertEquals(JThread.State.RUNNABLE, me.state());
        assertTrue(me.cpuMs() >= 0, "cpu= on JDK 11+");
    }

    private static void await(CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
