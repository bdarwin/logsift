package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LineExporterTest {
    @TempDir
    Path dir;

    @Test
    void exportsExactlyTheShownLinesWithOriginalBytes() throws Exception {
        Random rnd = new Random(41);
        String text = TestFiles.randomContent(rnd, 3000);
        Path f = dir.resolve("e.log");
        Files.writeString(f, text);
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        List<String> rawLines = rawLines(content);
        try (LogDocument doc = LogDocument.open(f, 4096);
             FilterEngine engine = new FilterEngine(doc)) {
            assertTrue(doc.awaitIdle(10_000));
            List<FilterEngine.Spec> specs = List.of(
                    new FilterEngine.Spec('A', new SearchQuery("error", false, false, false)),
                    new FilterEngine.Spec('B', new SearchQuery("r", false, false, false)));
            for (String formula : new String[] {"A", "NOT A", "A OR B", "NOT B"}) {
                engine.configure(specs, Formula.parse(formula));
                engine.setContext(rnd.nextInt(3));
                assertTrue(engine.awaitIdle(10_000));
                FilterResult r = engine.result();
                Path out = dir.resolve("out.log");
                long n = LineExporter.export(doc, c -> r.forEachRun(doc.lineCount(), c), out, null, () -> false);
                assertEquals(r.count(), n, formula);
                StringBuilder expected = new StringBuilder();
                for (long row = 0; row < r.count(); row++) {
                    String raw = rawLines.get((int) r.lineAt(row));
                    expected.append(raw.endsWith("\n") ? raw : raw + "\n");
                }
                assertEquals(expected.toString(), Files.readString(out), formula);
            }
        }
    }

    /** Lines with their original line endings. */
    private static List<String> rawLines(byte[] content) {
        String s = new String(content, StandardCharsets.UTF_8);
        List<String> out = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                out.add(s.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < s.length()) {
            out.add(s.substring(start));
        }
        return out;
    }
}
