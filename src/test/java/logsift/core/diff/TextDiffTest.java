package logsift.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Texts compared line by line: blocks, rows, paired lines, changed words, and the options. */
class TextDiffTest {

    private static TextDiff.Result diff(List<String> a, List<String> b) {
        return TextDiff.compare(a, b, TextDiff.Options.DEFAULT);
    }

    @Test
    void sameTexts() {
        TextDiff.Result r = diff(List.of("a", "b"), List.of("a", "b"));
        assertTrue(r.same());
        assertEquals(1, r.blocks().size());
        assertEquals(2, r.rows().size());
    }

    @Test
    void insertDeleteAndChange() {
        List<String> a = List.of("one", "two", "three", "four", "five");
        List<String> b = List.of("one", "2", "three", "four", "four and a half", "five");
        TextDiff.Result r = diff(a, b);
        assertEquals(List.of(Edit.Kind.EQUAL, Edit.Kind.REPLACE, Edit.Kind.EQUAL, Edit.Kind.INSERT, Edit.Kind.EQUAL),
                r.blocks().stream().map(TextDiff.Block::kind).toList());
        assertEquals(1, r.linesAdded());
        assertEquals(0, r.linesRemoved());
        assertEquals(1, r.linesChanged());
        // the inserted line faces an empty row on the left
        TextDiff.Row ins = r.rows().stream().filter(x -> x.left() < 0).findFirst().orElseThrow();
        assertEquals(4, ins.right());
        assertEquals(6, r.rows().size());
        TextDiff.Result d = diff(b, a);
        assertEquals(1, d.linesRemoved());
    }

    @Test
    void changedLinesArePairedWithTheMostAlike() {
        List<String> a = List.of("start", "user alice logged in", "user bob logged in", "end");
        List<String> b = List.of("start", "a totally new line", "user alice logged out", "user bob logged out", "end");
        TextDiff.Result r = diff(a, b);
        // alice faces alice, bob faces bob; the new line faces nothing
        List<TextDiff.Row> changed = r.rows().stream().filter(x -> r.blocks().get(x.block()).isChange()).toList();
        assertEquals(3, changed.size());
        assertEquals(-1, changed.get(0).left());
        assertEquals(1, changed.get(0).right());
        assertEquals(1, changed.get(1).left());
        assertEquals(2, changed.get(1).right());
        // the word that changed: in, then out
        List<List<TextDiff.Span>> w = r.inline(changed.get(1));
        assertEquals(List.of(new TextDiff.Span(18, 20)), w.get(0));
        assertEquals(List.of(new TextDiff.Span(18, 21)), w.get(1));
    }

    @Test
    void wordsMarkOnlyWhatDiffers() {
        List<List<TextDiff.Span>> w = TextDiff.words("price = 10.50 EUR", "price = 12.00 EUR", TextDiff.Options.DEFAULT);
        assertEquals("10.50", "price = 10.50 EUR".substring(w.get(0).get(0).start(), w.get(0).get(w.get(0).size() - 1).end()));
        assertEquals("12.00", "price = 12.00 EUR".substring(w.get(1).get(0).start(), w.get(1).get(w.get(1).size() - 1).end()));
    }

    @Test
    void optionsSpacesCaseAndMasks() {
        List<String> a = List.of("2026-10-28 10:02:13,123 INFO  job 7f3a9c21-1b2c-4d5e-8f90-123456789abc took 812 ms");
        List<String> b = List.of("2026-10-29 11:45:01,999 INFO job 0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0 took 790 ms");
        assertFalse(diff(a, b).same());
        TextDiff.Options masks = new TextDiff.Options(TextDiff.Spaces.ALL, false, true, true, true);
        assertTrue(TextDiff.compare(a, b, masks).same(), TextDiff.normalize(a.get(0), masks));
        assertTrue(TextDiff.compare(List.of("  Hello World"), List.of("hello world  "),
                new TextDiff.Options(TextDiff.Spaces.ENDS, true, false, false, false)).same());
        assertFalse(TextDiff.compare(List.of("Hello World"), List.of("hello world"),
                new TextDiff.Options(TextDiff.Spaces.ENDS, false, false, false, false)).same());
        assertTrue(TextDiff.compare(List.of("a  b"), List.of("ab"),
                new TextDiff.Options(TextDiff.Spaces.ALL, false, false, false, false)).same());
    }

    @Test
    void maskedPartsAreNotMarkedAsChangedWords() {
        TextDiff.Options times = new TextDiff.Options(TextDiff.Spaces.EXACT, false, true, false, false);
        String a = "2026-10-28 10:00:07,123 INFO Connecting to db-1";
        String b = "2026-10-29 11:00:07,456 INFO Connecting to db-2";
        List<List<TextDiff.Span>> w = TextDiff.words(a, b, times);
        assertEquals(1, w.get(0).size(), w.toString());
        assertEquals("1", a.substring(w.get(0).get(0).start(), w.get(0).get(0).end()));
    }

    @Test
    void bigFilesStayFast() {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            a.add("line " + i + " of the log");
            if (i % 1000 == 0) {
                b.add("inserted " + i);
            }
            b.add(i % 777 == 0 ? "changed " + i : "line " + i + " of the log");
        }
        long t0 = System.nanoTime();
        TextDiff.Result r = diff(a, b);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(200, r.linesAdded());
        assertEquals((200_000 + 776) / 777, r.linesChanged());
        assertTrue(ms < 5000, ms + " ms");
    }

    @Test
    void similarity() {
        assertEquals(1.0, TextDiff.similarity("abc", "abc"));
        assertTrue(TextDiff.similarity("user alice logged in", "user alice logged out") > 0.7);
        assertTrue(TextDiff.similarity("user alice logged in", "a totally new line") < 0.3);
    }
}
