package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A folder's files for its tab: relative paths, sizes, a cap. */
class FolderListingTest {
    @TempDir
    Path dir;

    @Test
    void filesWithTheirPathsAndSizes() throws IOException {
        Files.writeString(dir.resolve("app.log"), "12345");
        Files.createDirectories(dir.resolve("old/2026"));
        Files.writeString(dir.resolve("old/2026/app.log.1"), "abc");
        Files.write(dir.resolve("old/logs.zip"), new byte[] {1, 2});
        Files.createDirectories(dir.resolve("empty"));
        FolderListing.Result r = FolderListing.list(dir, 100, () -> false);
        List<String> names = r.files().stream().map(Archives.Entry::name).sorted().toList();
        assertEquals(List.of("app.log", "old/2026/app.log.1", "old/logs.zip"), names);
        assertEquals(5, r.files().stream().filter(e -> e.name().equals("app.log")).findFirst().orElseThrow().size());
        assertTrue(!r.capped());
    }

    @Test
    void aBigFolderStopsAtTheCap() throws IOException {
        for (int i = 0; i < 30; i++) {
            Files.writeString(dir.resolve("f" + i + ".log"), "x");
        }
        FolderListing.Result r = FolderListing.list(dir, 10, () -> false);
        assertEquals(10, r.files().size());
        assertTrue(r.capped());
        FolderListing.Result stopped = FolderListing.list(dir, 100, () -> true);
        assertTrue(stopped.capped() && stopped.files().isEmpty(), "cancelled");
    }

    @Test
    void linksAreNotFollowed() throws IOException {
        Path other = Files.createTempDirectory("logsift-other");
        try {
            Files.writeString(other.resolve("elsewhere.log"), "x");
            try {
                Files.createSymbolicLink(dir.resolve("link"), other);
            } catch (UnsupportedOperationException | IOException e) {
                return; // no links here (Windows without the right): nothing to check
            }
            Files.writeString(dir.resolve("here.log"), "x");
            assertEquals(List.of("here.log"), FolderListing.list(dir, 100, () -> false).files().stream()
                    .map(Archives.Entry::name).toList());
        } finally {
            Files.deleteIfExists(other.resolve("elsewhere.log"));
            Files.deleteIfExists(other);
        }
    }
}
