package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Explorer's Compare on two files: two launches a moment apart, one comparison. */
class GatherTest {
    private static void waitFor(List<?> l, int n) throws InterruptedException {
        for (int i = 0; i < 200 && l.size() < n; i++) {
            Thread.sleep(10);
        }
    }

    @Test
    void filesArrivingCloseTogetherGoTogether() throws Exception {
        List<List<Path>> groups = new CopyOnWriteArrayList<>();
        Gather g = new Gather(Duration.ofMillis(300), Duration.ofSeconds(5), groups::add);
        Path a = Path.of("a.log").toAbsolutePath();
        Path b = Path.of("b.log").toAbsolutePath();
        g.add(List.of(a));
        Thread.sleep(150);
        g.add(List.of(b));
        g.add(List.of(b)); // the same file twice is one
        waitFor(groups, 1);
        Thread.sleep(400);
        assertEquals(List.of(List.of(a, b)), groups);

        // later: a new pair
        Path c = Path.of("c.log").toAbsolutePath();
        g.add(List.of(c));
        waitFor(groups, 2);
        assertEquals(List.of(c), groups.get(1));
    }

    @Test
    void aSteadyTrickleStillEnds() throws Exception {
        List<List<Path>> groups = new CopyOnWriteArrayList<>();
        Gather g = new Gather(Duration.ofMillis(200), Duration.ofMillis(500), groups::add);
        long t0 = System.nanoTime();
        for (int i = 0; i < 8; i++) {
            g.add(List.of(Path.of("f" + i + ".log")));
            Thread.sleep(100);
        }
        waitFor(groups, 2);
        assertEquals(2, groups.size(), "handed on after the longest wait, then the rest: " + groups);
        assertEquals(8, groups.get(0).size() + groups.get(1).size());
        assertEquals(true, (System.nanoTime() - t0) / 1_000_000 < 3000);
    }
}
