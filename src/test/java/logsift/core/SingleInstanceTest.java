package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One Logsift per user: files handed to it, and git's commands answered when they are done. */
class SingleInstanceTest {
    @TempDir
    Path home;

    @Test
    void filesAndCommands() throws Exception {
        List<List<Path>> opened = new CopyOnWriteArrayList<>();
        List<List<String>> asked = new CopyOnWriteArrayList<>();
        SingleInstance si = SingleInstance.listen(home, opened::add, (cmd, reply) -> {
            asked.add(cmd);
            // answered later, as a merge is when it is saved
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    return;
                }
                reply.accept(cmd.get(0).equals("merge") ? 1 : 0);
            });
            t.start();
        });
        try {
            assertTrue(SingleInstance.forward(home, List.of(Path.of("/tmp/a.log"))));
            long t0 = System.nanoTime();
            assertEquals(0, SingleInstance.command(home, List.of("diff", "/x/a.txt", "/x/b.txt")));
            assertTrue((System.nanoTime() - t0) / 1_000_000 >= 250, "it waited for the answer");
            assertEquals(1, SingleInstance.command(home, List.of("merge", "/l", "/r", "/b", "/m")));
            assertEquals(List.of(List.of("diff", "/x/a.txt", "/x/b.txt"), List.of("merge", "/l", "/r", "/b", "/m")),
                    asked);
            // files still go to the instance after commands
            assertTrue(SingleInstance.forward(home, List.of(Path.of("/tmp/b.log"))));
            for (int i = 0; i < 50 && opened.size() < 2; i++) {
                Thread.sleep(20);
            }
            assertEquals(2, opened.size());
        } finally {
            si.close();
        }
    }

    @Test
    void noInstanceRunning(@TempDir Path other) throws Exception {
        assertEquals(-1, SingleInstance.command(other, List.of("diff", "a", "b")));
        Files.writeString(other.resolve("instance"), "1\nsecret\n"); // a stale file: nobody listens on port 1
        assertEquals(-1, SingleInstance.command(other, List.of("diff", "a", "b")));
    }
}
