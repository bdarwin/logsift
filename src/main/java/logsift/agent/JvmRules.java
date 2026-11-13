package logsift.agent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * What agents may do to a running JVM (Settings → Agents → Running JVMs): a thread dump and a heap
 * histogram asked each time, allowed, or never; a heap dump asked each time or never (never
 * allowed without asking); and the biggest heap to count or dump. Read at each call, so a change
 * applies at once, to the window's assistant and to logsift-mcp alike.
 */
public record JvmRules(Rule threadDump, Rule histogram, Rule heapDump, int maxHeapGb) {
    public enum Rule { ASK, ALLOW, NEVER }

    /** What an agent wants to do. */
    public enum Action {
        THREAD_DUMP("take thread dumps"),
        HISTOGRAM("count the objects on a heap"),
        HEAP_DUMP("take heap dumps");

        private final String words;

        Action(String words) {
            this.words = words;
        }

        public String words() {
            return words;
        }
    }

    public static final JvmRules DEFAULT = new JvmRules(Rule.ASK, Rule.ASK, Rule.ASK, 8);

    public JvmRules {
        threadDump = threadDump == null ? Rule.ASK : threadDump;
        histogram = histogram == null ? Rule.ASK : histogram;
        heapDump = heapDump == null || heapDump == Rule.ALLOW ? Rule.ASK : heapDump; // always asked
        maxHeapGb = Math.max(1, Math.min(maxHeapGb, 1024));
    }

    public Rule rule(Action a) {
        return switch (a) {
            case THREAD_DUMP -> threadDump;
            case HISTOGRAM -> histogram;
            case HEAP_DUMP -> heapDump;
        };
    }

    public JvmRules with(Action a, Rule r) {
        return new JvmRules(a == Action.THREAD_DUMP ? r : threadDump, a == Action.HISTOGRAM ? r : histogram,
                a == Action.HEAP_DUMP ? r : heapDump, maxHeapGb);
    }

    public JvmRules withMaxHeapGb(int gb) {
        return new JvmRules(threadDump, histogram, heapDump, gb);
    }

    public long maxHeapBytes() {
        return (long) maxHeapGb << 30;
    }

    public static Path file(Path logsiftHome) {
        return logsiftHome.resolve("agent-jvm.properties");
    }

    /** The rules in Logsift's folder (the defaults if there are none, or they can't be read). */
    public static JvmRules load(Path logsiftHome) {
        Path f = file(logsiftHome);
        if (!Files.isRegularFile(f)) {
            return DEFAULT;
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (IOException e) {
            return DEFAULT;
        }
        int gb;
        try {
            gb = Integer.parseInt(p.getProperty("maxHeapGb", "8").strip());
        } catch (NumberFormatException e) {
            gb = 8;
        }
        return new JvmRules(rule(p, "threadDump"), rule(p, "histogram"), rule(p, "heapDump"), gb);
    }

    private static Rule rule(Properties p, String key) {
        try {
            return Rule.valueOf(p.getProperty(key, "ASK").strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Rule.ASK;
        }
    }

    public void save(Path logsiftHome) throws IOException {
        Properties p = new Properties();
        p.setProperty("threadDump", threadDump.name());
        p.setProperty("histogram", histogram.name());
        p.setProperty("heapDump", heapDump.name());
        p.setProperty("maxHeapGb", String.valueOf(maxHeapGb));
        Files.createDirectories(logsiftHome);
        try (Writer out = Files.newBufferedWriter(file(logsiftHome), StandardCharsets.UTF_8)) {
            p.store(out, "What coding agents may do to a running JVM (Settings → Agents → Running JVMs)");
        }
    }
}
