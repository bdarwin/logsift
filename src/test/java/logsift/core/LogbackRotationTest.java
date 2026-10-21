package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.Duration;
import ch.qos.logback.core.util.FileSize;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A real logging library rolls the file over many times while Logsift follows and reads it.
 * The important check is on Windows: while Logsift holds the file open, logback must still be
 * able to rename the active file and delete the oldest one. If it can't, logback records a
 * warning in its status list, and this test fails.
 */
class LogbackRotationTest {
    @TempDir
    Path dir;

    @Test
    void logbackCanRotateWhileFileIsOpen() throws Exception {
        Path file = dir.resolve("app.log");
        LoggerContext ctx = new LoggerContext();
        ctx.setMDCAdapter(new LogbackMDCAdapter());
        RollingFileAppender<ILoggingEvent> appender = rollingAppender(ctx, file);
        Logger log = ctx.getLogger("test");
        log.setAdditive(false);
        log.addAppender(appender);
        log.info("first line");

        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicReference<Throwable> readerError = new AtomicReference<>();
        try (LogDocument doc = LogDocument.open(file, 4096)) {
            doc.startFollowing(20);
            int gen = doc.generation();

            // Reads the start and end of the file the whole time, like a user scrolling.
            Thread reader = new Thread(() -> {
                String[] buf = new String[50];
                try {
                    while (writing.get()) {
                        doc.readLines(0, buf.length, buf);
                        long n = doc.lineCount();
                        doc.readLines(Math.max(0, n - buf.length), buf.length, buf);
                    }
                } catch (Throwable t) {
                    readerError.set(t);
                }
            });
            reader.start();

            String pad = "x".repeat(80);
            for (int i = 0; i < 6000; i++) {
                log.info("line {} {}", i, pad);
                if (i % 50 == 0) {
                    Thread.sleep(5);
                }
            }
            writing.set(false);
            reader.join();
            appender.stop();

            List<Status> problems = ctx.getStatusManager().getCopyOfStatusList().stream()
                    .filter(s -> s.getLevel() >= Status.WARN)
                    .toList();
            assertEquals(List.of(), problems, "logback could not rotate");
            assertTrue(Files.exists(dir.resolve("app.3.log")), "not enough rotations happened");
            assertEquals(null, readerError.get(), "reading during rotation failed");

            doc.requestRefresh();
            assertTrue(doc.awaitIdle(10_000));
            assertTrue(doc.generation() > gen, "rotation was not noticed");
            LogDocumentTest.assertMatches(doc, Files.readAllBytes(file));
        } finally {
            ctx.stop();
        }
    }

    /** Rolls app.log to app.1.log .. app.3.log every 32 KB; the oldest is deleted. */
    private static RollingFileAppender<ILoggingEvent> rollingAppender(LoggerContext ctx, Path file) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(ctx);
        encoder.setPattern("%msg%n");
        encoder.start();

        RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<>();
        appender.setContext(ctx);
        appender.setName("rolling");
        appender.setFile(file.toString());
        appender.setEncoder(encoder);

        FixedWindowRollingPolicy rolling = new FixedWindowRollingPolicy();
        rolling.setContext(ctx);
        rolling.setParent(appender);
        // forward slashes: logback treats backslashes in patterns as escapes
        rolling.setFileNamePattern(
                file.resolveSibling("app.%i.log").toString().replace('\\', '/'));
        rolling.setMinIndex(1);
        rolling.setMaxIndex(3);
        rolling.start();

        SizeBasedTriggeringPolicy<ILoggingEvent> trigger = new SizeBasedTriggeringPolicy<>();
        trigger.setContext(ctx);
        trigger.setMaxFileSize(FileSize.valueOf("32KB"));
        trigger.setCheckIncrement(Duration.buildByMilliseconds(5)); // default is 60 s
        trigger.start();

        appender.setRollingPolicy(rolling);
        appender.setTriggeringPolicy(trigger);
        appender.start();
        return appender;
    }
}
