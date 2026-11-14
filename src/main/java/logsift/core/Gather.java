package logsift.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Files that arrive one by one but belong together: Explorer starts a Logsift for each selected
 * file, so "Compare" on two files arrives as two launches, a moment apart. The files are handed
 * on together once none has arrived for {@code quiet}, or {@code longest} after the first.
 */
public final class Gather {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "logsift-gather");
        t.setDaemon(true);
        return t;
    });

    private final long quietMs;
    private final long longestMs;
    private final Consumer<List<Path>> done;
    private final List<Path> got = new ArrayList<>();
    private long first;
    private ScheduledFuture<?> due;

    public Gather(java.time.Duration quiet, java.time.Duration longest, Consumer<List<Path>> done) {
        this.quietMs = quiet.toMillis();
        this.longestMs = longest.toMillis();
        this.done = done;
    }

    /** More files: waits a little longer for the rest. */
    public synchronized void add(List<Path> paths) {
        long now = System.currentTimeMillis();
        if (got.isEmpty()) {
            first = now;
        }
        for (Path p : paths) {
            Path a = p.toAbsolutePath().normalize();
            if (!got.contains(a)) {
                got.add(a);
            }
        }
        if (due != null) {
            due.cancel(false);
        }
        long wait = Math.max(0, Math.min(quietMs, first + longestMs - now));
        due = TIMER.schedule(this::finish, wait, TimeUnit.MILLISECONDS);
    }

    private void finish() {
        List<Path> all;
        synchronized (this) {
            if (got.isEmpty()) {
                return;
            }
            all = List.copyOf(got);
            got.clear();
            due = null;
        }
        done.accept(all);
    }
}
