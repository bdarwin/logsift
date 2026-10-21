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

    /** A log with entries: a dated line, then sometimes stack traces and blank lines. */
    private static String entryLog(Random rnd, int entries) {
        String[] words = {"error", "warn", "user=42 ", "timeout", "äöü", "retry", "ok"};
        StringBuilder sb = new StringBuilder();
        sb.append("\tat orphan continuation before the first entry\n");
        for (int i = 0; i < entries; i++) {
            sb.append(String.format("2026-10-21 10:%02d:%02d ", i / 60 % 60, i % 60));
            sb.append(rnd.nextBoolean() ? "ERROR " : "INFO ");
            sb.append(words[rnd.nextInt(words.length)]).append(' ').append(words[rnd.nextInt(words.length)]);
            sb.append('\n');
            int cont = rnd.nextInt(5) == 0 ? 1 + rnd.nextInt(6) : 0;
            for (int k = 0; k < cont; k++) {
                sb.append(rnd.nextInt(7) == 0 ? "" : "\tat com.acme." + words[rnd.nextInt(words.length)]);
                sb.append(rnd.nextInt(10) == 0 ? "\r\n" : "\n");
            }
        }
        return sb.toString();
    }

    @Test
    void wholeEntriesAndContextMatchNaive() throws IOException {
        Random rnd = new Random(21);
        String text = entryLog(rnd, 1500);
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        Path f = dir.resolve("entries.log");
        Files.write(f, content);
        List<String> lines = TestFiles.naiveLines(content);
        EntryFormat format = EntryFormat.detect(lines.subList(0, 200));
        assertEquals("date and time (2026-10-21 10:02:13)", format.name());
        try (LogDocument doc = LogDocument.open(f, 4096);
             FilterEngine engine = new FilterEngine(doc)) {
            assertTrue(doc.awaitIdle(10_000));
            engine.setEntryFormat(format);
            for (int round = 0; round < 40; round++) {
                Formula formula = randomFormula(rnd, 3);
                int ctx = round % 4 == 0 ? 0 : rnd.nextInt(4);
                engine.configure(SPECS, formula);
                engine.setContext(ctx);
                assertTrue(engine.awaitIdle(10_000));
                FilterResult r = engine.result();
                List<Long> matched = naiveEntries(lines, format, formula);
                List<Long> shown = naiveContext(matched, ctx, lines.size());
                String what = formula + " ctx=" + ctx;
                check(r, shown, lines.size(), what);
                for (long line = 0; line < lines.size(); line++) {
                    assertEquals(matched.contains(line), r.isMatch(line), what + " isMatch " + line);
                }
            }
        }
    }

    @Test
    void wholeEntriesFollowGrowingFile() throws IOException {
        Random rnd = new Random(23);
        byte[] all = entryLog(rnd, 800).getBytes(StandardCharsets.UTF_8);
        Path f = dir.resolve("grow-entries.log");
        Files.write(f, new byte[0]);
        EntryFormat format = EntryFormat.detect(TestFiles.naiveLines(all).subList(0, 100));
        Formula formula = Formula.parse("A AND NOT C");
        try (LogDocument doc = LogDocument.open(f, 500);
             FilterEngine engine = new FilterEngine(doc)) {
            engine.configure(SPECS, formula);
            engine.setEntryFormat(format);
            engine.setContext(1);
            int written = 0;
            while (written < all.length) {
                int n = Math.min(all.length - written, 1 + rnd.nextInt(2000));
                LogDocumentTest.append(f, all, written, n);
                written += n;
                doc.requestRefresh();
                assertTrue(engine.awaitIdle(5000));
                List<String> lines = TestFiles.naiveLines(Arrays.copyOf(all, written));
                // the last entry may still grow, so compare only complete entries' lines
                FilterResult r = engine.result();
                List<Long> shown = naiveContext(naiveEntries(lines, format, formula), 1, lines.size());
                check(r, shown, lines.size(), "after " + written);
            }
        }
    }

    /** Lines of entries where the formula holds (a filter matches an entry if any line does). */
    private static List<Long> naiveEntries(List<String> lines, EntryFormat format, Formula f) {
        Map<Character, Pattern> patterns = new java.util.HashMap<>();
        SPECS.forEach(s -> patterns.put(s.id(), s.query().toPattern()));
        List<Long> out = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            int j = i + 1;
            while (j < lines.size() && !format.startsEntry(lines.get(j))) {
                j++;
            }
            int from = i;
            int to = j;
            boolean pass = eval(f, c -> {
                for (int k = from; k < to; k++) {
                    if (patterns.get(c).matcher(lines.get(k)).find()) {
                        return true;
                    }
                }
                return false;
            });
            if (pass) {
                for (int k = from; k < to; k++) {
                    out.add((long) k);
                }
            }
            i = j;
        }
        return out;
    }

    private static List<Long> naiveContext(List<Long> lines, int n, int lineCount) {
        java.util.TreeSet<Long> out = new java.util.TreeSet<>();
        for (long l : lines) {
            for (long k = Math.max(0, l - n); k <= Math.min(lineCount - 1, l + n); k++) {
                out.add(k);
            }
        }
        return new ArrayList<>(out);
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
