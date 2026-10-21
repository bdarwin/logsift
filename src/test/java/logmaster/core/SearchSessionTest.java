package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchSessionTest {
    @TempDir
    Path dir;

    private static final List<SearchQuery> QUERIES = List.of(
            new SearchQuery("ERROR", true, false, false),
            new SearchQuery("error", false, false, false),
            new SearchQuery("order", false, false, true),
            new SearchQuery("Order", true, false, true),
            new SearchQuery("äöü", true, false, false),
            new SearchQuery("ÄÖÜ", false, false, false),
            new SearchQuery("日本", false, false, false),
            new SearchQuery("user=\\d+ ", false, true, false),
            new SearchQuery("^$", false, true, false),
            new SearchQuery("x{4000}", false, true, false),
            new SearchQuery("tail-without-newline", true, false, false),
            new SearchQuery("ERROR WARN", false, false, false),
            new SearchQuery("r", false, false, false),
            new SearchQuery("ERROR", false, false, true),
            new SearchQuery("müller", false, false, false),
            new SearchQuery("Order (user|info)", false, true, false),
            new SearchQuery("user=\\d+ (WARN|info)", true, true, false),
            new SearchQuery("x{10}", true, true, false),
            new SearchQuery("timeout x", false, true, true),
            new SearchQuery("äöü 日本", true, true, false),
            new SearchQuery("ÄÖÜ 日本", false, true, false));

    @Test
    void findAllMatchesNaive() throws IOException {
        Random rnd = new Random(3);
        for (int chunkSize : new int[] {5, 333, 4096, LineIndex.DEFAULT_CHUNK_SIZE}) {
            byte[] content = TestFiles.randomContent(rnd, 2000).getBytes(StandardCharsets.UTF_8);
            Path f = dir.resolve("s" + chunkSize + ".log");
            Files.write(f, content);
            List<String> lines = TestFiles.naiveLines(content);
            try (LogDocument doc = LogDocument.open(f, chunkSize)) {
                assertTrue(doc.awaitIdle(10_000));
                for (SearchQuery q : QUERIES) {
                    try (SearchSession s = new SearchSession(doc, q).start()) {
                        assertTrue(s.awaitIdle(10_000));
                        assertEquals(naive(lines, q), hitsOf(s), q + " chunk=" + chunkSize);
                    }
                }
            }
        }
    }

    @Test
    void liveSearchFollowsGrowingFile() throws IOException {
        Random rnd = new Random(11);
        byte[] all = TestFiles.randomContent(rnd, 4000).getBytes(StandardCharsets.UTF_8);
        Path f = dir.resolve("live.log");
        Files.write(f, new byte[0]);
        SearchQuery q = new SearchQuery("error", false, false, false);
        try (LogDocument doc = LogDocument.open(f, 700);
             SearchSession s = new SearchSession(doc, q).start()) {
            int written = 0;
            while (written < all.length) {
                int n = Math.min(all.length - written, 1 + rnd.nextInt(900));
                LogDocumentTest.append(f, all, written, n);
                written += n;
                doc.requestRefresh();
                assertTrue(s.awaitIdle(5000));
                if (rnd.nextInt(8) == 0 || written == all.length) {
                    byte[] part = java.util.Arrays.copyOf(all, written);
                    assertEquals(naive(TestFiles.naiveLines(part), q), hitsOf(s),
                            "after " + written + " bytes");
                }
            }
        }
    }

    @Test
    void restartsAfterTruncation() throws IOException {
        Path f = dir.resolve("trunc.log");
        Files.writeString(f, "ERROR a\nok\nERROR b\n");
        SearchQuery q = new SearchQuery("ERROR", true, false, false);
        try (LogDocument doc = LogDocument.open(f, 4);
             SearchSession s = new SearchSession(doc, q).start()) {
            assertTrue(s.awaitIdle(5000));
            assertEquals(List.of(0L, 2L), hitsOf(s));
            Files.writeString(f, "ok\nERROR c\n");
            doc.requestRefresh();
            assertTrue(s.awaitIdle(5000));
            assertEquals(List.of(1L), hitsOf(s));
        }
    }

    static List<Long> hitsOf(SearchSession s) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < s.hits().size(); i++) {
            out.add(s.hits().get(i));
        }
        return out;
    }

    static List<Long> naive(List<String> lines, SearchQuery q) {
        Pattern p = q.toPattern();
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (p.matcher(lines.get(i)).find()) {
                out.add((long) i);
            }
        }
        return out;
    }
}
