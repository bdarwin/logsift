package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBrowseTest {

    private static List<String> names(List<FileBrowse.Item> items) {
        return items.stream().map(i -> i.name() + ":" + i.kind()).toList();
    }

    @Test
    void aFolderShowsFoldersFirstThenFilesByName(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("logs"));
        Files.createDirectory(dir.resolve("Archive"));
        Files.createDirectory(dir.resolve(".git"));
        Files.writeString(dir.resolve("b.csv"), "x");
        Files.writeString(dir.resolve("A.log"), "xyz");
        Files.writeString(dir.resolve("bundle.zip"), "not really");
        Files.writeString(dir.resolve(".hidden"), "x");
        List<FileBrowse.Item> got = FileBrowse.folder(dir, false, () -> false);
        assertEquals(List.of("Archive:FOLDER", "logs:FOLDER", "A.log:FILE", "b.csv:FILE", "bundle.zip:ZIP"), names(got));
        assertEquals(3, got.get(2).size());
        assertEquals(dir.resolve("A.log"), got.get(2).path());
        assertTrue(names(FileBrowse.folder(dir, true, () -> false)).contains(".git:FOLDER"), "hidden ones when asked");
    }

    private static byte[] zip(Object... nameThenContent) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (int i = 0; i < nameThenContent.length; i += 2) {
                z.putNextEntry(new ZipEntry((String) nameThenContent[i]));
                Object c = nameThenContent[i + 1];
                if (c != null) {
                    z.write(c instanceof byte[] b ? b : ((String) c).getBytes(StandardCharsets.UTF_8));
                }
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test
    void aZipOpensLikeFoldersAndSoDoZipsInsideIt(@TempDir Path dir) throws IOException {
        byte[] inner = zip("data/orders.csv", "id,v\n1,a\n", "readme.txt", "hi");
        byte[] middle = zip("inner.zip", inner, "middle.log", "m");
        Path bundle = dir.resolve("bundle.zip");
        try (OutputStream o = Files.newOutputStream(bundle)) {
            o.write(zip("old/", null, "old/2026-09.zip", middle, "app.log", "line\n",
                    "looks.zip/real-folder.txt", "x"));
        }
        FileBrowse.ZipTree t = FileBrowse.ZipTree.read(bundle, () -> false);
        List<FileBrowse.Item> top = t.children("");
        assertEquals(List.of("looks.zip:FOLDER", "old:FOLDER", "app.log:FILE"), names(top),
                "a folder named like a zip is a folder");
        FileBrowse.Item old = top.get(1);
        assertEquals(bundle.resolve("old"), old.path());
        assertTrue(old.inZip());
        List<FileBrowse.Item> inOld = t.children(old);
        assertEquals(List.of("2026-09.zip:ZIP"), names(inOld));
        List<FileBrowse.Item> inMiddle = t.children(inOld.get(0));
        assertEquals(List.of("inner.zip:ZIP", "middle.log:FILE"), names(inMiddle));
        List<FileBrowse.Item> inInner = t.children(inMiddle.get(0));
        assertEquals(List.of("data:FOLDER", "readme.txt:FILE"), names(inInner));
        FileBrowse.Item csv = t.children(inInner.get(0)).get(0);
        assertEquals("orders.csv", csv.name());
        assertEquals(9, csv.size());
        // named like a file on disk, so it opens and compares like one
        Path p = bundle.resolve("old/2026-09.zip/inner.zip/data/orders.csv");
        assertEquals(p, csv.path());
        Archives.Located at = Archives.locate(p);
        assertEquals(bundle, at.zip());
        assertEquals("old/2026-09.zip!/inner.zip!/data/orders.csv", at.entry());
        try (var in = Archives.open(at.zip(), at.entry())) {
            assertEquals("id,v\n1,a\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aLevelThatIsNotThereIsEmpty() {
        FileBrowse.ZipTree t = FileBrowse.ZipTree.of(Path.of("/x/a.zip"), List.of(new Archives.Entry("a/b.log", 3)));
        assertEquals(List.of("b.log:FILE"), names(t.children("a")));
        assertEquals(List.of(), t.children("nope"));
    }
}
