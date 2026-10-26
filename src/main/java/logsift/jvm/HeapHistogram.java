package logsift.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What fills the Java heap, by class: the JVM's class histogram ({@code jcmd <pid>
 * GC.class_histogram}). Two histograms some time apart show which classes grow (a leak).
 *
 * @param time when it was taken (epoch ms)
 */
public record HeapHistogram(long time, List<Row> rows, long instances, long bytes) {

    /** One class: how many objects of it, and their size together. */
    public record Row(String className, String module, long instances, long bytes) {
        /** {@code [B} → {@code byte[]}, {@code [Ljava.lang.String;} → {@code java.lang.String[]}. */
        public String displayName() {
            return readable(className);
        }
    }

    /** A class in two histograms (zero where it wasn't there). */
    public record Change(String className, long instancesBefore, long bytesBefore, long instancesAfter,
                         long bytesAfter) {
        public long bytesGrown() {
            return bytesAfter - bytesBefore;
        }

        public long instancesGrown() {
            return instancesAfter - instancesBefore;
        }
    }

    private static final Pattern ROW = Pattern.compile(
            "^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)(?:\\s+\\((.*)\\))?\\s*$");
    private static final Pattern TOTAL = Pattern.compile("^\\s*Total\\s+(\\d+)\\s+(\\d+)\\s*$");

    public HeapHistogram {
        rows = List.copyOf(rows);
    }

    /** Reads {@code GC.class_histogram} text (any JDK since 8). */
    public static HeapHistogram parse(String text, long time) {
        List<Row> rows = new ArrayList<>();
        long inst = 0;
        long bytes = 0;
        boolean total = false;
        for (String line : text.split("\n")) {
            Matcher m = ROW.matcher(line);
            if (m.matches()) {
                rows.add(new Row(m.group(3), m.group(4) == null ? "" : m.group(4),
                        Long.parseLong(m.group(1)), Long.parseLong(m.group(2))));
                continue;
            }
            Matcher t = TOTAL.matcher(line);
            if (t.matches()) {
                inst = Long.parseLong(t.group(1));
                bytes = Long.parseLong(t.group(2));
                total = true;
            }
        }
        if (!total) {
            for (Row r : rows) {
                inst += r.instances();
                bytes += r.bytes();
            }
        }
        return new HeapHistogram(time, rows, inst, bytes);
    }

    /** Classes by how much they grew from {@code before} to {@code after} (biggest growth first). */
    public static List<Change> compare(HeapHistogram before, HeapHistogram after) {
        Map<String, long[]> m = new HashMap<>();
        for (Row r : before.rows()) {
            long[] v = m.computeIfAbsent(r.className(), k -> new long[4]);
            v[0] += r.instances();
            v[1] += r.bytes();
        }
        for (Row r : after.rows()) {
            long[] v = m.computeIfAbsent(r.className(), k -> new long[4]);
            v[2] += r.instances();
            v[3] += r.bytes();
        }
        List<Change> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new Change(k, v[0], v[1], v[2], v[3])));
        out.sort((a, b) -> Long.compare(b.bytesGrown(), a.bytesGrown()));
        return out;
    }

    /** A JVM class name as written in Java. */
    public static String readable(String name) {
        int dims = 0;
        while (dims < name.length() && name.charAt(dims) == '[') {
            dims++;
        }
        if (dims == 0) {
            return name;
        }
        String elem = name.substring(dims);
        String base = switch (elem) {
            case "B" -> "byte";
            case "C" -> "char";
            case "I" -> "int";
            case "J" -> "long";
            case "S" -> "short";
            case "Z" -> "boolean";
            case "F" -> "float";
            case "D" -> "double";
            default -> elem.startsWith("L") && elem.endsWith(";") ? elem.substring(1, elem.length() - 1) : elem;
        };
        return base + "[]".repeat(dims);
    }

    /**
     * The heap in use, in bytes, from {@code GC.heap_info} (every collector words it its own way:
     * "garbage-first heap total 262144K, used 123456K", "PSYoungGen … used 1310K" and "ParOldGen …
     * used 0K", "ZHeap used 34M", Shenandoah's "34M used"); metaspace left out. -1 if not found.
     */
    public static long heapUsed(String heapInfo) {
        if (heapInfo == null) {
            return -1;
        }
        java.util.regex.Pattern after = java.util.regex.Pattern.compile("used (\\d+)([KMG])\\b");
        java.util.regex.Pattern before = java.util.regex.Pattern.compile("(\\d+)([KMG]) used\\b");
        long sum = 0;
        boolean any = false;
        for (String line : heapInfo.split("\n")) {
            String l = line.strip();
            if (l.startsWith("Metaspace") || l.startsWith("class space") || l.startsWith("Shenandoah Metaspace")) {
                continue;
            }
            for (java.util.regex.Pattern p : new java.util.regex.Pattern[] {after, before}) {
                java.util.regex.Matcher m = p.matcher(l);
                while (m.find()) {
                    long n = Long.parseLong(m.group(1));
                    sum += n << (m.group(2).equals("K") ? 10 : m.group(2).equals("M") ? 20 : 30);
                    any = true;
                }
            }
        }
        return any ? sum : -1;
    }
}
