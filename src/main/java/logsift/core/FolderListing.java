package logsift.core;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The files in a folder and its subfolders, for showing them as a tree: their paths relative to
 * the folder (with {@code /}) and sizes. Folders that can't be read are skipped; links aren't
 * followed; a big folder (a home folder, /var/log) stops at a cap, and says so.
 */
public final class FolderListing {
    private FolderListing() {}

    /** At most this many files are listed. */
    public static final int MAX_FILES = 20_000;
    /** How deep subfolders are listed. */
    public static final int MAX_DEPTH = 12;

    /**
     * @param capped some files were left out (the cap, or {@code cancelled})
     * @param skipped folders that couldn't be read
     */
    public record Result(List<Archives.Entry> files, boolean capped, int skipped) {}

    public static Result list(Path dir, int max, BooleanSupplier cancelled) throws IOException {
        List<Archives.Entry> out = new ArrayList<>();
        boolean[] capped = {false};
        int[] skipped = {0};
        Files.walkFileTree(dir, EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
                        if (cancelled.getAsBoolean()) {
                            capped[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                        if (!a.isRegularFile()) {
                            return FileVisitResult.CONTINUE; // links, sockets, devices
                        }
                        if (out.size() >= max) {
                            capped[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        String rel = dir.relativize(f).toString().replace(f.getFileSystem().getSeparator(), "/");
                        out.add(new Archives.Entry(rel, a.size()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path f, IOException e) {
                        if (!f.equals(dir)) {
                            skipped[0]++;
                            return FileVisitResult.CONTINUE; // no permission: leave it out
                        }
                        return FileVisitResult.TERMINATE;
                    }
                });
        if (!Files.isReadable(dir)) {
            throw new IOException(dir + ": cannot be read");
        }
        return new Result(List.copyOf(out), capped[0], skipped[0]);
    }
}
