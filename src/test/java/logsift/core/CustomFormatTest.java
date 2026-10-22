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
    void importsLog4j2ConfigsWithProperties() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Configuration status="WARN">
                  <Properties>
                    <Property name="LOG_DIR">logs</Property>
                    <Property name="LOG_PATTERN">%d{yyyy-MM-dd HH:mm:ss.SSS} %5p ${sys:PID} --- [%15.15t] %-40.40c{1.} : %m%n%xwEx</Property>
                    <!-- <Property name="LOG_PATTERN">%m%n</Property> -->
                  </Properties>
                  <Appenders>
                    <Console name="Console" target="SYSTEM_OUT" follow="true">
                      <PatternLayout pattern="%style{%d{ISO8601}}{black} %highlight{%-5level }[%style{%t}{bright,blue}] %style{%C{1.}}{bright,yellow}: %msg%n%throwable"/>
                    </Console>
                    <RollingFile name="RollingFile" fileName="${LOG_DIR}/app.log"
                                 filePattern="${LOG_DIR}/app-%d{yyyy-MM-dd}-%i.log.gz">
                      <PatternLayout>
                        <Pattern>${LOG_PATTERN}</Pattern>
                      </PatternLayout>
                    </RollingFile>
                  </Appenders>
                </Configuration>""";
        var all = LayoutImport.convertAll(xml);
        assertEquals(2, all.size(), all.toString());
        var console = all.get(0);
        assertEquals("{time} {level} [{thread}] {class}: {message}", console.pattern());
        assertEquals("yyyy-MM-dd'T'HH:mm:ss,SSS", console.timePattern());
        assertTrue(console.console());
        var file = LayoutImport.convert(xml); // a file appender's, without a sample
        assertEquals("{time} {level} {pid} --- [{thread}] {logger} : {message}", file.pattern());
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", file.timePattern());
        assertEquals("RollingFile \u201cRollingFile\u201d", file.where());
        String fileLine = "2026-10-21 10:02:13.250  INFO 4242 --- [           main] c.a.DemoApplication                      : Started";
        String consoleLine = "2026-10-21T10:02:13,250 INFO  [main] c.a.DemoApplication: Started";
        LogFormat f = new CustomFormat("f", file.pattern(), file.timePattern(), "").compile();
        assertEquals("4242", f.value(fileLine, "pid"));
        assertEquals("Started", f.value(fileLine, "message"));
        // with a sample, the layout its lines fit
        assertEquals(console.pattern(), LayoutImport.convert(xml, List.of(consoleLine, consoleLine)).pattern());
        assertEquals(file.pattern(), LayoutImport.convert(xml, List.of(fileLine)).pattern());
        LogFormat c = new CustomFormat("c", console.pattern(), console.timePattern(), "").compile();
        assertTrue(TimeFormat.inColumn(c, List.of(consoleLine), 2026).parse(consoleLine) != TimeFormat.NONE);
    }

    @Test
    void importsOtherConfigShapes() {
        // logback with <property>, a multi-line pattern and a time zone
        var lb = LayoutImport.convert("""
                <configuration>
                  <property name="PATTERN" value="%d{HH:mm:ss.SSS, UTC} [%thread] %-5level %logger{36} - %msg%n"/>
                  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder><pattern>${PATTERN}</pattern></encoder>
                  </appender>
                  <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
                    <file>app.log</file>
                    <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
                      <fileNamePattern>app.%d{yyyy-MM-dd}.log</fileNamePattern>
                    </rollingPolicy>
                    <encoder>
                      <pattern>
                        %d{yyyy-MM-dd HH:mm:ss.SSS} | %-5level | %X{requestId:-none} |
                        %logger{36} | %msg%n
                      </pattern>
                    </encoder>
                  </appender>
                </configuration>""");
        assertEquals("{time} | {level} | {requestId} | {logger} | {message}", lb.pattern());
        assertEquals("RollingFileAppender \u201cFILE\u201d", lb.where());

        // log4j2.properties with a property and a continued line
        var props = LayoutImport.convertAll("""
                property.pattern = %d{DEFAULT} [%t] %-5level \\
                    %logger{36} - %msg%n
                appender.console.type = Console
                appender.console.name = STDOUT
                appender.console.layout.type = PatternLayout
                appender.console.layout.pattern = ${pattern}
                """);
        assertEquals(1, props.size());
        assertEquals("{time} [{thread}] {level} {logger} - {message}", props.get(0).pattern());
        assertTrue(props.get(0).console());

        // log4j2.yaml
        var yaml = LayoutImport.convert("""
                Configuration:
                  Properties:
                    Property:
                      - name: log-pattern
                        value: "%d{HH:mm:ss.SSS} %-5p %c{1} - %m%n"
                  Appenders:
                    Console:
                      name: Console
                      PatternLayout:
                        Pattern: "${log-pattern}"
                """);
        assertEquals("{time} {level} {logger} - {message}", yaml.pattern());

        // log4j2.json
        var json = LayoutImport.convert("""
                { "configuration": { "appenders": { "Console": { "name": "STDOUT",
                    "PatternLayout": { "pattern": "%d{HH:mm:ss} %p %c{1} - %m%n" } } } } }""");
        assertEquals("{time} {level} {logger} - {message}", json.pattern());

        // Spring Boot application.properties that only changes the date
        var spring = LayoutImport.convert("logging.pattern.dateformat=yyyy-MM-dd HH:mm:ss.SSS\nlogging.level.root=INFO");
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", spring.timePattern());
        assertTrue(spring.pattern().contains("{pid} --- [{thread}] {logger} : {message}"), spring.pattern());

        // Log4j 2 time zones in a second {…}, %notEmpty left out, %equals
        var tz = LayoutImport.convert("%d{yyyy-MM-dd HH:mm:ss.SSS}{GMT+0} %p %notEmpty{[%marker] }%c - %equals{%X{tid}}{}{-} %m%n");
        assertEquals("{time} {level} {logger} - {tid} {message}", tz.pattern());
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", tz.timePattern());

        // a property that is not in the text
        assertNull(LayoutImport.convert("<PatternLayout pattern=\"${LOG_PATTERN}\"/>"));
        assertTrue(LayoutImport.problem("<PatternLayout pattern=\"${LOG_PATTERN}\"/>").contains("LOG_PATTERN"));
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

    @Test
    void importsLog4j1XmlJulAndPythonConfigs() {
        var log4j1 = LayoutImport.convertAll("""
                <log4j:configuration xmlns:log4j="http://jakarta.apache.org/log4j/">
                  <appender name="console" class="org.apache.log4j.ConsoleAppender">
                    <layout class="org.apache.log4j.PatternLayout">
                      <param name="ConversionPattern" value="%d{HH:mm:ss} %-5p %c{1} - %m%n"/>
                    </layout>
                  </appender>
                  <appender name="file" class="org.apache.log4j.DailyRollingFileAppender">
                    <param name="DatePattern" value="'.'yyyy-MM-dd"/>
                    <layout class="org.apache.log4j.PatternLayout">
                      <param name="ConversionPattern" value="%d{ISO8601} [%t] %-5p %c - %m%n"/>
                    </layout>
                  </appender>
                  <appender name="audit" class="org.apache.log4j.RollingFileAppender">
                    <layout class="org.apache.log4j.EnhancedPatternLayout">
                      <param value="%d{yyyy-MM-dd HH:mm:ss,SSS} %X{user} %m%n" name="ConversionPattern"/>
                    </layout>
                  </appender>
                </log4j:configuration>
                """);
        assertEquals(3, log4j1.size(), log4j1.toString());
        assertEquals("{time} {level} {logger} - {message}", log4j1.get(0).pattern());
        assertTrue(log4j1.get(0).console());
        assertEquals("{time} [{thread}] {level} {logger} - {message}", log4j1.get(1).pattern());
        assertEquals("yyyy-MM-dd HH:mm:ss,SSS", log4j1.get(1).timePattern());
        assertEquals("DailyRollingFileAppender \u201cfile\u201d", log4j1.get(1).where());
        assertEquals("{time} {user} {message}", log4j1.get(2).pattern());

        var jul = LayoutImport.convertAll("""
                handlers=java.util.logging.FileHandler
                java.util.logging.FileHandler.pattern=%h/java%u.log
                java.util.logging.SimpleFormatter.format=%1$tY-%1$tm-%1$td %1$tH:%1$tM:%1$tS.%1$tL %4$-7s [%3$s] %5$s%6$s%n
                """);
        assertEquals(1, jul.size(), jul.toString());
        assertEquals("{time} {level} [{logger}] {message}", jul.get(0).pattern());
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", jul.get(0).timePattern());
        CustomFormat f = new CustomFormat("jul", jul.get(0).pattern(), jul.get(0).timePattern(), "");
        assertTrue(f.compile().matches("2026-10-21 10:02:13.456 INFO    [com.acme.App] started"));

        var conf = LayoutImport.convertAll("""
                [formatters]
                keys=simple,detailed

                [formatter_simple]
                format=%(asctime)s - %(name)s - %(levelname)s - %(message)s
                datefmt=%d/%m/%Y %I:%M:%S %p

                [formatter_detailed]
                format=%(asctime)s.%(msecs)03d %(levelname)-8s [%(threadName)s] %(message)s
                datefmt=%Y-%m-%d %H:%M:%S
                """);
        assertEquals(2, conf.size(), conf.toString());
        assertEquals("{time} - {logger} - {level} - {message}", conf.get(0).pattern());
        assertEquals("dd/MM/yyyy hh:mm:ss a", conf.get(0).timePattern());
        assertEquals("formatter \u201csimple\u201d", conf.get(0).where());
        assertEquals("{time} {level} [{thread}] {message}", conf.get(1).pattern());
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", conf.get(1).timePattern());

        var code = LayoutImport.convertAll("""
                logging.basicConfig(format='%(asctime)s %(levelname)s %(message)s', datefmt='%H:%M:%S')
                h.setFormatter(logging.Formatter(fmt="%(asctime)s|%(process)d|%(message)s"))
                """);
        assertEquals(2, code.size(), code.toString());
        assertEquals("HH:mm:ss", code.get(0).timePattern());
        assertEquals("{time}|{pid}|{message}", code.get(1).pattern());
        assertEquals("yyyy-MM-dd HH:mm:ss,SSS", code.get(1).timePattern());
    }
}
