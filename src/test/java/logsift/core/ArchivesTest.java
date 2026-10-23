package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArchivesTest {
    @TempDir
    Path dir;

    @Test
    void listsZipsInsideZips() throws Exception {
        byte[] inner = zip(Map.of("a.log", bytes("first\n"), "b.csv.gz", gzip("x,y\n1,2\n")));
        Path f = dir.resolve("outer.zip");
        Files.write(f, zip(Map.of("logs/", new byte[0], "logs/inner.zip", inner, "c.log", bytes("third\n"))));
        List<Archives.Entry> list = Archives.list(f, () -> false);
        assertEquals(List.of("c.log", "logs/inner.zip", "logs/inner.zip!/a.log", "logs/inner.zip!/b.csv.gz"),
                list.stream().map(Archives.Entry::name).toList(), "folders left out");
        assertEquals(6, list.get(2).size(), "sizes, also of nested entries");
        assertEquals("logs/inner.zip › a.log", list.get(2).label());
        assertEquals("a.log", list.get(2).fileName());
        assertEquals("b.csv", Archives.gunzippedName(list.get(3).fileName()));
    }

    @Test
    void extractsEntriesAndGzipFiles() throws Exception {
        byte[] inner = zip(Map.of("a.log", bytes("first\n"), "b.log.gz", gzip("second\n")));
        Path f = dir.resolve("outer.zip");
        Files.write(f, zip(Map.of("inner.zip", inner)));
        Path out = dir.resolve("out.log");
        double[] last = {0};
        assertTrue(Archives.extract(f, "inner.zip!/b.log.gz", -1, out, p -> last[0] = p, () -> false));
        assertEquals("second\n", Files.readString(out));
        Path gz = dir.resolve("big.log.gz");
        String big = "line of a log that is long enough\n".repeat(50_000);
        Files.write(gz, gzip(big));
        Path out2 = dir.resolve("big.log");
        assertTrue(Archives.extract(gz, null, -1, out2, p -> last[0] = p, () -> false));
        assertEquals(big, Files.readString(out2));
        assertTrue(last[0] > 0.9, "progress by the .gz read: " + last[0]);
        // cancelled: no half-written copy left
        Path out3 = dir.resolve("cancelled.log");
        assertFalse(Archives.extract(gz, null, -1, out3, null, () -> true));
        assertFalse(Files.exists(out3));
    }

    @Test
    void listsADamagedNestedZipAsAFile() throws Exception {
        Path f = dir.resolve("outer.zip");
        Files.write(f, zip(Map.of("bad.zip", bytes("not a zip at all"), "ok.log", bytes("ok\n"))));
        assertEquals(List.of("bad.zip", "ok.log"), Archives.list(f, () -> false).stream().map(Archives.Entry::name).toList());
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(entries).entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
            }
        }
        return b.toByteArray();
    }

    static byte[] gzip(String content) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (OutputStream o = new GZIPOutputStream(b)) {
            o.write(bytes(content));
        }
        return b.toByteArray();
    }
}
