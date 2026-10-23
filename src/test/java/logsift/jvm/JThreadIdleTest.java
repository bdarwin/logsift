package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JThreadIdleTest {

    private static JThread thread(JThread.State state, String... methods) {
        List<JThread.Frame> frames = java.util.Arrays.stream(methods)
                .map(m -> new JThread.Frame(m, "X.java:1", "", List.of())).toList();
        return new JThread("http-nio-8080-exec-1", 1, "", true, false, 5, state, "", frames, List.of(), -1, -1);
    }

    @Test
    void waitingForTheDatabaseIsWork() {
        assertFalse(thread(JThread.State.TIMED_WAITING, "java.util.concurrent.SynchronousQueue.poll",
                "com.zaxxer.hikari.util.ConcurrentBag.borrow", "com.zaxxer.hikari.pool.HikariPool.getConnection",
                "com.acme.shop.OrderRepository.save", "java.util.concurrent.ThreadPoolExecutor.runWorker").isIdle(),
                "waiting for a pool's connection");
        assertFalse(thread(JThread.State.RUNNABLE, "sun.nio.ch.Net.poll", "sun.nio.ch.NioSocketImpl.park",
                "sun.nio.ch.NioSocketImpl.read", "org.postgresql.core.PGStream.receiveChar",
                "org.postgresql.jdbc.PgStatement.executeQuery", "com.acme.shop.OrderRepository.find").isIdle(),
                "reading the database's answer");
        assertTrue(thread(JThread.State.WAITING, "jdk.internal.misc.Unsafe.park",
                "java.util.concurrent.LinkedBlockingQueue.take", "org.apache.tomcat.util.threads.TaskQueue.take",
                "java.util.concurrent.ThreadPoolExecutor.getTask").isIdle(), "waiting for a request");
        assertTrue(thread(JThread.State.RUNNABLE, "sun.nio.ch.Net.poll", "sun.nio.ch.NioSocketImpl.park",
                "org.apache.tomcat.util.net.NioEndpoint$Poller.run").isIdle(), "the poller");
    }
}
