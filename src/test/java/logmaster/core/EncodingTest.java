package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncodingTest {
    @TempDir
    Path dir;

    private static final String TEXT = "2026-10-21 10:00:00 INFO café crème ok\n"
            + "2026-10-21 10:00:01 ERROR Müller: Straße nicht gefunden\n"
            + "2026-10-21 10:00:02 WARN 東京 emoji 😀 done\n";

    private static Charset detect(byte[] b) {
        return Encoding.detect(b, b.length);
    }

    @Test
    void detectsEncodings() {
        byte[] utf8 = TEXT.getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, detect(utf8));
        byte[] bom = new byte[utf8.length + 3];
        bom[0] = (byte) 0xEF;
        bom[1] = (byte) 0xBB;
        bom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, bom, 3, utf8.length);
        assertEquals(StandardCharsets.UTF_8, detect(bom));
        assertEquals(StandardCharsets.UTF_16LE, detect(("﻿" + TEXT).getBytes(StandardCharsets.UTF_16LE)));
        assertEquals(StandardCharsets.UTF_16BE, detect(("﻿" + TEXT).getBytes(StandardCharsets.UTF_16BE)));
        assertEquals(StandardCharsets.UTF_16LE, detect(TEXT.repeat(3).getBytes(StandardCharsets.UTF_16LE)));
        assertEquals(Encoding.WINDOWS_1252, detect("café crème\n".repeat(10).getBytes(Encoding.WINDOWS_1252)));
    }

    @Test
    void windows1252LogsDisplayAndSearch() throws Exception {
        String text = "2026-10-21 INFO café ok\n2026-10-21 ERROR Müller fehlgeschlagen\n2026-10-21 INFO plain\n";
        Path f = dir.resolve("w.log");
        Files.write(f, text.getBytes(Encoding.WINDOWS_1252));
        try (LogDocument doc = LogDocument.open(f, LineIndex.DEFAULT_CHUNK_SIZE, Encoding.WINDOWS_1252)) {
            assertTrue(doc.awaitIdle(5000));
            assertEquals("2026-10-21 ERROR Müller fehlgeschlagen", doc.line(1));
            for (SearchQuery q : List.of(new SearchQuery("Müller", true, false, false),
                    new SearchQuery("mÜller", false, false, false),
                    new SearchQuery("M.ller fehl", true, true, false))) {
                try (SearchSession s = new SearchSession(doc, q).start()) {
                    assertTrue(s.awaitIdle(5000));
                    assertEquals(1, s.hits().size(), q.toString());
                    assertEquals(1, s.hits().get(0), q.toString());
                }
            }
        }
    }

    @Test
    void utf16MirrorFollowsTheOriginal() throws Exception {
        Path f = dir.resolve("u.log");
        Path copy = dir.resolve("u-utf8.log");
        Files.write(f, ("﻿" + TEXT).getBytes(StandardCharsets.UTF_16LE));
        try (Utf16Mirror m = Utf16Mirror.create(f, copy, StandardCharsets.UTF_16LE, null, () -> false)) {
            assertEquals(TEXT, Files.readString(copy, StandardCharsets.UTF_8));
            // append in odd pieces: half a char, and a surrogate pair split in two writes
            byte[] more = "2026-10-21 10:00:03 INFO more 😀 x\n".getBytes(StandardCharsets.UTF_16LE);
            int[] cuts = {7, 31, 32, more.length};
            int from = 0;
            for (int cut : cuts) {
                byte[] part = java.util.Arrays.copyOfRange(more, from, cut);
                Files.write(f, part, StandardOpenOption.APPEND);
                m.update();
                from = cut;
            }
            assertEquals(TEXT + "2026-10-21 10:00:03 INFO more 😀 x\n", Files.readString(copy, StandardCharsets.UTF_8));
            // truncated (log rotated in place): the copy is rewritten
            Files.write(f, "new start\n".getBytes(StandardCharsets.UTF_16LE));
            m.update();
            assertEquals("new start\n", Files.readString(copy, StandardCharsets.UTF_8));
        }
    }

    @Test
    void folderSearchHandlesEncodings() throws Exception {
        Files.write(dir.resolve("a.log"), ("﻿" + TEXT).getBytes(StandardCharsets.UTF_16LE));
        Files.write(dir.resolve("b.log"), "INFO ok\nERROR Müller weg\n".getBytes(Encoding.WINDOWS_1252));
        FolderSearch s = new FolderSearch(new FolderSearch.Options(dir, "*.log", false, false,
                new SearchQuery("Müller", true, false, false))).start();
        assertTrue(s.awaitDone(10_000));
        List<String> found = new ArrayList<>();
        for (FolderSearch.FileResult r : s.results()) {
            for (FolderSearch.Hit h : r.hits()) {
                found.add(r.file().getFileName() + ":" + h.line() + ":" + h.text());
            }
        }
        assertEquals(List.of("a.log:1:2026-10-21 10:00:01 ERROR Müller: Straße nicht gefunden",
                "b.log:1:ERROR Müller weg"), found);
    }
}
