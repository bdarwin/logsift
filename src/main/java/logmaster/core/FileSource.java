package logmaster.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Read-only access to a file using positional reads.
 *
 * <p>We use positional reads instead of memory mapping on purpose: a mapped file cannot be
 * renamed or deleted on Windows while mapped (which breaks log rotation by the app that writes
 * the log), mappings are limited to 2 GB each, and unmapping is not under our control. Reads go
 * through the OS page cache, so they are nearly as fast. Positional reads are safe to call from
 * many threads at once.
 */
public final class FileSource implements Closeable {
    private final Path path;
    private final FileChannel channel;

    public FileSource(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
    }

    public Path path() {
        return path;
    }

    /** Current size of the open file. */
    public long size() throws IOException {
        return channel.size();
    }

    /**
     * Reads up to {@code len} bytes at {@code pos} into {@code dst[off..]}. Stops early only at
     * end of file. Returns the number of bytes read.
     */
    public int read(long pos, byte[] dst, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(dst, off, len);
        int total = 0;
        while (bb.hasRemaining()) {
            int n = channel.read(bb, pos + total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
