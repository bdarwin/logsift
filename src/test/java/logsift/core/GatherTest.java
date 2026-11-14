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
        // files keep coming (gaps far shorter than the quiet time): the first ones go after the longest wait
        List<List<Path>> groups = new CopyOnWriteArrayList<>();
        Gather g = new Gather(Duration.ofMillis(1500), Duration.ofMillis(300), groups::add);
        int handedBeforeTheEnd = -1;
        for (int i = 0; i < 20; i++) {
            g.add(List.of(Path.of("f" + i + ".log")));
            Thread.sleep(50);
            if (i == 19) {
                handedBeforeTheEnd = groups.size();
            }
        }
        assertEquals(true, handedBeforeTheEnd >= 1, "some were handed on while more were coming");
        for (int i = 0; i < 500 && groups.stream().mapToInt(List::size).sum() < 20; i++) {
            Thread.sleep(10);
        }
        assertEquals(20, groups.stream().mapToInt(List::size).sum(), "and all of them in the end: " + groups);
    }
}
