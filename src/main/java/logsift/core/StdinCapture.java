package logsift.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Lets Logsift show another program's output ({@code kubectl logs -f pod | logsift -}): the
 * piped text is copied to a file as it arrives, and that file is opened and followed like any
 * growing log.
 */
public final class StdinCapture {
    private StdinCapture() {}

    /** Starts copying {@code in} into a new file in {@code dir}; returns the file at once. */
    public static Path start(InputStream in, Path dir) throws IOException {
        Files.createDirectories(dir);
        cleanOld(dir, Duration.ofDays(7));
        String name = "stdin-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log";
        Path f = dir.resolve(name);
        int n = 1;
        while (Files.exists(f)) {
            f = dir.resolve(name.replace(".log", "-" + n++ + ".log"));
        }
        Files.createFile(f);
        Path target = f;
        Thread t = new Thread(() -> copy(in, target), "logsift-stdin");
        t.setDaemon(true);
        t.start();
        return f;
    }

    private static void copy(InputStream in, Path target) {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) >= 0) {
                out.write(buf, 0, r);
                out.flush(); // so the viewer sees each chunk as it arrives
            }
        } catch (IOException e) {
            // the pipe closed or the disk is full: keep what was captured
        }
    }

    /** Removes captures older than {@code age}. */
    static void cleanOld(Path dir, Duration age) {
        Instant cutoff = Instant.now().minus(age);
        try (var files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().startsWith("stdin-"))
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toInstant().isBefore(cutoff);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // in use or gone
                        }
                    });
        } catch (IOException e) {
            // nothing to clean
        }
    }
}
