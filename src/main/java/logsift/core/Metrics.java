package logsift.core;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * What a log measures: how long things that start and end took ({@link Spans}), and the numbers
 * its messages report over time ({@link Numbers}).
 */
public record Metrics(Spans.Result spans, List<Numbers.Series> numbers) {
    public static final Metrics EMPTY = new Metrics(Spans.Result.EMPTY, List.of());

    public boolean isEmpty() {
        return spans.kinds().isEmpty() && numbers.isEmpty();
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
