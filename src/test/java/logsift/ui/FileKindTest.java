package logsift.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Analyze from the right-click menu: what a file is, told by its start where its name can't. */
class FileKindTest {
    @TempDir
    Path dir;

    private Path copy(String resource, String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            byte[] b = resource.endsWith(".gz") ? new GZIPInputStream(in).readAllBytes() : in.readAllBytes();
            return Files.write(dir.resolve(name), b);
        }
    }

    private Path bytes(String name, String start) throws Exception {
        byte[] head = start.getBytes(StandardCharsets.ISO_8859_1);
        byte[] b = java.util.Arrays.copyOf(head, head.length + 64);
        return Files.write(dir.resolve(name), b);
    }

    @Test
    void logsAreToldApartByWhatTheySay() throws Exception {
        // all called .log: only their text says what they are
        assertEquals(FileKind.GC_LOG, FileKind.of(copy("/gc/j17-g1-time.log.gz", "service-gc.log")));
        assertEquals(FileKind.GC_LOG, FileKind.of(copy("/gc/j8-cms.log.gz", "gc.log")));
        assertEquals(FileKind.THREAD_DUMP, FileKind.of(copy("/threaddumps/jstack-21.txt", "stuck.log")));
        assertEquals(FileKind.THREAD_DUMP, FileKind.of(copy("/threaddumps/jstack-8.txt", "stuck8.log")));
        assertEquals(FileKind.THREAD_DUMP, FileKind.of(copy("/threaddumps/py-spy-celery.txt", "worker.log")),
                "Python's thread dumps go to Threads too");
        assertEquals(FileKind.THREAD_DUMP, FileKind.of(copy("/threaddumps/faulthandler.txt", "stderr.log")));
        assertEquals(FileKind.CRASH, FileKind.of(copy("/logsift/crash/hs_err_linux_c2.log", "crashed.log")));
        Path plain = Files.writeString(dir.resolve("app.log"),
                "2026-11-14 10:00:00.123 INFO  [main] c.e.App - started in 2.3 s\n".repeat(50));
        assertEquals(FileKind.LOG, FileKind.of(plain));
    }

    @Test
    void dumpsAndRecordingsByNameOrTheirFirstBytes() throws Exception {
        assertEquals(FileKind.HEAP_DUMP, FileKind.of(bytes("app.hprof", "anything")));
        assertEquals(FileKind.HEAP_DUMP, FileKind.of(bytes("heap.bin", "JAVA PROFILE 1.0.2\0")));
        assertEquals(FileKind.PROFILE, FileKind.of(bytes("app.jfr", "anything")));
        assertEquals(FileKind.PROFILE, FileKind.of(bytes("recording.bin", "FLR\0")));
        assertEquals(FileKind.CRASH, FileKind.of(bytes("w3wp.dmp", "anything"))); // the crash view says what it is
        assertEquals(FileKind.CRASH, FileKind.of(bytes("dotnet-crash.bin", "MDMP\u0093§")));
        assertEquals(FileKind.FOLDER, FileKind.of(Files.createDirectories(dir.resolve("logs"))));
        assertEquals(FileKind.LOG, FileKind.of(bytes("logs.zip", "PK\u0003\u0004")), "unpacked and opened as a log");
        assertEquals(FileKind.LOG, FileKind.of(dir.resolve("gone.log")), "opening it says it's not there");
    }
}
