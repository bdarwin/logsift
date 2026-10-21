package logsift.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.nio.file.StandardOpenOption;

/**
 * Read-only access to a file using positional reads.
 *
 * <p>We use positional reads instead of memory mapping on purpose: a mapped file cannot be
 * renamed or deleted on Windows while mapped (which breaks log rotation by the app that writes
 * the log), mappings are limited to 2 GB each, and unmapping is not under our control. Reads go
 * through the OS page cache, so they are nearly as fast. Positional reads are safe to call from
 * many threads at once.
 *
 * <p>Java closes a file channel when a thread is interrupted while reading it. We don't interrupt
 * readers, but if it happens anyway (for example from a library), the channel is reopened so one
 * interrupted thread can't break the document for everyone.
 */
public final class FileSource implements Closeable {
    private final Path path;
    private final Object fileKey; // identifies the file (inode); null on Windows
    private volatile FileChannel channel;
    private volatile boolean closed;

    public FileSource(Path path) throws IOException {
        this.path = path;
        this.fileKey = fileKey(path);
        this.channel = FileChannel.open(path, StandardOpenOption.READ);
    }

    private static Object fileKey(Path p) throws IOException {
        return Files.readAttributes(p, BasicFileAttributes.class).fileKey();
    }

    public Path path() {
        return path;
    }

    /** Current size of the open file. */
    public long size() throws IOException {
        try {
            return channel.size();
        } catch (ClosedChannelException e) {
            return retry(e, () -> channel.size());
        }
    }

    /**
     * Reads up to {@code len} bytes at {@code pos} into {@code dst[off..]}. Stops early only at
     * end of file. Returns the number of bytes read.
     */
    public int read(long pos, byte[] dst, int off, int len) throws IOException {
        try {
            return readOnce(pos, dst, off, len);
        } catch (ClosedChannelException e) {
            return retry(e, () -> readOnce(pos, dst, off, len));
        }
    }

    private interface IoCall<T> {
        T call() throws IOException;
    }

    /** Reopens a channel closed by an interrupt (not by us) and tries once more. */
    private <T> T retry(ClosedChannelException e, IoCall<T> call) throws IOException {
        if (closed) {
            throw e;
        }
        // The interrupt flag would close the new channel at once; keep it aside meanwhile.
        boolean interrupted = Thread.interrupted() || e instanceof ClosedByInterruptException;
        try {
            reopen();
            return call.call();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void reopen() throws IOException {
        if (channel.isOpen()) {
            return; // another thread already reopened it
        }
        if (!Objects.equals(fileKey, fileKey(path))) {
            throw new FileChangedException("file was replaced");
        }
        channel = FileChannel.open(path, StandardOpenOption.READ);
    }

    private int readOnce(long pos, byte[] dst, int off, int len) throws IOException {
        FileChannel channel = this.channel;
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
    public synchronized void close() throws IOException {
        closed = true;
        channel.close();
    }
}
