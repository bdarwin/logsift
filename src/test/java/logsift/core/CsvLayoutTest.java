package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A table's header found below a title, rows ignored by hand, and empty columns. */
class CsvLayoutTest {

    private static List<String> lines(String text) {
        return List.of(text.split("\n", -1));
    }

    private static List<String> rows(LogFormat f, List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (f.isRow(l)) {
                out.add(f.split(l, null)[0]);
            }
        }
        return out;
    }

    @Test
    void aTitleAboveTheTableIsntItsHeader() {
        List<String> l = lines("Sales report, Q3 2026\n\nregion,product,amount,qty\nEU,Widget,10,1\nUS,Gadget,20,2\n"
                + "EU,Gadget,30,3\n");
        // the title has a comma: two fields over a table of four, not a header
        LogFormat f = LogFormat.detectCsv(l, true);
        assertNotNull(f);
        assertEquals(List.of("region", "product", "amount", "qty"), f.headerNames());
        assertEquals(2, f.headerLine());
        assertFalse(f.isRow(l.get(0)), "the title is no row");
        assertEquals(List.of("EU", "US", "EU"), rows(f, l));

        // a title without a separator at all
        List<String> plain = lines("Exported from the shop\nid;name\n1;a\n2;b\n");
        LogFormat g = LogFormat.detectCsv(plain, true);
        assertEquals(List.of("id", "name"), g.headerNames());
        assertEquals(';', g.delimiter());
        assertEquals(List.of("1", "2"), rows(g, plain));
    }

    @Test
    void aRowOfEmptyNamesIsntTheHeader() {
        List<String> l = lines(",,,\nid,name,amount,note\n1,a,5,x\n2,b,6,\n");
        LogFormat f = LogFormat.detectCsv(l, true);
        assertEquals(List.of("id", "name", "amount", "note"), f.headerNames());
        assertFalse(f.isRow(",,,"));
        assertNull(f.split(",,,", null));
        assertEquals(List.of("1", "2"), rows(f, l));
    }

    @Test
    void aTableThatStartsWithItsHeaderReadsAsBefore() {
        List<String> l = lines("id,name\n1,a\n2,b\n");
        LogFormat f = LogFormat.detectCsv(l, true);
        assertEquals(0, f.headerLine());
        assertFalse(f.skipsRows());
        assertEquals("", f.rowsKey());
        for (LogFormat built : LogFormat.presets()) {
            assertFalse(built.isSkipped("2026-01-01 10:00:00 INFO x"), built.name()); // no rows skipped, not null
            assertFalse(built.skipsRows());
        }
        // not named .csv: no header looked for further down (a log isn't a table)
        assertNull(LogFormat.detectCsv(lines("Report\nid,name\n1,a\n2,b\n3,c\n4,d\n5,e\n"), false));
    }

    @Test
    void theHeaderAndIgnoredRowsChosenByHand() {
        List<String> l = lines("id,name,amount\nTotal,,30\n1,a,10\n2,b,20\n");
        CsvLayout ignoreTotal = CsvLayout.NONE.withIgnored("Total,,30", true);
        LogFormat f = LogFormat.detectCsv(l, true, ignoreTotal);
        assertTrue(f.skipsRows());
        assertTrue(f.isSkipped("Total,,30"));
        assertTrue(f.isSkipped("Total,,30\r"), "a Windows line end is the same row");
        assertEquals(List.of("1", "2"), rows(f, l));
        assertNull(f.value("Total,,30", "amount"), "a filter on a column doesn't see it");
        assertFalse(f.rowsKey().isEmpty());

        // the second line as header: the first isn't a row then
        List<String> two = lines("old_id,old_name\nid,name\n1,a\n2,b\n");
        LogFormat g = LogFormat.detectCsv(two, true, CsvLayout.NONE.withHeaderLine(1));
        assertEquals(List.of("id", "name"), g.headerNames());
        assertEquals(1, g.headerLine());
        assertEquals(List.of("1", "2"), rows(g, two));
        // a header line that is blank or past the sample: found as usual
        assertEquals(0, LogFormat.detectCsv(two, true, CsvLayout.NONE.withHeaderLine(99)).headerLine());
    }

    @Test
    void layoutsAreKeptAsText() {
        CsvLayout l = CsvLayout.NONE.withHeaderLine(3).withIgnored("Total,1", true).withIgnored("Sum,2", true);
        assertEquals(l, CsvLayout.decode(l.encode()));
        assertTrue(CsvLayout.decode(l.encode()).ignores("Sum,2"));
        assertFalse(l.withIgnored("Sum,2", false).ignores("Sum,2"));
        assertEquals(CsvLayout.NONE, CsvLayout.decode(""));
        assertEquals(CsvLayout.NONE, CsvLayout.decode("nonsense;i=zz"));
        assertEquals("", CsvLayout.NONE.encode());
        // a row over several lines hashes the same from a Windows file, and with a byte order mark
        assertEquals(CsvLayout.hash("a,\"x\ny\""), CsvLayout.hash("a,\"x\r\ny\"\r"));
        assertEquals(CsvLayout.hash("﻿id,name"), CsvLayout.hash("id,name"));
    }

    @Test
    void emptyColumnsAreFound() {
        List<String> l = lines("id,name,,notes,\n1,a,,,\n2,b,,,\n3,c,, ,\n");
        LogFormat f = LogFormat.detectCsv(l, true);
        List<String> empty = EmptyColumns.find(f, (first, count, out) -> {
            for (int i = 0; i < count; i++) {
                out[i] = first + i < l.size() ? l.get((int) (first + i)) : null;
            }
        }, l.size(), () -> false);
        assertEquals(List.of("column 3", "notes", "column 5"), empty);
        assertNull(EmptyColumns.find(f, (a, b, c) -> { }, l.size(), () -> true), "stopped");
        assertEquals(List.of(), EmptyColumns.find(f, (a, b, c) -> { }, 0, () -> false), "no rows: none known");
    }
}
