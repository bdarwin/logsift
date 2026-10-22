package logsift.jvm;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All threads of a JVM at one moment: when it was taken (epoch ms, or {@link #NO_TIME}), the JVM
 * as the dump names it ("OpenJDK 64-Bit Server VM (21.0.2+13 mixed mode)", or ""), and where it
 * starts in the file it was read from (0-based line, or -1).
 */
public final class ThreadDump {
    public static final long NO_TIME = Long.MIN_VALUE;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final long time;
    private final String vm;
    private final List<JThread> threads;
    private final long line;
    private Map<String, JThread> byKey;

    public ThreadDump(long time, String vm, List<JThread> threads, long line) {
        this.time = time;
        this.vm = vm == null ? "" : vm;
        this.threads = List.copyOf(threads);
        this.line = line;
    }

    public long time() {
        return time;
    }

    public String vm() {
        return vm;
    }

    public List<JThread> threads() {
        return threads;
    }

    public long line() {
        return line;
    }

    public Map<JThread.State, Integer> stateCounts() {
        Map<JThread.State, Integer> m = new EnumMap<>(JThread.State.class);
        for (JThread t : threads) {
            m.merge(t.state(), 1, Integer::sum);
        }
        return m;
    }

    /** The thread with this {@link JThread#key()}, or null. */
    public synchronized JThread find(String key) {
        if (byKey == null) {
            byKey = new HashMap<>();
            for (JThread t : threads) {
                byKey.putIfAbsent(t.key(), t);
            }
        }
        return byKey.get(key);
    }

    /** The thread holding this lock, or null. */
    public JThread holderOf(JThread.Lock lock) {
        if (lock == null) {
            return null;
        }
        for (JThread t : threads) {
            if (t.held().contains(lock)) {
                return t;
            }
        }
        return null;
    }

    /** "10:23:45", or "" without a time. */
    public String clock() {
        return time == NO_TIME ? "" : CLOCK.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }

    /** "2026-10-22 10:23:45", or "" without a time. */
    public String dateTime() {
        return time == NO_TIME ? "" : DAY.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
    }
}
