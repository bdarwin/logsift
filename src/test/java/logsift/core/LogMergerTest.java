package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import logsift.tools.LogGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogMergerTest {
    @TempDir
    Path dir;

    @Test
    void mergesInTimeOrderAndKeepsEverything() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Path d = dir.resolve("node" + (i + 1));
            Files.createDirectories(d);
            Path f = d.resolve("app.log");
            LogGenerator.generate(f, (1 + i) << 20, 10 + i);
            files.add(f);
        }
        List<String> labels = LogMerger.labels(files);
        assertEquals(List.of("node1-app", "node2-app", "node3-app"), labels);
        List<LogMerger.Source> sources = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            sources.add(new LogMerger.Source(files.get(i), labels.get(i)));
        }
        Path out = dir.resolve("merged.log");
        try (LogMerger m = LogMerger.merge(sources, out, null, () -> false)) {
            List<String> merged = Files.readAllLines(out);
            long total = 0;
            for (Path f : files) {
                total += Files.readAllLines(f).size();
            }
            assertEquals(total, merged.size(), "every line once");
            assertEquals(total, m.linesWritten());

            // each source's lines, label removed, are the original file in order
            Map<String, List<String>> bySource = new HashMap<>();
            TimeFormat tf = TimeFormat.detect(Files.readAllLines(files.get(0)).subList(0, 200), 2026);
            long lastTime = Long.MIN_VALUE;
            String lastLabel = null;
            for (String line : merged) {
                assertTrue(line.startsWith("<"), line);
                int gt = line.indexOf("> ");
                String label = line.substring(1, gt);
                String text = line.substring(gt + 2);
                bySource.computeIfAbsent(label, k -> new ArrayList<>()).add(text);
                long t = tf.parse(line); // parse skips the label
                if (t != TimeFormat.NONE) {
                    assertTrue(t >= lastTime, "time order at: " + line);
                    lastTime = t;
                    lastLabel = label;
                } else {
                    assertEquals(lastLabel, label, "a stack trace line stays with its entry: " + line);
                }
            }
            for (int i = 0; i < files.size(); i++) {
                assertEquals(Files.readAllLines(files.get(i)), bySource.get(labels.get(i)),
                        "lines of " + labels.get(i));
            }

            // following: new lines in a source are appended
            m.follow(30);
            String extra = "2099-01-01 00:00:00.000 ERROR [main] c.a.X - late line\n";
            Files.writeString(files.get(1), extra, StandardOpenOption.APPEND);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && !Files.readString(out, StandardCharsets.UTF_8).endsWith("<node2-app> " + extra)) {
                Thread.sleep(20);
            }
            assertTrue(Files.readString(out, StandardCharsets.UTF_8).endsWith("<node2-app> " + extra),
                    "appended while following");
        }
    }

    @Test
    void entryAndTimeDetectionSkipTheLabel() {
        EntryFormat ef = EntryFormat.detect(List.of("<n1> 2026-10-21 10:00:00 INFO a",
                "<n1> \tat x", "<n2> 2026-10-21 10:00:01 INFO b", "<n2> 2026-10-21 10:00:02 INFO c"));
        assertEquals("date and time (2026-10-21 10:02:13)", ef.name());
        assertTrue(!ef.startsEntry("<n1> \tat x"));
        TimeFormat tf = TimeFormat.detect(List.of("<n1> 2026-10-21 10:00:00.5 INFO a",
                "<n2> 2026-10-21 10:00:01 INFO b", "<n2> 2026-10-21 10:00:02 INFO c"), 2026);
        assertEquals(tf.parse("2026-10-21 10:00:00.500 x"), tf.parse("<n1> 2026-10-21 10:00:00.5 INFO a"));
    }
}
