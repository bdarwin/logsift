package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** Attaches to a real JVM started for the test (running {@link FixtureApp}). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LocalJvmsTest {
    private Process app;

    static Process startFixture() throws Exception {
        return FixtureApp.start();
    }

    @BeforeAll
    void start() throws Exception {
        app = startFixture();
    }

    @AfterAll
    void stop() throws Exception {
        app.getOutputStream().close();
        if (!app.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            app.destroyForcibly();
        }
    }

    private static void checkDump(ThreadDump d) {
        Set<Set<String>> deadlocks = ThreadAnalysis.deadlocks(d).stream()
                .map(c -> c.stream().map(JThread::name).collect(Collectors.toSet())).collect(Collectors.toSet());
        assertEquals(Set.of(Set.of("worker-A", "worker-B"), Set.of("lock-1", "lock-2")), deadlocks);
        assertEquals("db-writer", ThreadAnalysis.contention(d).get(0).holder().name());
        assertTrue(d.time() > 0);
    }

    @Test
    void listsAndAttaches() throws Exception {
        List<JvmProcess> jvms = LocalJvms.list();
        JvmProcess me = jvms.stream().filter(j -> j.pid() == app.pid()).findFirst()
                .orElseThrow(() -> new AssertionError("fixture not listed: " + jvms));
        assertTrue(me.name().endsWith("FixtureApp"), me.name());
        assertTrue(jvms.stream().noneMatch(j -> j.pid() == LocalJvms.self()), "not Logsift itself");
        try (LocalJvms.Session s = LocalJvms.attach(app.pid(), null)) {
            assertEquals("attach", s.how());
            ThreadDump d1 = s.dump();
            checkDump(d1);
            Thread.sleep(600);
            ThreadDump d2 = s.dump();
            List<ThreadAnalysis.Hot> hot = ThreadAnalysis.hot(List.of(d1, d2));
            assertEquals("hot-loop", hot.get(0).thread().name(), "CPU use between two dumps");
        }
    }

    @Test
    void usesJcmdWhenAsked() throws Exception {
        Path jcmd = LocalJvms.findJcmd(System.getProperty("java.home"));
        assertNotNull(jcmd, "the test JDK has jcmd");
        try (LocalJvms.Session s = LocalJvms.viaJcmd(app.pid(), jcmd)) {
            assertEquals("jcmd", s.how());
            checkDump(s.dump());
        }
    }

    @Test
    void describesCommandLines() {
        assertEquals("app.jar", JvmProcess.describe("/usr/bin/java -Xmx1g -jar /opt/app/app.jar --port 80"));
        assertEquals("com.acme.Main", JvmProcess.describe("java -cp a.jar:b.jar -Dx=1 com.acme.Main run"));
        assertEquals("com.acme.Main", JvmProcess.describe("java --add-opens java.base/java.lang=ALL-UNNAMED"
                + " -m acme.app/com.acme.Main"));
        assertEquals("org.apache.catalina.startup.Bootstrap", JvmProcess.describe(
                "/usr/lib/jvm/java-17/bin/java -Djava.util.logging.config.file=/x/logging.properties"
                        + " -classpath \"/opt/tomcat/bin/bootstrap.jar:/opt/tomcat/bin/tomcat-juli.jar\""
                        + " org.apache.catalina.startup.Bootstrap start"));
        assertTrue(JvmProcess.isJava("/usr/lib/jvm/java-17/bin/java -jar x.jar"));
        assertTrue(JvmProcess.isJava("C:\\\\Program Files\\\\Java\\\\bin\\\\javaw.exe -jar x.jar"));
        assertTrue(!JvmProcess.isJava("/usr/bin/python3 javaapp.py"));
        assertTrue(!JvmProcess.isJava("/opt/x/bin/app --conf /etc/java x"));
        assertEquals("Logsift", JvmProcess.describe("/Applications/Logsift.app/Contents/MacOS/Logsift"));
    }
}
