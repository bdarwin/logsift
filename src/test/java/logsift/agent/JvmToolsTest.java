package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import logsift.jvm.FixtureApp;
import logsift.jvm.JvmLink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A peek into a running JVM for the assistant: where each service's JVM runs (Log Locations), a
 * thread dump and a heap histogram, each only after the person says yes, and short.
 */
class JvmToolsTest {
    static Process app;
    static int port;

    @TempDir
    Path dir;
    Workspace w;
    final List<String> asked = new ArrayList<>();
    final List<String> keys = new ArrayList<>();

    @BeforeAll
    static void startApp() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            port = s.getLocalPort();
        }
        app = FixtureApp.start(false, null, "-Dcom.sun.management.jmxremote.port=" + port,
                "-Dcom.sun.management.jmxremote.rmi.port=" + port, "-Djava.rmi.server.hostname=127.0.0.1",
                "-Dcom.sun.management.jmxremote.authenticate=false", "-Dcom.sun.management.jmxremote.ssl=false");
    }

    @AfterAll
    static void stopApp() throws Exception {
        FixtureApp.stop(app);
    }

    @BeforeEach
    void start() {
        w = new Workspace(new Workspace.Options(new Scope(List.of(dir), dir), Masking.Options.DEFAULT,
                Workspace.DEFAULT_MAX_CHARS, 30_000, List.of(), null));
        LogCatalog.Service orders = new LogCatalog.Service("orders", "Takes orders", List.of(dir.toString()), List.of(),
                "UTC", "", List.of(new LogCatalog.JvmPlace("logsift.jvm.FixtureApp", "", ""),
                new LogCatalog.JvmPlace("logsift.jvm.FixtureApp", "", "127.0.0.1:" + port)));
        LogCatalog c = LogCatalog.of(List.of(new LogCatalog.Entry(orders, dir.resolve("logs.json"), false)));
        w.setCatalog(() -> c);
    }

    @AfterEach
    void stop() throws Exception {
        w.close();
        java.nio.file.Files.deleteIfExists(JvmRules.file(logsift.core.CacheSettings.home()));
    }

    private static void rules(JvmRules r) throws Exception {
        r.save(logsift.core.CacheSettings.home());
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
                asked.add(what);
                keys.add(String.valueOf(key));
                if (!yes) {
                    throw new Tool.Failure("The person said no to: " + what + ".");
                }
            }
        });
    }

    @Test
    void placesAreReadAndWrittenWithTheService() throws Exception {
        LogCatalog.JvmPlace ssh = new LogCatalog.JvmPlace("orders.jar", "deploy@prod-7:2222", "");
        assertEquals(JvmLink.Kind.SSH, ssh.kindOf());
        assertEquals("deploy", ssh.user());
        assertEquals("prod-7", ssh.host());
        assertEquals(2222, ssh.port());
        LogCatalog.JvmPlace jmx = new LogCatalog.JvmPlace("orders.jar", "", "prod-8:9010");
        assertEquals(JvmLink.Kind.JMX, jmx.kindOf());
        assertEquals("", jmx.user());
        assertEquals(9010, jmx.port());
        assertEquals("orders.jar on this computer", new LogCatalog.JvmPlace("orders.jar", "", "").label());
        LogCatalog.Service s = new LogCatalog.Service("orders", "", List.of("/var/log/orders"), List.of(), "", "",
                List.of(ssh, jmx));
        String json = LogCatalog.format(List.of(s));
        assertTrue(json.contains("\"ssh\": \"deploy@prod-7:2222\"") && !json.contains("password"), json);
        assertEquals(List.of(s), LogCatalog.parse(json));
        String none = LogCatalog.format(List.of(new LogCatalog.Service("a", "", List.of(), List.of(), "", "")));
        assertTrue(!none.contains("jvms"), "a service without JVMs is written as before");
    }

    @Test
    void listsTheServicesJvmsWithHandles() {
        String r = call(JvmTools.LIST_JVMS, Map.of("service", "orders"));
        assertTrue(r.startsWith("jvm:1  logsift.jvm.FixtureApp on this computer · service orders"), r);
        assertTrue(r.contains("jvm:2  logsift.jvm.FixtureApp on 127.0.0.1:" + port + " (JMX) · service orders"), r);
        assertTrue(r.contains("as the person allows: thread dumps asked each time, histograms asked each time, heap dumps "
                + "asked each time (heaps up to 8 GB)."), r);
    }

    @Test
    void aThreadDumpOnlyWithThePersonsYes() {
        String nobody = call(JvmTools.THREAD_DUMP, Map.of("jvm", "orders"));
        assertTrue(nobody.startsWith("FAILED") && nobody.contains("can't be asked here"), nobody);
        saying(false);
        String no = call(JvmTools.THREAD_DUMP, Map.of("jvm", "orders"));
        assertTrue(no.contains("The person said no to: take a thread dump of logsift.jvm.FixtureApp on this computer"), no);
        saying(true);
        String r = call(JvmTools.THREAD_DUMP, Map.of("jvm", "jvm:1"));
        assertTrue(r.startsWith("Thread dump of logsift.jvm.FixtureApp (pid " + app.pid() + ")"), r);
        assertTrue(r.toLowerCase().contains("deadlock"), "its deadlocks: " + r);
        assertTrue(r.contains("The whole dump: log:"), r);
        assertTrue(r.length() < 4_200, "a peek, not the dump: " + r.length());
        String h = r.replaceAll("(?s).*The whole dump: (log:\\d+).*", "$1");
        String more = call(LogTools.all().stream().filter(t -> t.name().equals("search")).findFirst().orElseThrow(),
                Map.of("log", h, "query", "BLOCKED"));
        assertTrue(more.contains("BLOCKED"), "the whole dump reads as a log: " + more);
    }

    @Test
    void aHeapHistogramOverJmxWithoutAFullGc() {
        saying(true);
        String r = call(JvmTools.HEAP_HISTOGRAM, Map.of("jvm", "jvm:2", "top", 5));
        assertTrue(r.contains("| class | objects | size |"), r);
        assertEquals(5, r.lines().filter(l -> l.startsWith("| ") && !l.startsWith("| class")).count(), r);
        assertTrue(r.contains("All classes: log:"), r);
        assertTrue(asked.get(0).startsWith("count the objects on the heap of logsift.jvm.FixtureApp on 127.0.0.1:"), asked.toString());
    }

    @Test
    void whatThePersonSetsIsWhatAgentsMayDo() throws Exception {
        saying(true);
        rules(JvmRules.DEFAULT.with(JvmRules.Action.THREAD_DUMP, JvmRules.Rule.NEVER));
        String never = call(JvmTools.THREAD_DUMP, Map.of("jvm", "jvm:1"));
        assertTrue(never.startsWith("FAILED The person doesn't let agents take thread dumps (Settings → Agents → "
                + "Running JVMs). Not done: take a thread dump of logsift.jvm.FixtureApp (pid " + app.pid()
                + ") on this computer."), never);
        assertTrue(asked.isEmpty(), "not asked: " + asked);
        rules(JvmRules.DEFAULT.with(JvmRules.Action.HISTOGRAM, JvmRules.Rule.ALLOW));
        String allowed = call(JvmTools.HEAP_HISTOGRAM, Map.of("jvm", "jvm:1", "top", 3));
        assertTrue(allowed.contains("| class | objects | size |"), allowed);
        assertTrue(asked.isEmpty(), "allowed without asking: " + asked);
        String listed = call(JvmTools.LIST_JVMS, Map.of());
        assertTrue(listed.contains("histograms allowed"), listed);
        // a heap dump can't be allowed without asking: read back as asked
        rules(JvmRules.DEFAULT.with(JvmRules.Action.HEAP_DUMP, JvmRules.Rule.ALLOW).withMaxHeapGb(0));
        JvmRules back = JvmRules.load(logsift.core.CacheSettings.home());
        assertEquals(JvmRules.Rule.ASK, back.heapDump());
        assertEquals(1, back.maxHeapGb(), "at least 1 GB");
    }

    @Test
    void aHeapDumpIsAskedEachTimeAndReadAfter() throws Exception {
        saying(true);
        String r = call(JvmTools.HEAP_DUMP, Map.of("jvm", "jvm:1"));
        assertTrue(r.startsWith("Heap dump of logsift.jvm.FixtureApp (pid " + app.pid() + "): "), r);
        Path file = Path.of(r.replaceAll("(?s)^Heap dump of [^:]+: (\\S+\\.hprof) .*", "$1"));
        try {
            assertTrue(java.nio.file.Files.size(file) > 1000, file.toString());
            assertEquals(logsift.core.CacheSettings.home().resolve("heap-dumps"), file.getParent());
            assertTrue(asked.get(0).matches("take a heap dump of logsift\\.jvm\\.FixtureApp \\(pid " + app.pid()
                    + "\\) on this computer \\(about [0-9.]+ MB\\)"), asked.toString());
            assertEquals(List.of("null"), keys, "no key: never allowed for the rest of a chat");
            assertTrue(r.contains("analyze_heap_dump path=" + file), r);
            // outside the agent's folders, but Logsift wrote it for the agent: read without asking
            String explained = call(HeapTools.all().stream().filter(t -> t.name().equals("analyze_heap_dump")).findFirst()
                    .orElseThrow(), Map.of("path", file.toString()));
            assertFalse(explained.startsWith("FAILED"), explained);
            call(JvmTools.HEAP_DUMP, Map.of("jvm", "jvm:1"));
            assertEquals(2, asked.size(), "asked again: " + asked);
        } finally {
            try (var l = java.nio.file.Files.list(file.getParent())) {
                for (Path f : l.toList()) {
                    java.nio.file.Files.deleteIfExists(f);
                }
            }
        }
        rules(JvmRules.DEFAULT.with(JvmRules.Action.HEAP_DUMP, JvmRules.Rule.NEVER));
        String never = call(JvmTools.HEAP_DUMP, Map.of("jvm", "jvm:1"));
        assertTrue(never.startsWith("FAILED The person doesn't let agents take heap dumps"), never);
        assertEquals(2, asked.size());
    }

    @Test
    void theAssistantIsToldWhereTheJvmsAre() {
        String text = Skill.locations(w.catalog(), null);
        assertTrue(text.contains("JVMs (thread_dump, heap_histogram, heap_dump): logsift.jvm.FixtureApp on this computer"), text);
    }
}
