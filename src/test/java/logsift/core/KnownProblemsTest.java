package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnownProblemsTest {

    private static MessageStats.Template t(String text, long count, long first) {
        return new MessageStats.Template(text, MessageStats.levelOf(text), count, first, first + count);
    }

    @Test
    void recognizesProblemsInMessagesAndExceptions() {
        List<MessageStats.Template> ts = List.of(
                t("ERROR [<*>] c.z.h.p.HikariPool - HikariPool-<*> - Connection is not available, request timed out after <*>.", 12, 100),
                t("[<*>] [CRITICAL] WORKER TIMEOUT (pid:<*>)", 3, 50),
                t("[<*>] [INFO] Booting worker with pid: <*>", 9, 0),
                t("INFO [main] c.a.App - Started App in <*> seconds", 2, 0));
        Exceptions.Thrown oom = new Exceptions.Thrown("java.lang.OutOfMemoryError", "Java heap space",
                List.of(new Exceptions.Frame("at x", "com.acme.Cache", "put", "Cache.java", 9, true)));
        Exceptions.Thrown wrapper = new Exceptions.Thrown("java.lang.IllegalStateException", "boom",
                List.of(new Exceptions.Frame("at y", "com.acme.Api", "get", "Api.java", 3, true)));
        Exceptions.Result ex = new Exceptions.Result(List.of(new Exceptions.Group("k",
                new Exceptions.Occurrence(400, List.of(wrapper, oom)), 2, 400, 900, new long[] {400, 900}, 2, List.of(), 1)),
                2, false);
        List<KnownProblems.Match> found = KnownProblems.find(ts, ex);
        List<String> ids = found.stream().map(m -> m.signature().id()).toList();
        assertEquals(List.of("db-pool", "gunicorn-timeout", "oom-heap"), ids);
        KnownProblems.Match pool = found.get(0);
        assertEquals(12, pool.count());
        assertEquals(100, pool.firstLine());
        assertTrue(pool.example().contains("Connection is not available"), pool.example());
        assertEquals(2, found.get(2).count());
        assertEquals("java.lang.OutOfMemoryError: Java heap space", found.get(2).example());
    }

    @Test
    void everySignatureExplainsItself() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (KnownProblems.Signature s : KnownProblems.ALL) {
            assertTrue(ids.add(s.id()), "unique: " + s.id());
            assertTrue(s.why().length() > 20 && s.tryThis().length() > 20, s.id());
            assertTrue(!s.pattern().matcher("2026-10-21 10:00:00 INFO all good, order 12 placed in 40 ms").find(), s.id());
        }
        assertEquals(List.of(), KnownProblems.find(null, null));
    }
}
