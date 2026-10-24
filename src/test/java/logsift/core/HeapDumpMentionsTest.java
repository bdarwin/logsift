package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeapDumpMentionsTest {
    @Test
    void findsThePathTheJvmPrinted() {
        assertEquals("java_pid4242.hprof", HeapDumpMentions.pathIn("Dumping heap to java_pid4242.hprof ..."));
        assertEquals("/var/dumps/java_pid4242.hprof",
                HeapDumpMentions.pathIn("2026-10-24 10:00:01 Dumping heap to /var/dumps/java_pid4242.hprof ..."));
        assertEquals("C:\\dumps\\my app\\java_pid1.hprof",
                HeapDumpMentions.pathIn("Dumping heap to C:\\dumps\\my app\\java_pid1.hprof ..."));
        assertEquals("/d/x.hprof", HeapDumpMentions.pathIn("Dumping heap to /d/x.hprof"));
        assertNull(HeapDumpMentions.pathIn("Heap dump file created [1234 bytes in 0.1 secs]"));
        assertNull(HeapDumpMentions.pathIn(null));
    }

    @Test
    void knowsLinesAboutMemory() {
        assertTrue(HeapDumpMentions.isAbout("java.lang.OutOfMemoryError: Java heap space"));
        assertTrue(HeapDumpMentions.isAbout("Dumping heap to x.hprof ..."));
        assertFalse(HeapDumpMentions.isAbout("GET /memory 200"));
    }

    @Test
    void looksNextToTheLog() {
        assertEquals(List.of("/var/dumps/java_pid1.hprof", "/var/log/app/java_pid1.hprof"),
                HeapDumpMentions.candidates("/var/dumps/java_pid1.hprof", "/var/log/app"));
        assertEquals(List.of("/var/log/app/java_pid1.hprof"),
                HeapDumpMentions.candidates("java_pid1.hprof", "/var/log/app"));
        assertEquals(List.of("/var/log/app/dumps/java_pid1.hprof", "/var/log/app/java_pid1.hprof"),
                HeapDumpMentions.candidates("dumps/java_pid1.hprof", "/var/log/app"));
        assertEquals(List.of("C:\\d\\java_pid1.hprof", "C:/logs/java_pid1.hprof"),
                HeapDumpMentions.candidates("C:\\d\\java_pid1.hprof", "C:/logs"));
    }
}
