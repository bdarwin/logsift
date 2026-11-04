package logsift.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void stop() {
        w.close();
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
        assertTrue(r.contains("each asks the person first"), r);
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
    void theAssistantIsToldWhereTheJvmsAre() {
        String text = Skill.locations(w.catalog(), null);
        assertTrue(text.contains("JVMs (thread_dump, heap_histogram): logsift.jvm.FixtureApp on this computer"), text);
    }
}
