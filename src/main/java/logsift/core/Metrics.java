package logsift.core;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * What a log measures: how long things that start and end took ({@link Spans}), and the numbers
 * its messages report over time ({@link Numbers}).
 */
public record Metrics(Spans.Result spans, List<Numbers.Series> numbers, boolean filtered) {
    public static final Metrics EMPTY = new Metrics(Spans.Result.EMPTY, List.of());

    public Metrics(Spans.Result spans, List<Numbers.Series> numbers) {
        this(spans, numbers, false);
    }

    /**
     * Only what the kept lines measure: spans by their start line (paired over the whole log),
     * numbers by their line. Measurements with nothing left are left out.
     */
    public Metrics keep(java.util.function.LongPredicate include) {
        List<Spans.Kind> kinds = new java.util.ArrayList<>();
        for (Spans.Kind k : spans.kinds()) {
            Spans.Kind left = k.keep(include);
            if (left != null) {
                kinds.add(left);
            }
        }
        List<Numbers.Series> nums = new java.util.ArrayList<>();
        for (Numbers.Series s : numbers) {
            Numbers.Series left = s.source() == Numbers.Source.ROWS ? null : s.keep(include);
            if (left != null) {
                nums.add(left);
            }
        }
        return new Metrics(new Spans.Result(List.copyOf(kinds)), List.copyOf(nums), true);
    }

    public boolean isEmpty() {
        return spans.kinds().isEmpty() && numbers.isEmpty();
    }

    /** These, and the measurements of the log's columns ({@link ColumnMetrics}) after them. */
    public Metrics withColumns(List<Numbers.Series> columns) {
        if (columns.isEmpty()) {
            return this;
        }
        List<Numbers.Series> all = new java.util.ArrayList<>(numbers);
        all.addAll(columns);
        return new Metrics(spans, List.copyOf(all), filtered);
    }

    /** Both, from the log's message kinds; null if cancelled. */
    public static Metrics analyze(List<MessageStats.Template> templates, Spans.Lines lines, RequestTrace.Reader reader,
                                  BooleanSupplier cancelled) {
        Spans.Result s = Spans.analyze(templates, lines, reader, cancelled);
        if (s == null) {
            return null;
        }
        // a start or end message's numbers ("Finished job … in 812 ms") are the durations already shown
        java.util.Set<MessageStats.Template> paired = new java.util.HashSet<>();
        for (Spans.Kind k : s.kinds()) {
            paired.add(k.start());
            paired.add(k.end());
        }
        List<MessageStats.Template> rest = templates.stream().filter(t -> !paired.contains(t)).toList();
        List<Numbers.Series> n = Numbers.analyze(rest, lines, reader, cancelled);
        return n == null ? null : new Metrics(s, n);
    }
}
