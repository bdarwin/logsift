package logsift.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Three-way merges: one side's changes are taken, the same change twice once, different changes conflict. */
class Merge3Test {

    private static List<Merge3.Choice> auto(Merge3.Result r) {
        List<Merge3.Choice> out = new ArrayList<>();
        for (Merge3.Chunk c : r.chunks()) {
            out.add(r.automatic(c));
        }
        return out;
    }

    private static List<Merge3.Kind> kinds(Merge3.Result r) {
        return r.chunks().stream().map(Merge3.Chunk::kind).toList();
    }

    @Test
    void changesOnDifferentLinesMergeCleanly() {
        List<String> base = List.of("a", "b", "c", "d", "e");
        List<String> ours = List.of("a", "B", "c", "d", "e");
        List<String> theirs = List.of("a", "b", "c", "D", "e", "f");
        Merge3.Result r = Merge3.merge(base, ours, theirs, TextDiff.Options.DEFAULT);
        assertEquals(0, r.conflicts());
        assertEquals(List.of("a", "B", "c", "D", "e", "f"), r.merged(auto(r), null));
        assertEquals(List.of(Merge3.Kind.SAME, Merge3.Kind.OURS, Merge3.Kind.SAME, Merge3.Kind.THEIRS),
                kinds(r).subList(0, 4));
    }

    @Test
    void theSameChangeOnBothSidesIsTakenOnce() {
        List<String> base = List.of("x", "y", "z");
        List<String> both = List.of("x", "Y", "z");
        Merge3.Result r = Merge3.merge(base, both, both, TextDiff.Options.DEFAULT);
        assertEquals(List.of(Merge3.Kind.SAME, Merge3.Kind.BOTH_SAME, Merge3.Kind.SAME), kinds(r));
        assertEquals(both, r.merged(auto(r), null));
    }

    @Test
    void differentChangesConflict() {
        List<String> base = List.of("one", "two", "three");
        List<String> ours = List.of("one", "TWO (ours)", "three");
        List<String> theirs = List.of("one", "2 (theirs)", "three");
        Merge3.Result r = Merge3.merge(base, ours, theirs, TextDiff.Options.DEFAULT);
        assertEquals(1, r.conflicts());
        Merge3.Chunk c = r.chunks().get(1);
        assertEquals(Merge3.Kind.CONFLICT, c.kind());
        List<Merge3.Choice> choices = auto(r);
        assertEquals(Merge3.Choice.NONE, choices.get(1));
        assertEquals(List.of("one", "two", "three"), r.merged(choices, null), "unresolved: the base meanwhile");
        assertEquals(List.of("one", "<<<<<<< ours", "TWO (ours)", "=======", "2 (theirs)", ">>>>>>> theirs", "three"),
                r.withMarkers(choices, null, "ours", "theirs"));
        choices.set(1, Merge3.Choice.THEIRS_THEN_OURS);
        assertEquals(List.of("one", "2 (theirs)", "TWO (ours)", "three"), r.merged(choices, null));
        List<List<String>> edited = new ArrayList<>(Collections.nCopies(r.chunks().size(), null));
        edited.set(1, List.of("two, by hand"));
        choices.set(1, Merge3.Choice.EDITED);
        assertEquals(List.of("one", "two, by hand", "three"), r.merged(choices, edited));
    }

    @Test
    void insertsAtTheSamePlaceConflictUnlessEqual() {
        List<String> base = List.of("a", "b");
        Merge3.Result r = Merge3.merge(base, List.of("a", "ours", "b"), List.of("a", "theirs", "b"),
                TextDiff.Options.DEFAULT);
        assertEquals(1, r.conflicts());
        Merge3.Result same = Merge3.merge(base, List.of("a", "new", "b"), List.of("a", "new", "b"),
                TextDiff.Options.DEFAULT);
        assertEquals(0, same.conflicts());
        assertEquals(List.of("a", "new", "b"), same.merged(auto(same), null));
    }

    @Test
    void deletesAndEdgesOfTheFile() {
        List<String> base = List.of("first", "keep", "last");
        Merge3.Result r = Merge3.merge(base, List.of("keep", "last"), List.of("first", "keep", "last", "appended"),
                TextDiff.Options.DEFAULT);
        assertEquals(0, r.conflicts());
        assertEquals(List.of("keep", "last", "appended"), r.merged(auto(r), null));
        Merge3.Result empty = Merge3.merge(List.of(), List.of("x"), List.of(), TextDiff.Options.DEFAULT);
        assertEquals(List.of("x"), empty.merged(auto(empty), null));
        Merge3.Result none = Merge3.merge(List.of(), List.of(), List.of(), TextDiff.Options.DEFAULT);
        assertEquals(List.of(), none.merged(auto(none), null));
    }

    @Test
    void manyScatteredChangesKeepTheirPlaces() {
        List<String> base = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            base.add("line " + i);
        }
        List<String> ours = new ArrayList<>(base);
        List<String> theirs = new ArrayList<>(base);
        List<String> want = new ArrayList<>(base);
        for (int i = 10; i < 300; i += 50) {
            ours.set(i, "ours " + i);
            want.set(i, "ours " + i);
        }
        for (int i = 35; i < 300; i += 50) {
            theirs.set(i, "theirs " + i);
            want.set(i, "theirs " + i);
        }
        Merge3.Result r = Merge3.merge(base, ours, theirs, TextDiff.Options.DEFAULT);
        assertEquals(0, r.conflicts());
        assertEquals(want, r.merged(auto(r), null));
    }
}
