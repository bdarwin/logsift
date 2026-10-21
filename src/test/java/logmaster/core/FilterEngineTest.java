package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilterEngineTest {
    @TempDir
    Path dir;

    private static final List<FilterEngine.Spec> SPECS = List.of(
            new FilterEngine.Spec('A', new SearchQuery("error", false, false, false)),
            new FilterEngine.Spec('B', new SearchQuery("user=\\d+ ", false, true, false)),
            new FilterEngine.Spec('C', new SearchQuery("WARN", true, false, true)),
            new FilterEngine.Spec('D', new SearchQuery("äöü", false, false, false)),
            new FilterEngine.Spec('E', new SearchQuery("r", false, false, false)));

    @Test
    void parsesAndFormats() {
        assertEquals("(A OR B) AND NOT C", Formula.format(Formula.parse("(a or b) and not c")));
        assertEquals("A OR B AND NOT C", Formula.format(Formula.parse("A | B & !C")));
        assertEquals("NOT (A OR B)", Formula.format(Formula.parse("-(A||B)")));
        assertEquals("(A OR B) AND NOT (C OR D)",
                Formula.format(Formula.auto(List.of('A', 'B'), List.of('C', 'D'))));
        assertEquals("NOT C", Formula.format(Formula.auto(List.of(), List.of('C'))));
        assertEquals(null, Formula.auto(List.of(), List.of()));
        assertEquals(null, Formula.parse("  "));
        for (String bad : new String[] {"A AND", "(A", "A B", "A ANDNOT B", "A + B", "AND A"}) {
            assertThrows(IllegalArgumentException.class, () -> Formula.parse(bad), bad);
        }
    }

    @Test
    void matchesNaiveEvaluation() throws IOException {
        Random rnd = new Random(5);
        byte[] content = TestFiles.randomContent(rnd, 3000).getBytes(StandardCharsets.UTF_8);
        Path f = dir.resolve("f.log");
        Files.write(f, content);
        List<String> lines = TestFiles.naiveLines(content);
        try (LogDocument doc = LogDocument.open(f, 4096);
             FilterEngine engine = new FilterEngine(doc)) {
            assertTrue(doc.awaitIdle(10_000));
            for (int round = 0; round < 60; round++) {
                Formula formula = round == 0 ? null : randomFormula(rnd, 3);
                engine.configure(SPECS, formula);
                assertTrue(engine.awaitIdle(10_000));
                FilterResult r = engine.result();
                assertTrue(r.isComplete(), "complete");
                check(r, naive(lines, formula), lines.size(), String.valueOf(formula));
            }
        }
    }

    @Test
    void followsGrowingFile() throws IOException {
        Random rnd = new Random(9);
        byte[] all = TestFiles.randomContent(rnd, 3000).getBytes(StandardCharsets.UTF_8);
        Path f = dir.resolve("g.log");
        Files.write(f, new byte[0]);
        Formula formula = Formula.parse("(A OR C) AND NOT B");
        try (LogDocument doc = LogDocument.open(f, 600);
             FilterEngine engine = new FilterEngine(doc)) {
            engine.configure(SPECS, formula);
            int written = 0;
            while (written < all.length) {
                int n = Math.min(all.length - written, 1 + rnd.nextInt(1500));
                LogDocumentTest.append(f, all, written, n);
                written += n;
                doc.requestRefresh();
                assertTrue(engine.awaitIdle(5000));
                List<String> lines = TestFiles.naiveLines(Arrays.copyOf(all, written));
                check(engine.result(), naive(lines, formula), lines.size(), "after " + written);
            }
        }
    }

    @Test
    void rejectsUnknownLetter() throws IOException {
        Path f = dir.resolve("u.log");
        Files.writeString(f, "x\n");
        try (LogDocument doc = LogDocument.open(f);
             FilterEngine engine = new FilterEngine(doc)) {
            assertThrows(IllegalArgumentException.class,
                    () -> engine.configure(SPECS, Formula.parse("A AND Z")));
        }
    }

    /** Checks every way of reading the result against the expected passing lines. */
    private static void check(FilterResult r, List<Long> expected, int lineCount, String what) {
        assertEquals(lineCount, r.checkedLines(), what + " checked lines");
        assertEquals(expected.size(), r.count(), what + " count");
        for (int row = 0; row < expected.size(); row++) {
            assertEquals(expected.get(row), r.lineAt(row), what + " row " + row);
        }
        int k = 0;
        for (long line = 0; line < lineCount; line++) {
            while (k < expected.size() && expected.get(k) < line) {
                k++;
            }
            assertEquals(k, r.rowAtOrAfter(line), what + " rowAtOrAfter " + line);
            assertEquals(k < expected.size() && expected.get(k) == line, r.contains(line),
                    what + " contains " + line);
        }
    }

    private static List<Long> naive(List<String> lines, Formula f) {
        Map<Character, Pattern> patterns = new java.util.HashMap<>();
        SPECS.forEach(s -> patterns.put(s.id(), s.query().toPattern()));
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (f == null || eval(f, c -> patterns.get(c).matcher(line).find())) {
                out.add((long) i);
            }
        }
        return out;
    }

    private static boolean eval(Formula f, java.util.function.Predicate<Character> leaf) {
        return switch (f) {
            case Formula.Ref r -> leaf.test(r.id());
            case Formula.Not n -> !eval(n.a(), leaf);
            case Formula.And x -> eval(x.a(), leaf) && eval(x.b(), leaf);
            case Formula.Or x -> eval(x.a(), leaf) || eval(x.b(), leaf);
        };
    }

    private static Formula randomFormula(Random rnd, int depth) {
        if (depth == 0 || rnd.nextInt(4) == 0) {
            Formula ref = new Formula.Ref(SPECS.get(rnd.nextInt(SPECS.size())).id());
            return rnd.nextInt(3) == 0 ? new Formula.Not(ref) : ref;
        }
        Formula a = randomFormula(rnd, depth - 1);
        Formula b = randomFormula(rnd, depth - 1);
        Formula f = rnd.nextBoolean() ? new Formula.And(a, b) : new Formula.Or(a, b);
        return rnd.nextInt(4) == 0 ? new Formula.Not(f) : f;
    }
}
