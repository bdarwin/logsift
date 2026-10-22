package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The user's own log formats: patterns, time formats, importing layouts and guessing. */
class CustomFormatTest {
    @TempDir
    Path dir;

    private static final List<String> PIPES = List.of(
            "21.10.26 10:02:13.250|E|OrderService|payment failed: card declined",
            "21.10.26 10:02:14.001|I|OrderService|order 42 placed",
            "    at com.acme.Pay.charge(Pay.java:12)",
            "21.10.26 10:02:15.777|W|Cache|evicting 10 entries",
            "21.10.26 10:03:00.000|I|Http|GET /health 200");

    @Test
    void splitsLinesWithAPattern() {
        LogFormat f = new CustomFormat("Pipes", "{time}|{level}|{component}|{message}", "dd.MM.yy HH:mm:ss.SSS", "")
                .compile();
        assertTrue(f.isCustom());
        // "source" is filled in for merged logs only
        assertEquals(List.of("source", "time", "level", "component", "message"), f.columns());
        String[] v = f.split(PIPES.get(0), null);
        assertNull(v[0]);
        assertEquals("21.10.26 10:02:13.250", v[1]);
        assertEquals("E", v[2]);
        assertEquals("OrderService", v[3]);
        assertEquals("payment failed: card declined", v[4]);
        assertEquals("node1", f.value("<node1> " + PIPES.get(0), "source"));
        assertFalse(f.matches(PIPES.get(2)), "a stack trace line continues the entry");
        assertEquals(new int[] {4, 5}[0], Formats.matchCount(f, PIPES)[0]);
    }

    @Test
    void readsTimesWithTheUsersTimeFormat() {
        CustomFormat c = new CustomFormat("Pipes", "{time}|{level}|{component}|{message}", "dd.MM.yy HH:mm:ss.SSS", "");
        Formats.Detected d = Formats.detect(PIPES, List.of(c), "app.log", null, 2026);
        assertEquals("Pipes", d.columns().name());
        assertNotNull(d.times());
        long t = d.times().parse(PIPES.get(0));
        assertEquals(TimeFormat.daysFromCivil(2026, 10, 21) * 86_400_000L + ((10 * 60 + 2) * 60 + 13) * 1000L + 250, t);
        assertEquals(TimeFormat.NONE, d.times().parse(PIPES.get(2)));
        assertTrue(d.entries().startsEntry(PIPES.get(0)));
        assertFalse(d.entries().startsEntry(PIPES.get(2)));
    }

    @Test
    void timeInTheMiddleOfALine() {
        List<String> lines = List.of(
                "node7 | pid 41 | 2026-10-21 10:02:13 | started",
                "node7 | pid 41 | 2026-10-21 10:02:14 | ready",
                "node7 | pid 41 | 2026-10-21 10:02:15 | stopping");
        CustomFormat c = new CustomFormat("Nodes", "{host} | pid {pid} | {time} | {message}", "", "");
        Formats.Detected d = Formats.detect(lines, List.of(c), "x.log", null, 2026);
        assertEquals("Nodes", d.columns().name());
        assertEquals(TimeFormat.daysFromCivil(2026, 10, 21) * 86_400_000L + 36_134_000L, d.times().parse(lines.get(1)));
    }

    @Test
    void fileNamesAndChoosingByHand() {
        CustomFormat c = new CustomFormat("Mine", "{word} {message}", "", "app*.log; *.trace");
        assertTrue(c.appliesTo("APP-1.log"));
        assertTrue(c.appliesTo("x.trace"));
        assertFalse(c.appliesTo("other.log"));
        List<String> lines = List.of("2026-10-21 10:00:00 INFO a", "2026-10-21 10:00:01 INFO b",
                "2026-10-21 10:00:02 WARN c");
        assertEquals("Mine", Formats.detect(lines, List.of(c), "app.log", null, 2026).columns().name());
        assertEquals("time level message", Formats.detect(lines, List.of(c), "x.log", null, 2026).columns().name());
        assertNull(Formats.detect(lines, List.of(c), "app.log", Formats.PLAIN, 2026).columns());
        assertNotNull(Formats.detect(lines, List.of(c), "app.log", Formats.PLAIN, 2026).times());
        assertEquals("Mine", Formats.detect(lines, List.of(c), "x.log", "Mine", 2026).columns().name());
    }

    @Test
    void explainsMistakes() {
        assertThrows(IllegalArgumentException.class, () -> new CustomFormat("x", "{time} {level", "", "").compile());
        assertThrows(IllegalArgumentException.class, () -> new CustomFormat("x", "{a}{b}", "", "").compile());
        assertThrows(IllegalArgumentException.class, () -> new CustomFormat("x", "{a} {a}", "", "").compile());
        assertThrows(IllegalArgumentException.class, () -> new CustomFormat("x", "no columns", "", "").compile());
        assertThrows(IllegalArgumentException.class, () -> new CustomFormat("x", "{time} {m}", "yyyy-qq", "").compile());
        assertThrows(IllegalArgumentException.class, () -> new CustomFormat("x", "(?<a>[", "", "").compile());
        // a broken format is skipped by detection
        assertNull(Formats.detect(List.of("a", "b", "c"), List.of(new CustomFormat("x", "{a", "", "*")), "a.log",
                null, 2026).columns());
    }

    @Test
    void savesAndLoads() throws Exception {
        Path f = dir.resolve("formats.properties");
        List<CustomFormat> list = List.of(new CustomFormat("A", "{time} {message}", "HH:mm:ss", "*.a"),
                new CustomFormat("B = b", "{x}|{message}", "", ""));
        CustomFormat.save(f, list);
        assertEquals(list, CustomFormat.load(f));
        assertTrue(CustomFormat.load(dir.resolve("missing")).isEmpty());
    }

    // ---------------------------------------------------------------- importing layouts

    @Test
    void importsLogbackPatterns() {
        var r = LayoutImport.convert("%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
        assertEquals("{time} [{thread}] {level} {logger} - {message}", r.pattern());
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", r.timePattern());
        LogFormat f = new CustomFormat("lb", r.pattern(), r.timePattern(), "").compile();
        String line = "2026-10-21 10:02:13.250 [main] INFO  com.acme.App - started in 2 s";
        assertEquals("INFO", f.value(line, "level"));
        assertEquals("com.acme.App", f.value(line, "logger"));
        assertEquals("started in 2 s", f.value(line, "message"));
    }

    @Test
    void importsWholeConfigFiles() {
        String xml = """
                <configuration>
                  <appender name="FILE" class="ch.qos.logback.core.FileAppender">
                    <encoder>
                      <pattern>%date{ISO8601} %highlight(%-5level) [%15.15thread] %cyan(%logger{39}) : %X{traceId:-} %msg%n%ex</pattern>
                    </encoder>
                  </appender>
                </configuration>""";
        var r = LayoutImport.convert(xml);
        assertEquals("{time} {level} [{thread}] {logger} : {traceId} {message}", r.pattern());
        assertEquals("yyyy-MM-dd HH:mm:ss,SSS", r.timePattern());

        var l4j2 = LayoutImport.convert("<PatternLayout pattern=\"%d{HH:mm:ss,SSS} %-5p %c{1}:%L - %m%n\"/>");
        assertEquals("{time} {level} {logger}:{line} - {message}", l4j2.pattern());
        assertEquals("HH:mm:ss,SSS", l4j2.timePattern());

        var props = LayoutImport.convert("log4j.appender.R.layout.ConversionPattern=%d{ABSOLUTE} %5p %t %c - %m%n");
        assertEquals("{time} {level} {thread} {logger} - {message}", props.pattern());
        assertEquals("HH:mm:ss,SSS", props.timePattern());

        // Spring Boot's default console layout
        var spring = LayoutImport.convert("%clr(%d{${LOG_DATEFORMAT_PATTERN:-yyyy-MM-dd'T'HH:mm:ss.SSSXXX}}){faint} "
                + "%clr(${LOG_LEVEL_PATTERN:-%5p}) %clr(${PID:- }){magenta} %clr(---){faint} %clr([%15.15t]){faint} "
                + "%clr(%-40.40logger{39}){cyan} %clr(:){faint} %m%n${LOG_EXCEPTION_CONVERSION_WORD:-%wEx}");
        LogFormat sf = new CustomFormat("spring", spring.pattern(), spring.timePattern(), "").compile();
        String line = "2026-10-21T10:02:13.250+02:00  INFO 4242 --- [           main] com.acme.DemoApplication"
                + "                 : Started DemoApplication in 1.2 seconds";
        assertTrue(sf.matches(line), spring.pattern() + " / " + spring.timePattern());
        assertEquals("4242", sf.value(line, "pid"));
        assertEquals("com.acme.DemoApplication", sf.value(line, "logger"));
        assertEquals("Started DemoApplication in 1.2 seconds", sf.value(line, "message"));
        assertNotNull(TimeFormat.inColumn(sf, List.of(line), 2026));
        assertTrue(TimeFormat.inColumn(sf, List.of(line), 2026).parse(line) != TimeFormat.NONE);
    }

    @Test
    void importsPythonFormats() {
        var r = LayoutImport.convert("format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'");
        assertEquals("{time} - {logger} - {level} - {message}", r.pattern());
        LogFormat f = new CustomFormat("py", r.pattern(), r.timePattern(), "").compile();
        String line = "2026-10-21 10:02:13,250 - shop.orders - WARNING - low stock";
        assertEquals("shop.orders", f.value(line, "logger"));
        assertTrue(TimeFormat.inColumn(f, List.of(line), 2026).parse(line) != TimeFormat.NONE);
        assertNull(LayoutImport.convert("just some text"));
    }

    // ---------------------------------------------------------------- guessing

    @Test
    void guessesFromSampleLines() {
        List<String> lines = List.of(
                "2026-10-21 10:02:13,250 [worker-1] ERROR com.acme.Pay - charge failed",
                "java.lang.IllegalStateException: declined",
                "    at com.acme.Pay.charge(Pay.java:12)",
                "2026-10-21 10:02:14,001 [main] INFO com.acme.App - order 42 placed",
                "2026-10-21 10:02:15,777 [worker-2] WARN com.acme.Cache - evicting");
        var g = FormatGuess.guess(lines);
        assertEquals("{time} [{thread}] {level} {logger} - {message}", g.pattern());
        assertEquals(3, g.matching());

        var pipes = FormatGuess.guess(PIPES);
        assertEquals(4, pipes.matching(), pipes.pattern());
        assertEquals("{time}|{level}|{field3}|{message}", pipes.pattern());
        assertEquals("dd.MM.yy HH:mm:ss.SSS", pipes.timePattern());
        LogFormat f = new CustomFormat("p", pipes.pattern(), pipes.timePattern(), "").compile();
        assertEquals("OrderService", f.value(PIPES.get(0), "field3"), pipes.pattern());
        assertEquals("", g.timePattern(), "a timestamp Logsift reads by itself");
        // month first when a value can only be read that way
        assertEquals("MM/dd/yyyy HH:mm:ss", FormatGuess.timePattern("{time} {message}",
                List.of("10/21/2026 10:02:13 a", "10/22/2026 10:02:13 b")));
        assertEquals("d.M.yyyy H:mm", FormatGuess.timePattern("{time} {message}", List.of("1.2.2026 9:05 a")));

        var syslogish = FormatGuess.guess(List.of("E 10:02:13 boot: disk failed", "I 10:02:14 boot: ok",
                "W 10:02:15 net: slow"));
        assertNotNull(syslogish);
        assertEquals(3, syslogish.matching(), syslogish.pattern());
    }
}
