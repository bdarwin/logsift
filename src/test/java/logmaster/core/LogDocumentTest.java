package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LogDocumentTest {
    @TempDir
    Path dir;

    /** Tiny chunk sizes put chunk boundaries everywhere: inside lines, at \r, at \n. */
    @ParameterizedTest
    @ValueSource(ints = {2, 3, 7, 64, 1000, 4096, LineIndex.DEFAULT_CHUNK_SIZE})
    void indexMatchesNaiveSplit(int chunkSize) throws IOException {
        Random rnd = new Random(chunkSize);
        for (int iter = 0; iter < 8; iter++) {
            byte[] content = TestFiles.randomContent(rnd, rnd.nextInt(chunkSize < 64 ? 60 : 400))
                    .getBytes(StandardCharsets.UTF_8);
            Path f = dir.resolve("f" + iter + ".log");
            Files.write(f, content);
            try (LogDocument doc = LogDocument.open(f, chunkSize)) {
                assertTrue(doc.awaitIdle(10_000));
                assertMatches(doc, content);
            }
        }
    }

    @Test
    void emptyFile() throws IOException {
        Path f = dir.resolve("empty.log");
        Files.write(f, new byte[0]);
        try (LogDocument doc = LogDocument.open(f, 16)) {
            assertTrue(doc.awaitIdle(5000));
            assertEquals(0, doc.lineCount());
        }
    }

    @Test
    void followsAppendsInRandomPieces() throws IOException {
        Random rnd = new Random(7);
        byte[] all = TestFiles.randomContent(rnd, 3000).getBytes(StandardCharsets.UTF_8);
        Path f = dir.resolve("tail.log");
        Files.write(f, new byte[0]);
        try (LogDocument doc = LogDocument.open(f, 512)) {
            assertTrue(doc.awaitIdle(5000));
            int written = 0;
            while (written < all.length) {
                // pieces end anywhere: mid-line, between \r and \n, ...
                int n = Math.min(all.length - written, 1 + rnd.nextInt(700));
                append(f, all, written, n);
                written += n;
                doc.requestRefresh();
                assertTrue(doc.awaitIdle(5000));
                if (rnd.nextInt(10) == 0) {
                    assertMatches(doc, java.util.Arrays.copyOf(all, written));
                }
            }
            assertMatches(doc, all);
        }
    }

    @Test
    void truncationResetsAndRotationReopens() throws IOException {
        Path f = dir.resolve("rot.log");
        Files.writeString(f, "first line\nsecond line\nthird line\n");
        try (LogDocument doc = LogDocument.open(f, 8)) {
            assertTrue(doc.awaitIdle(5000));
            assertEquals(3, doc.lineCount());
            int gen = doc.generation();

            // truncate in place
            Files.writeString(f, "new\n");
            doc.requestRefresh();
            assertTrue(doc.awaitIdle(5000));
            assertEquals(1, doc.lineCount());
            assertEquals("new", doc.line(0));
            assertTrue(doc.generation() > gen);

            // rewritten with same-size-or-larger content (fingerprint differs)
            gen = doc.generation();
            Files.writeString(f, "NEW\nmore lines here\n");
            doc.requestRefresh();
            assertTrue(doc.awaitIdle(5000));
            assertTrue(doc.generation() > gen);
            assertEquals(List.of("NEW", "more lines here"),
                    List.of(doc.line(0), doc.line(1)));

            // rotation: move away and create a new file at the same path
            gen = doc.generation();
            Files.move(f, dir.resolve("rot.log.1"));
            Files.writeString(f, "after rotation\n");
            doc.requestRefresh();
            assertTrue(doc.awaitIdle(5000));
            assertTrue(doc.generation() > gen);
            assertEquals(1, doc.lineCount());
            assertEquals("after rotation", doc.line(0));
        }
    }

    @Test
    void veryLongLineIsCutForDisplay() throws IOException {
        Path f = dir.resolve("long.log");
        String longLine = "y".repeat(LogDocument.MAX_DISPLAY_BYTES * 3);
        Files.writeString(f, "a\n" + longLine + "\nb\n");
        try (LogDocument doc = LogDocument.open(f, 1000)) {
            assertTrue(doc.awaitIdle(5000));
            assertEquals(3, doc.lineCount());
            String[] out = new String[3];
            doc.readLines(0, 3, out);
            assertEquals("a", out[0]);
            assertEquals(LogDocument.MAX_DISPLAY_BYTES, out[1].length());
            assertEquals("b", out[2]);
        }
    }

    static void append(Path f, byte[] data, int off, int len) throws IOException {
        try (var out = Files.newOutputStream(f, StandardOpenOption.APPEND)) {
            out.write(data, off, len);
        }
    }

    static void assertMatches(LogDocument doc, byte[] content) {
        List<String> expected = TestFiles.naiveLines(content);
        List<Long> starts = TestFiles.naiveStarts(content);
        assertEquals(expected.size(), doc.lineCount(), "line count");
        LineIndex.Snapshot s = doc.snapshot();
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(starts.get(i), doc.lineStart(s, i), "start of line " + i);
        }
        // read in batches of varying size, like the UI does
        String[] buf = new String[37];
        for (int first = 0; first < expected.size(); first += 37) {
            doc.readLines(first, 37, buf);
            for (int k = 0; k < 37 && first + k < expected.size(); k++) {
                String exp = expected.get(first + k);
                if (exp.length() > LogDocument.MAX_DISPLAY_BYTES) {
                    exp = exp.substring(0, LogDocument.MAX_DISPLAY_BYTES);
                }
                assertEquals(exp, buf[k], "line " + (first + k));
            }
        }
    }
}
