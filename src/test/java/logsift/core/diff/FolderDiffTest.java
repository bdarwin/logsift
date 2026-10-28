package logsift.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Folders (and zips) compared: same, different, only on one side, folders by what is in them. */
class FolderDiffTest {
    @TempDir
    Path tmp;

    private static void write(Path p, String text) throws Exception {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    private static Map<String, FolderDiff.Status> statuses(FolderDiff.Result r) {
        return FolderDiff.all(r.root()).stream().collect(Collectors.toMap(FolderDiff.Node::path,
                FolderDiff.Node::status, (a, b) -> a));
    }

    @Test
    void foldersCompared() throws Exception {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        write(a.resolve("same.txt"), "hello");
        write(b.resolve("same.txt"), "hello");
        write(a.resolve("conf/app.conf"), "port=1");
        write(b.resolve("conf/app.conf"), "port=2"); // same size, different bytes
        write(a.resolve("conf/old.conf"), "x");
        write(b.resolve("conf/new.conf"), "y");
        write(a.resolve("logs/a.log"), "1");
        write(b.resolve("logs/a.log"), "1");
        write(b.resolve("extra/deep/z.txt"), "z");
        write(a.resolve(".git/HEAD"), "ref"); // tool folders are left out
        write(a.resolve(".hidden"), "h");
        FolderDiff.Result r = FolderDiff.compare(a, b, FolderDiff.Options.DEFAULT, () -> false, s -> { });
        Map<String, FolderDiff.Status> st = statuses(r);
        assertEquals(FolderDiff.Status.SAME, st.get("same.txt"));
        assertEquals(FolderDiff.Status.DIFFERENT, st.get("conf/app.conf"));
        assertEquals(FolderDiff.Status.LEFT_ONLY, st.get("conf/old.conf"));
        assertEquals(FolderDiff.Status.RIGHT_ONLY, st.get("conf/new.conf"));
        assertEquals(FolderDiff.Status.DIFFERENT, st.get("conf"), "a folder with differences");
        assertEquals(FolderDiff.Status.SAME, st.get("logs"));
        assertEquals(FolderDiff.Status.RIGHT_ONLY, st.get("extra"));
        assertEquals(FolderDiff.Status.RIGHT_ONLY, st.get("extra/deep/z.txt"));
        assertTrue(!st.containsKey(".git") && !st.containsKey(".hidden"), st.keySet().toString());
        assertEquals(6, r.files());
        assertEquals(2, r.same());
        assertEquals(1, r.different());
        assertEquals(1, r.leftOnly());
        assertEquals(2, r.rightOnly());
        assertEquals(4, r.root().differences());
        // folders first, then files, by name
        assertEquals(List.of("conf", "extra", "logs", "same.txt"),
                r.root().kids().stream().map(FolderDiff.Node::name).toList());
    }

    @Test
    void sizesAndTimesOnly() throws Exception {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        write(a.resolve("f.txt"), "port=1");
        write(b.resolve("f.txt"), "port=2");
        Files.setLastModifiedTime(a.resolve("f.txt"), Files.getLastModifiedTime(b.resolve("f.txt")));
        FolderDiff.Result quick = FolderDiff.compare(a, b, new FolderDiff.Options(false, false, List.of()), () -> false,
                s -> { });
        assertEquals(FolderDiff.Status.SAME, statuses(quick).get("f.txt"), "same size and time: taken as the same");
        FolderDiff.Result full = FolderDiff.compare(a, b, FolderDiff.Options.DEFAULT, () -> false, s -> { });
        assertEquals(FolderDiff.Status.DIFFERENT, statuses(full).get("f.txt"));
    }

    @Test
    void aZipAgainstAFolder() throws Exception {
        Path a = tmp.resolve("a");
        write(a.resolve("logs/x.log"), "one");
        write(a.resolve("readme.txt"), "hi");
        Path zip = tmp.resolve("b.zip");
        java.io.ByteArrayOutputStream inner = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(inner)) {
            z.putNextEntry(new ZipEntry("x.log"));
            z.write("one".getBytes());
            z.closeEntry();
        }
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("logs.zip")); // a zip inside: a folder
            z.write(inner.toByteArray());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write("ho".getBytes());
            z.closeEntry();
        }
        FolderDiff.Result r = FolderDiff.compare(a, zip, FolderDiff.Options.DEFAULT, () -> false, s -> { });
        Map<String, FolderDiff.Status> st = statuses(r);
        assertEquals(FolderDiff.Status.DIFFERENT, st.get("readme.txt"));
        assertEquals(FolderDiff.Status.LEFT_ONLY, st.get("logs/x.log"), "logs/ on disk, logs.zip/ in the zip");
        assertEquals(FolderDiff.Status.RIGHT_ONLY, st.get("logs.zip/x.log"));
    }

    @Test
    void pathOrder() {
        assertTrue(FolderDiff.comparePaths("a/b", "a/c") < 0);
        assertTrue(FolderDiff.comparePaths("B", "a") > 0);
        assertTrue(FolderDiff.comparePaths("a", "a/b") < 0);
    }
}
