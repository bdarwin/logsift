package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Which failures are bugs, and that bugs in background work reach the reporter. */
class ProblemsTest {
    private final List<Throwable> reported = new ArrayList<>();

    @AfterEach
    void noSink() {
        Problems.setSink(null);
    }

    @Test
    void expectedFailuresAreNotBugs() {
        assertTrue(Problems.isExpected(new NoSuchFileException("/var/log/gone.log")));
        assertTrue(Problems.isExpected(new FileNotFoundException("x")));
        assertTrue(Problems.isExpected(new java.net.ConnectException("Connection refused")));
        assertTrue(Problems.isExpected(new UncheckedIOException(new IOException("disk"))));
        assertTrue(Problems.isExpected(new CancellationException()));
        assertTrue(Problems.isExpected(new InterruptedException()));
        assertTrue(Problems.isExpected(new FileChangedException("replaced")));
        assertTrue(Problems.isExpected(new java.util.regex.PatternSyntaxException("x", "(", 0)));
        assertTrue(Problems.isExpected(new ExecutionException(new IOException("wrapped"))));
        assertTrue(!Problems.isExpected(new NullPointerException()));
        assertTrue(!Problems.isExpected(new IllegalStateException("bug")));
        assertTrue(!Problems.isExpected(new ArrayIndexOutOfBoundsException()));
        assertTrue(!Problems.isExpected(new OutOfMemoryError()));
        assertTrue(!Problems.isExpected(new ExecutionException(new NullPointerException())));
    }

    @Test
    void onlyBugsAreReported() {
        Problems.setSink(reported::add);
        Problems.unexpected(new NoSuchFileException("a.log"));
        Problems.unexpected(new CancellationException());
        assertTrue(reported.isEmpty());
        NullPointerException npe = new NullPointerException("bug");
        Problems.unexpected(new ExecutionException(npe));
        assertEquals(1, reported.size());
        assertSame(npe, reported.get(0), "the bug itself, not its wrapper");
        Problems.setSink(null);
        Problems.unexpected(new IllegalStateException("nowhere to go")); // no app: nothing happens
        assertEquals(1, reported.size());
    }

    @Test
    void aGuardedTasksBugReachesTheReporter() throws Exception {
        Problems.setSink(reported::add);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // a task whose Future nobody reads
            pool.submit(Problems.guard((Runnable) () -> {
                throw new IllegalStateException("lost before");
            })).get();
            assertEquals(1, reported.size());
            assertEquals("lost before", reported.get(0).getMessage());
            // a Callable: reported, and its Future still fails
            Future<String> f = pool.submit(Problems.guard(() -> {
                throw new ArrayIndexOutOfBoundsException(3);
            }));
            assertThrows(ExecutionException.class, f::get);
            assertEquals(2, reported.size());
            // expected failures are left to the caller
            Future<String> io = pool.submit(Problems.guard(() -> {
                throw new IOException("no such file");
            }));
            assertThrows(ExecutionException.class, io::get);
            assertEquals(2, reported.size());
        } finally {
            pool.shutdownNow();
        }
    }
}
