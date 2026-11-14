package logsift.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A Logsift launched with one file, as Windows launches one per file when several are opened from
 * Explorer, for tests: {@code OpensTogether HOME FILE OUT N}. It hands its file to the running one
 * and prints "forwarded", or becomes it: then it waits until it has N files (its own and the
 * others'), writes them to OUT/listener-PID.txt and ends.
 */
final class OpensTogether {
    private OpensTogether() {
    }

    public static void main(String[] args) throws Exception {
        Path home = Path.of(args[0]);
        Path file = Path.of(args[1]);
        Path out = Path.of(args[2]);
        int n = Integer.parseInt(args[3]);
        List<Path> got = new CopyOnWriteArrayList<>();
        SingleInstance si = SingleInstance.openOrListen(home, List.of(file), got::addAll, null,
                java.time.Duration.ofSeconds(30));
        if (si == null) {
            System.out.println("forwarded");
            return;
        }
        got.add(file.toAbsolutePath());
        long until = System.nanoTime() + 30_000_000_000L;
        while (got.size() < n && System.nanoTime() < until) {
            Thread.sleep(20);
        }
        List<String> lines = new ArrayList<>();
        got.forEach(p -> lines.add(p.toString()));
        Files.write(out.resolve("listener-" + ProcessHandle.current().pid() + ".txt"), lines);
        si.close();
        System.out.println("listened");
    }
}
