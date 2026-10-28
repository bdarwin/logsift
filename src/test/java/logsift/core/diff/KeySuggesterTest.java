package logsift.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The key suggested to match rows by. */
class KeySuggesterTest {
    @TempDir
    Path dir;

    private KeySuggester.Suggestion suggest(String a, String b) throws IOException {
        Path pa = dir.resolve("a.csv");
        Path pb = dir.resolve("b.csv");
        Files.writeString(pa, a);
        Files.writeString(pb, b);
        try (CsvFile fa = CsvFile.open(pa, null); CsvFile fb = CsvFile.open(pb, null)) {
            ColumnMap m = ColumnMap.byName(fa.header(), fb.header());
            KeySuggester.Suggestion s = KeySuggester.suggest(fa, fb, m, null);
            return new KeySuggester.Suggestion(s.pairs().stream().map(p -> m.pairs().get(p).left()).toList(), s.reason());
        }
    }

    @Test
    void prefersAnIdNamedUniqueColumn() throws IOException {
        // row_no is unique too, but order_id is named like a key; amount is unique but measured
        String a = "row_no,amount,order_id,status\n1,1.50,A-1,new\n2,2.25,A-2,new\n3,3.75,A-3,paid\n";
        String b = "row_no,amount,order_id,status\n1,2.25,A-2,new\n2,1.50,A-1,paid\n3,9.99,A-4,new\n";
        KeySuggester.Suggestion s = suggest(a, b);
        assertEquals(List.of(2), s.pairs());
        assertEquals("order_id: unique and filled in on every row of both files", s.reason());
    }

    @Test
    void skipsColumnsWithEmptyOrRepeatedValues() throws IOException {
        String a = "id,code,label\n1,X,a\n1,Y,b\n2,,c\n";
        String b = "id,code,label\n1,X,a\n2,Y,b\n3,Z,c\n";
        KeySuggester.Suggestion s = suggest(a, b);
        assertEquals(List.of(2), s.pairs(), s.reason());
        assertTrue(s.reason().contains("no column is named like an id"), s.reason());
    }

    @Test
    void combinesColumnsWhenNoneIsUniqueAlone() throws IOException {
        String a = "region,sku,qty\neu,1,5\neu,2,5\nus,1,5\nus,2,6\n";
        String b = "region,sku,qty\nus,2,6\neu,1,5\nus,1,7\neu,2,5\n";
        KeySuggester.Suggestion s = suggest(a, b);
        assertEquals(List.of(0, 1), s.pairs(), s.reason());
        assertTrue(s.reason().startsWith("region + sku: no column alone is unique"), s.reason());
    }

    @Test
    void noneWhenNothingIsUnique() throws IOException {
        String a = "level,msg\nINFO,a\nINFO,a\nWARN,b\n";
        String b = "level,msg\nINFO,a\nWARN,b\nWARN,b\n";
        KeySuggester.Suggestion s = suggest(a, b);
        assertFalse(s.found());
        assertTrue(s.reason().endsWith("rows are lined up in file order."), s.reason());
    }

    @Test
    void namesThatSoundLikeKeys() {
        assertTrue(KeySuggester.nameScore("id") > KeySuggester.nameScore("customer_id"));
        assertTrue(KeySuggester.nameScore("customerId") > 0);
        assertTrue(KeySuggester.nameScore("Product Code") > 0);
        assertEquals(0, KeySuggester.nameScore("price"));
        assertEquals(0, KeySuggester.nameScore("paid"), "ends with id, but isn't one");
    }
}
