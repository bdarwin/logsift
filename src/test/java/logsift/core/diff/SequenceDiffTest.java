package logsift.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Lining up sequences: runs that cover both sides, minimal for small cases, bounded for big ones. */
class SequenceDiffTest {

    private static long[] seq(String s) {
        return s.chars().mapToLong(c -> c).toArray();
    }

    private static String render(List<Edit> edits) {
        StringBuilder sb = new StringBuilder();
        for (Edit e : edits) {
            sb.append(e.kind().name().charAt(0)).append(e.aLength()).append('/').append(e.bLength()).append(' ');
        }
        return sb.toString().trim();
    }

    /** The edits cover both sequences in order, and EQUAL runs really are equal. */
    private static void checkCovers(long[] a, long[] b, List<Edit> edits) {
        int ai = 0;
        int bi = 0;
        for (Edit e : edits) {
            assertEquals(ai, e.aStart());
            assertEquals(bi, e.bStart());
            if (e.kind() == Edit.Kind.EQUAL) {
                assertEquals(e.aLength(), e.bLength());
                for (int i = 0; i < e.aLength(); i++) {
                    assertEquals(a[e.aStart() + i], b[e.bStart() + i]);
                }
            }
            ai = e.aEnd();
            bi = e.bEnd();
        }
        assertEquals(a.length, ai);
        assertEquals(b.length, bi);
    }

    private static int equalCount(List<Edit> edits) {
        return edits.stream().filter(e -> e.kind() == Edit.Kind.EQUAL).mapToInt(Edit::aLength).sum();
    }

    private static int lcs(long[] a, long[] b) {
        int[][] t = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                t[i][j] = a[i] == b[j] ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1]);
            }
        }
        return t[0][0];
    }

    @Test
    void simpleCases() {
        assertEquals("E3/3", render(SequenceDiff.diff(seq("abc"), seq("abc"))));
        assertEquals("", render(SequenceDiff.diff(seq(""), seq(""))));
        assertEquals("I0/2", render(SequenceDiff.diff(seq(""), seq("ab"))));
        assertEquals("D2/0", render(SequenceDiff.diff(seq("ab"), seq(""))));
        assertEquals("E1/1 I0/1 E2/2", render(SequenceDiff.diff(seq("abc"), seq("aXbc"))));
        assertEquals("E1/1 D1/0 E1/1", render(SequenceDiff.diff(seq("abc"), seq("ac"))));
        assertEquals("E1/1 R1/1 E1/1", render(SequenceDiff.diff(seq("abc"), seq("aXc"))));
    }

    @Test
    void minimalOnRandomSmallInputs() {
        Random rnd = new Random(7);
        for (int t = 0; t < 2000; t++) {
            long[] a = new long[rnd.nextInt(14)];
            long[] b = new long[rnd.nextInt(14)];
            for (int i = 0; i < a.length; i++) {
                a[i] = rnd.nextInt(4);
            }
            for (int i = 0; i < b.length; i++) {
                b[i] = rnd.nextInt(4);
            }
            List<Edit> e = SequenceDiff.diff(a, b);
            checkCovers(a, b, e);
            // patience anchors may give up a little for readability; Myers alone is minimal
            assertTrue(equalCount(e) <= lcs(a, b));
            List<Edit> m = SequenceDiff.diff(a, b, 1000, false);
            checkCovers(a, b, m);
            assertEquals(lcs(a, b), equalCount(m));
        }
    }

    @Test
    void myersAloneIsMinimalOnLongerInputs() {
        Random rnd = new Random(11);
        for (int t = 0; t < 1000; t++) {
            long[] a = new long[rnd.nextInt(40)];
            long[] b = new long[rnd.nextInt(40)];
            for (int i = 0; i < a.length; i++) {
                a[i] = rnd.nextInt(2);
            }
            for (int i = 0; i < b.length; i++) {
                b[i] = rnd.nextInt(2);
            }
            List<Edit> e = SequenceDiff.diff(a, b, 1000, false);
            checkCovers(a, b, e);
            assertEquals(lcs(a, b), equalCount(e));
        }
    }

    @Test
    void movedBlockIsAnchoredByUniqueLines() {
        // unique lines line up; repeated blank lines don't pull the diff apart
        long[] a = {1, 0, 2, 0, 3, 0, 4};
        long[] b = {1, 0, 2, 0, 9, 0, 3, 0, 4};
        List<Edit> e = SequenceDiff.diff(a, b);
        checkCovers(a, b, e);
        assertEquals(7, equalCount(e));
    }

    @Test
    void bigGapsStayFast() {
        int n = 200_000;
        long[] a = new long[n];
        long[] b = new long[n];
        Random rnd = new Random(3);
        for (int i = 0; i < n; i++) {
            a[i] = rnd.nextLong();
            b[i] = rnd.nextInt(10) == 0 ? rnd.nextLong() : a[i];
        }
        long t0 = System.nanoTime();
        List<Edit> e = SequenceDiff.diff(a, b);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        checkCovers(a, b, e);
        assertTrue(equalCount(e) > n * 0.85, "the unchanged rows line up");
        assertTrue(ms < 10_000, ms + " ms");
        // nothing in common at all, with repeats (no anchors): a replacement, not quadratic time
        long[] x = new long[50_000];
        long[] y = new long[50_000];
        for (int i = 0; i < x.length; i++) {
            x[i] = i % 3;
            y[i] = 3 + i % 5;
        }
        t0 = System.nanoTime();
        List<Edit> r = SequenceDiff.diff(x, y);
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000);
        checkCovers(x, y, r);
        assertEquals(List.of(new Edit(Edit.Kind.REPLACE, 0, x.length, 0, y.length)), r);
    }

    @Test
    void navigatorStops() {
        boolean[] ch = {false, true, true, false, true, false, false, true};
        ChangeNavigator rows = ChangeNavigator.of(ch.length, i -> ch[i], false);
        ChangeNavigator hunks = ChangeNavigator.of(ch.length, i -> ch[i], true);
        assertEquals(4, rows.count());
        assertEquals(3, hunks.count());
        assertEquals(1, rows.next(-1));
        assertEquals(2, rows.next(1));
        assertEquals(4, hunks.next(1));
        assertEquals(-1, rows.next(7));
        assertEquals(4, rows.previous(7));
        assertEquals(-1, rows.previous(1));
        assertEquals(1, hunks.previous(4));
        assertEquals(3, rows.ordinal(4));
        List<Integer> walk = new ArrayList<>();
        for (int p = hunks.next(-1); p >= 0; p = hunks.next(p)) {
            walk.add(p);
        }
        assertEquals(List.of(1, 4, 7), walk);
    }
}
