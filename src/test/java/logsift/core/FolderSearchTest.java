package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FolderSearchTest {
    @TempDir
    Path dir;

    /** File label ("a.log", "e.zip!x.log") to its content, for the files that should be searched. */
    private final Map<String, String> searchable = new LinkedHashMap<>();

    @Test
    void findsTheSameLinesAsNaiveSearch() throws Exception {
        Random rnd = new Random(12);
        Files.createDirectories(dir.resolve("sub/deeper"));
        Files.createDirectories(dir.resolve(".git"));
        plain("a.log", TestFiles.randomContent(rnd, 800));
        plain("sub/b.log", TestFiles.randomContent(rnd, 500).replace("\n", "\r\n"));
        plain("sub/deeper/c.log", TestFiles.randomContent(rnd, 300));
        plain("a.log.1", TestFiles.randomContent(rnd, 200));                  // rotated
        plain("a.log.2026-10-20", TestFiles.randomContent(rnd, 100));         // rotated by date
        Files.writeString(dir.resolve("notes.txt"), "error but not a log\n");   // wrong name
        Files.writeString(dir.resolve("a.log.bak"), "error in a backup\n");      // not rotation
        Files.writeString(dir.resolve(".git/x.log"), "error hidden folder\n"); // hidden folder
        Files.write(dir.resolve("bin.log"), new byte[] {'e', 'r', 'r', 'o', 'r', 0, 1, 2}); // binary
        String gz = TestFiles.randomContent(rnd, 400);
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(dir.resolve("d.log.3.gz")))) {
            o.write(gz.getBytes(StandardCharsets.UTF_8));
        }
        searchable.put("d.log.3.gz", gz);
        String x = TestFiles.randomContent(rnd, 300);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(dir.resolve("e.zip")))) {
            z.putNextEntry(new ZipEntry("logs/x.log"));
            z.write(x.getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("readme.md"));
            z.write("error in readme\n".getBytes(StandardCharsets.UTF_8));
        }
        searchable.put("e.zip!logs/x.log", x);
        // a zip inside a zip inside a zip, a .gz and a rotated log inside a zip
        String y = TestFiles.randomContent(rnd, 250);
        String w = TestFiles.randomContent(rnd, 150);
        String v = TestFiles.randomContent(rnd, 120);
        byte[] innermost = zip(Map.of("deep/y.log", y.getBytes(StandardCharsets.UTF_8)));
        byte[] inner = zip(Map.of("more.zip", innermost, "w.log.4.gz", gzip(w),
                "notes.txt", "error not a log\n".getBytes(StandardCharsets.UTF_8)));
        Files.write(dir.resolve("f.zip"), zip(Map.of("archive/inner.zip", inner,
                "v.log.1", v.getBytes(StandardCharsets.UTF_8))));
        searchable.put("f.zip!archive/inner.zip!/more.zip!/deep/y.log", y);
        searchable.put("f.zip!archive/inner.zip!/w.log.4.gz", w);
        searchable.put("f.zip!v.log.1", v);

        for (SearchQuery q : List.of(
                new SearchQuery("error", false, false, false),
                new SearchQuery("user=\\d+ ", false, true, false),
                new SearchQuery("ÄÖÜ", false, false, false),
                new SearchQuery("x{4000}", false, true, false))) {
            for (int block : new int[] {64, 1000, 1 << 20}) {
                FolderSearch s = new FolderSearch(
                        new FolderSearch.Options(dir, "*.log", true, true, q), block).start();
                assertTrue(s.awaitDone(20_000));
                Map<String, List<Long>> expected = new TreeMap<>();
                searchable.forEach((label, content) -> {
                    List<Long> lines = naive(content, q.toPattern());
                    if (!lines.isEmpty()) {
                        expected.put(label, lines);
                    }
                });
                Map<String, List<Long>> actual = new TreeMap<>();
                for (FolderSearch.FileResult r : s.results()) {
                    String label = dir.relativize(r.file()).toString().replace('\\', '/')
                            + (r.entry() != null ? "!" + r.entry() : "");
                    List<Long> lines = new ArrayList<>();
                    for (FolderSearch.Hit h : r.hits()) {
                        lines.add(h.line());
                        String want = lineOf(searchable.get(label), h.line());
                        assertEquals(want, h.text(), label + " text of line " + h.line());
                    }
                    assertEquals(r.hitCount(), lines.size());
                    actual.put(label, lines);
                }
                assertEquals(expected, actual, q + " block=" + block);
                assertEquals(1, s.filesSkipped(), "binary file skipped");
            }
        }
    }

    @Test
    void opensAnEntryOfAZipInsideAZip() throws Exception {
        byte[] inner = zip(Map.of("a.log", "first\n".getBytes(StandardCharsets.UTF_8),
                "b.log.gz", gzip("second\n")));
        Path f = dir.resolve("outer.zip");
        Files.write(f, zip(Map.of("x/inner.zip", inner, "c.log", "third\n".getBytes(StandardCharsets.UTF_8))));
        assertEquals("first\n", read(f, "x/inner.zip!/a.log"));
        assertEquals("second\n", read(f, "x/inner.zip!/b.log.gz"));
        assertEquals("third\n", read(f, "c.log"));
        assertThrows(IOException.class, () -> read(f, "x/inner.zip!/none.log"));
    }

    @Test
    void stopsFollowingZipsAtSomeDepth() throws Exception {
        byte[] z = zip(Map.of("a.log", "error deep\n".getBytes(StandardCharsets.UTF_8)));
        for (int i = 1; i < FolderSearch.MAX_DEPTH; i++) {
            z = zip(Map.of("n" + i + ".zip", z));
        }
        Files.write(dir.resolve("ok.zip"), z);
        Files.write(dir.resolve("too.zip"), zip(Map.of("n.zip", z)));
        FolderSearch s = new FolderSearch(new FolderSearch.Options(dir, "*.log", false, true,
                SearchQuery.literal("error"))).start();
        assertTrue(s.awaitDone(10_000));
        assertEquals(1, s.results().size(), "only the one at the deepest level followed");
        assertEquals("ok.zip", s.results().get(0).file().getFileName().toString());
    }

    private static String read(Path zip, String entry) throws IOException {
        try (var in = FolderSearch.openEntry(zip, entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(entries).entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
            }
        }
        return b.toByteArray();
    }

    private static byte[] gzip(String content) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        try (OutputStream o = new GZIPOutputStream(b)) {
            o.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return b.toByteArray();
    }

    @Test
    void notRecursiveAndNoArchives() throws Exception {
        plain("top.log", "error 1\nok\n");
        plain("sub/deep.log", "error 2\n");
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(dir.resolve("z.log.gz")))) {
            o.write("error 3\n".getBytes(StandardCharsets.UTF_8));
        }
        FolderSearch s = new FolderSearch(new FolderSearch.Options(dir, "*.log *.gz", false, false,
                SearchQuery.literal("error"))).start();
        assertTrue(s.awaitDone(10_000));
        assertEquals(1, s.results().size(), "only top.log; the .gz is binary when not unpacked");
        assertEquals("top.log", s.results().get(0).file().getFileName().toString());
    }

    private void plain(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        searchable.put(name, content);
    }

    private static List<Long> naive(String content, Pattern p) {
        List<String> lines = TestFiles.naiveLines(content.getBytes(StandardCharsets.UTF_8));
        List<Long> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (p.matcher(lines.get(i)).find()) {
                out.add((long) i);
            }
        }
        return out;
    }

    private static String lineOf(String content, long line) {
        String s = TestFiles.naiveLines(content.getBytes(StandardCharsets.UTF_8)).get((int) line);
        return s.length() > FolderSearch.MAX_TEXT ? s.substring(0, FolderSearch.MAX_TEXT) : s;
    }
}
