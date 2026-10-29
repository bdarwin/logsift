package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Pivot tables: groups, columns, sums, counts, averages, percentages, time groups and filters. */
class PivotTest {

    /** Sales: region, product, amount, a date; one blank region. */
    static List<String> sales() {
        List<String> out = new ArrayList<>();
        out.add("region,product,amount,sold_at");
        String[] regions = {"North", "South", "North", "East", "South", "North"};
        String[] products = {"Tea", "Coffee", "Coffee", "Tea", "Tea", "Cake"};
        for (int i = 0; i < 60; i++) {
            out.add(String.format("%s,%s,%d.50,2026-%02d-%02d 10:%02d:00", regions[i % 6], products[i % 6], 10 + i,
                    1 + i % 3, 1 + i % 28, i % 60));
        }
        out.add(",Tea,1,2026-03-05 09:00:00");
        return out;
    }

    private static LogFormat format(List<String> lines) {
        return LogFormat.detectCsv(lines.subList(0, Math.min(100, lines.size())), true);
    }

    private static ColumnProfile.Lines lines(List<String> lines) {
        return (first, count, out) -> {
            for (int i = 0; i < count; i++) {
                out[i] = first + i < lines.size() ? lines.get((int) (first + i)) : null;
            }
        };
    }

    private static Pivot.Result run(List<String> l, Pivot.Spec spec) {
        LogFormat f = format(l);
        Pivot.Fields fields = Pivot.fields(f, l.size(), lines(l), 2026);
        return Pivot.compute(f, l.size(), lines(l), null, spec, fields, ZoneOffset.UTC, () -> false, null);
    }

    private static Pivot.Node kid(Pivot.Node n, String label) {
        return n.kids().stream().filter(k -> k.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void numbers() {
        assertEquals(1234.5, Pivot.number("1,234.5"));
        assertEquals(12.0, Pivot.number("$12"));
        assertEquals(7.0, Pivot.number("7 ms"));
        assertEquals(50.0, Pivot.number("50%"));
        assertEquals(-3e2, Pivot.number("-3e2"));
        assertTrue(Double.isNaN(Pivot.number("A-00001")));
        assertTrue(Double.isNaN(Pivot.number("2026-10-21")));
        assertTrue(Double.isNaN(Pivot.number("item 10")));
        assertTrue(Double.isNaN(Pivot.number("")));
    }

    @Test
    void plainNumbersAreReadFast() {
        assertEquals(1508.0, Pivot.plainNumber("1508"));
        assertEquals(-3.25, Pivot.plainNumber("-3.25"));
        assertEquals(12.5, Pivot.plainNumber("+12.5"));
        assertEquals(12.0, Pivot.plainNumber("12."), "as the full reader takes it");
        assertTrue(Double.isNaN(Pivot.plainNumber("1,234")));
        assertTrue(Double.isNaN(Pivot.plainNumber("-")));
        assertTrue(Double.isNaN(Pivot.plainNumber("7 ms")));
        assertEquals(7.0, Pivot.number("7 ms"), "the rest as before");
        assertEquals(Double.parseDouble("0.1"), Pivot.number("0.1"), "decimals exactly as parsed");
    }

    @Test
    void medianAndPercentiles() {
        List<String> l = new ArrayList<>();
        l.add("host,ms");
        for (int i = 1; i <= 100; i++) {
            l.add((i % 2 == 0 ? "a" : "b") + "," + i);
        }
        Pivot.Result r = run(l, new Pivot.Spec(List.of(new Pivot.Field(0)), List.of(),
                List.of(new Pivot.Value(1, Pivot.Agg.MEDIAN), new Pivot.Value(1, Pivot.Agg.P95),
                        new Pivot.Value(1, Pivot.Agg.P99)), Map.of()));
        Pivot.Node root = r.root();
        assertEquals(50.5, r.value(root, -1, 0), 1e-9, "the median of 1..100");
        assertEquals(95.05, r.value(root, -1, 1), 1e-9, "interpolated, as spreadsheets do");
        assertEquals(99.01, r.value(root, -1, 2), 1e-9);
        assertEquals(51.0, r.value(kid(root, "a"), -1, 0), 1e-9, "2, 4 … 100");
        assertEquals(50.0, r.value(kid(root, "b"), -1, 0), 1e-9, "1, 3 … 99");
        assertTrue(Pivot.Agg.MEDIAN.numeric() && Pivot.Agg.P95.numeric());
    }

    @Test
    void bigTablesAreReadInParallelWithTheSameResult() {
        // over PARALLEL_LINES lines: split and worked out on several threads, added in file order
        List<String> l = new ArrayList<>();
        l.add("region,product,amount");
        String[] regions = {"North", "South", "East", "West", "Central"};
        String[] products = {"Tea", "Coffee", "Cake"};
        double[] sums = new double[regions.length];
        long[] counts = new long[regions.length];
        java.util.List<String> firstSeen = new ArrayList<>();
        int n = Pivot.PARALLEL_LINES * 3 + 123;
        for (int i = 0; i < n; i++) {
            int r = (i * 7 + i / 1000) % regions.length;
            double amount = (i % 997) / 4.0;
            l.add(regions[r] + "," + products[i % 3] + "," + amount);
            sums[r] += amount;
            counts[r]++;
            if (!firstSeen.contains(regions[r])) {
                firstSeen.add(regions[r]);
            }
        }
        Pivot.Result res = run(l, new Pivot.Spec(List.of(new Pivot.Field(0)), List.of(new Pivot.Field(1)),
                List.of(new Pivot.Value(2, Pivot.Agg.SUM), new Pivot.Value(2, Pivot.Agg.COUNT)), Map.of()));
        assertEquals(n, res.rows());
        for (int r = 0; r < regions.length; r++) {
            Pivot.Node k = kid(res.root(), regions[r]);
            assertEquals(sums[r], res.value(k, -1, 0), 1e-6, regions[r]);
            assertEquals(counts[r], res.value(k, -1, 1), 1e-9, regions[r]);
        }
        assertEquals(List.of("Cake", "Coffee", "Tea"), res.colKeys().stream().map(c -> c.get(0)).toList());
    }

    @Test
    void manyValuesKeepAFairSample() {
        Pivot.Acc a = new Pivot.Acc();
        int n = Pivot.MAX_KEPT * 2;
        for (int i = 0; i < n; i++) {
            a.add("", i, Pivot.Agg.MEDIAN);
        }
        assertEquals(Pivot.MAX_KEPT, a.nKept);
        double median = a.get(Pivot.Agg.MEDIAN);
        assertTrue(Math.abs(median - n / 2.0) < n * 0.01, "close to the true median: " + median);
    }

    @Test
    void fieldKinds() {
        List<String> l = sales();
        Pivot.Fields f = Pivot.fields(format(l), l.size(), lines(l), 2026);
        assertEquals(List.of(Pivot.Kind.TEXT, Pivot.Kind.TEXT, Pivot.Kind.NUMBER, Pivot.Kind.TIME), f.kinds());
        assertEquals(List.of("region", "product", "amount", "sold_at"), f.titles());
    }

    @Test
    void rowsByRegionWithSumAndCount() {
        Pivot.Result r = run(sales(), new Pivot.Spec(List.of(new Pivot.Field(0)), List.of(),
                List.of(new Pivot.Value(2, Pivot.Agg.SUM), new Pivot.Value(2, Pivot.Agg.COUNT),
                        new Pivot.Value(2, Pivot.Agg.AVERAGE)), Map.of()));
        assertEquals(61, r.rows());
        assertEquals(List.of("East", "North", "South", Pivot.BLANK),
                r.root().kids().stream().map(Pivot.Node::label).toList(), "sorted, blank last");
        Pivot.Node north = kid(r.root(), "North");
        // North: i % 6 in {0, 2, 5}: 30 rows
        assertEquals(30, north.rows());
        double sum = 0;
        for (int i = 0; i < 60; i++) {
            if (i % 6 == 0 || i % 6 == 2 || i % 6 == 5) {
                sum += 10 + i + 0.5;
            }
        }
        assertEquals(sum, r.value(north, -1, 0), 1e-9);
        assertEquals(30.0, r.value(north, -1, 1));
        assertEquals(sum / 30, r.value(north, -1, 2), 1e-9);
        double total = 0;
        for (int i = 0; i < 60; i++) {
            total += 10.5 + i;
        }
        assertEquals(total + 1, r.value(r.root(), -1, 0), 1e-9, "the grand total");
        assertEquals("Sum of amount", r.valueTitle(0));
        assertEquals(0, r.colKeys().size());
    }

    @Test
    void columnsAndPercentages() {
        Pivot.Result r = run(sales(), new Pivot.Spec(List.of(new Pivot.Field(0)), List.of(new Pivot.Field(1)),
                List.of(new Pivot.Value(-1, Pivot.Agg.COUNT, Pivot.ShowAs.PCT_ROW)), Map.of()));
        assertEquals(List.of(List.of("Cake"), List.of("Coffee"), List.of("Tea")), r.colKeys());
        Pivot.Node north = kid(r.root(), "North");
        // North sells Tea (i%6==0), Coffee (2) and Cake (5): 10 rows each
        assertEquals(100.0 / 3, r.value(north, 0, 0), 1e-9);
        assertEquals(100.0, r.value(north, -1, 0), 1e-9);
        assertEquals(10.0, r.raw(north, 2, 0));
        Pivot.Node east = kid(r.root(), "East");
        assertEquals(0.0, r.raw(east, 0, 0), "no cake in the east");
        assertEquals(0.0, r.value(east, 0, 0));
        assertEquals("Count of rows (%)", r.valueTitle(0));
    }

    @Test
    void nestedRowsHaveSubtotals() {
        Pivot.Result r = run(sales(), new Pivot.Spec(List.of(new Pivot.Field(0), new Pivot.Field(1)), List.of(),
                List.of(), Map.of()));
        Pivot.Node south = kid(r.root(), "South");
        assertEquals(List.of("Coffee", "Tea"), south.kids().stream().map(Pivot.Node::label).toList());
        assertEquals(20.0, r.value(south, -1, 0));
        assertEquals(10.0, r.value(kid(south, "Tea"), -1, 0));
        assertEquals(List.of("South", "Tea"), kid(south, "Tea").path());
        assertEquals(7, r.level(2).size(), "North 3, South 2, East 1, blank 1");
    }

    @Test
    void timesByMonthAndWeekday() {
        Pivot.Result r = run(sales(), new Pivot.Spec(List.of(new Pivot.Field(3, Pivot.Group.MONTH)), List.of(),
                List.of(), Map.of()));
        assertEquals(List.of("2026-01", "2026-02", "2026-03"), r.root().kids().stream().map(Pivot.Node::label).toList());
        assertEquals(21.0, r.value(kid(r.root(), "2026-03"), -1, 0), "20 and the blank region's row");
        assertEquals("sold_at (Month)", r.fieldTitle(r.spec().rows().get(0)));
        Pivot.Result q = run(sales(), new Pivot.Spec(List.of(new Pivot.Field(3, Pivot.Group.QUARTER)), List.of(),
                List.of(), Map.of()));
        assertEquals(List.of("2026 Q1"), q.root().kids().stream().map(Pivot.Node::label).toList());
        Pivot.Result w = run(sales(), new Pivot.Spec(List.of(new Pivot.Field(3, Pivot.Group.WEEKDAY)), List.of(),
                List.of(), Map.of()));
        assertTrue(w.root().kids().get(0).label().startsWith("1 "), "Monday first");
    }

    @Test
    void filtersKeepSomeValuesAndListThemAll() {
        Pivot.Result r = run(sales(), new Pivot.Spec(List.of(new Pivot.Field(1)), List.of(), List.of(),
                Map.of(0, Set.of("North"))));
        assertEquals(30, r.rows());
        assertEquals(List.of("Cake", "Coffee", "Tea"), r.root().kids().stream().map(Pivot.Node::label).toList());
        assertEquals(List.of("East", "North", "South", Pivot.BLANK), r.filterValues(0), "every value, to choose from");
    }

    @Test
    void aFilterFieldWithEveryValueKept() {
        java.util.Map<Integer, Set<String>> all = new java.util.HashMap<>();
        all.put(0, null);
        Pivot.Result r = run(sales(), new Pivot.Spec(List.of(new Pivot.Field(1)), List.of(), List.of(), all));
        assertEquals(61, r.rows());
        assertEquals(4, r.filterValues(0).size());
    }

    @Test
    void sortByValueAndCountDistinct() {
        Pivot.Result r = run(sales(), new Pivot.Spec(List.of(new Pivot.Field(1)), List.of(),
                List.of(new Pivot.Value(0, Pivot.Agg.COUNT_DISTINCT), new Pivot.Value(2, Pivot.Agg.MAX)), Map.of()));
        assertEquals(3.0, r.value(kid(r.root(), "Tea"), -1, 0), "North, East, South: blank isn't counted");
        r.sort(1, -1, true);
        assertEquals("Cake", r.root().kids().get(0).label(), "Cake has the largest amount (69.5)");
        assertEquals(69.5, r.value(r.root().kids().get(0), -1, 1));
        r.sort(-1, -1, true);
        assertEquals("Tea", r.root().kids().get(0).label(), "Z to A");
    }

    @Test
    void cancelledAndLabels() {
        List<String> l = sales();
        LogFormat f = format(l);
        assertNull(Pivot.compute(f, l.size(), lines(l), null, new Pivot.Spec(List.of(), List.of(), List.of(), Map.of()),
                null, ZoneOffset.UTC, () -> true, null));
        assertTrue(Pivot.compareLabels("2", "10") < 0);
        assertTrue(Pivot.compareLabels("b", "A") > 0);
        assertTrue(Pivot.compareLabels(Pivot.BLANK, "zzz") > 0);
        assertEquals("2026-10-29 14:00", Pivot.timeLabel(java.time.LocalDateTime.of(2026, 10, 29, 14, 37)
                .toInstant(ZoneOffset.UTC).toEpochMilli(), Pivot.Group.HOUR, ZoneOffset.UTC));
        assertFalse(Pivot.Agg.COUNT.numeric());
    }
}
