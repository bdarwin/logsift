package logsift.heap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the index of a heap dump keeps its big arrays: in memory for small dumps, in memory-mapped
 * files on disk for big ones (so a 10 GB dump doesn't need 10 GB of Java heap). The arrays grow
 * in chunks as they are written.
 */
public final class Store implements AutoCloseable {
    static final int SHIFT = 24; // 16M elements per chunk
    static final int CHUNK = 1 << SHIFT;
    static final int MASK = CHUNK - 1;

    private final Path dir; // null: in memory
    private final List<FileChannel> files = new ArrayList<>();
    private int next;

    private Store(Path dir) {
        this.dir = dir;
    }

    /** Arrays in memory. */
    public static Store inMemory() {
        return new Store(null);
    }

    /** Arrays in files in {@code dir} (created; removed on close). */
    public static Store onDisk(Path dir) throws IOException {
        Files.createDirectories(dir);
        return new Store(dir);
    }

    public boolean isOnDisk() {
        return dir != null;
    }

    Ints ints(String name) {
        return new Ints(name);
    }

    Longs longs(String name) {
        return new Longs(name);
    }

    private FileChannel open(String name, int index) {
        try {
            Path f = dir.resolve(name + "-" + (next++) + "-" + index + ".bin");
            FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
            synchronized (files) {
                files.add(ch);
            }
            return ch;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Maps the first {@code bytes} of a file (growing it); what was written before stays. */
    private static MappedByteBuffer map(FileChannel ch, long bytes) {
        try {
            MappedByteBuffer b = ch.map(FileChannel.MapMode.READ_WRITE, 0, bytes);
            b.order(ByteOrder.nativeOrder());
            return b;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        synchronized (files) {
            for (FileChannel ch : files) {
                try {
                    ch.close();
                } catch (IOException e) {
                    // a mapped file may stay until the buffer is collected (Windows)
                }
            }
            files.clear();
        }
        if (dir != null) {
            try (var s = Files.list(dir)) {
                for (Path p : s.toList()) {
                    Files.deleteIfExists(p);
                }
                Files.deleteIfExists(dir);
            } catch (IOException e) {
                // removed at the next start (see HeapIndex.cleanUp)
            }
        }
    }

    /** A growable array of ints, all 0 at first. */
    final class Ints {
        private final String name;
        private IntBuffer[] chunks = new IntBuffer[0];
        private FileChannel[] channels = new FileChannel[0];

        Ints(String name) {
            this.name = name;
        }

        int get(long i) {
            return chunks[(int) (i >>> SHIFT)].get((int) (i & MASK));
        }

        void set(long i, int v) {
            chunk(i).put((int) (i & MASK), v);
        }

        void add(long i, int d) {
            IntBuffer c = chunk(i);
            int k = (int) (i & MASK);
            c.put(k, c.get(k) + d);
        }

        private IntBuffer chunk(long i) {
            int c = (int) (i >>> SHIFT);
            if (c >= chunks.length || (i & MASK) >= chunks[c].capacity()) {
                grow(c, (int) (i & MASK));
            }
            return chunks[c];
        }

        /** Makes room for {@code n} elements. */
        Ints size(long n) {
            if (n > 0) {
                chunk(n - 1);
            }
            return this;
        }

        void fill(long n, int v) {
            size(n);
            for (long i = 0; i < n; i++) {
                set(i, v);
            }
        }

        /** Makes chunk {@code c} hold index {@code k}; in memory, the last chunk grows by doubling. */
        private void grow(int c, int k) {
            IntBuffer[] a = chunks.length > c ? chunks : java.util.Arrays.copyOf(chunks, c + 1);
            for (int j = 0; j <= c; j++) {
                int want = j < c ? CHUNK : k + 1;
                IntBuffer old = a[j];
                if (old != null && old.capacity() >= want) {
                    continue;
                }
                int cap = Math.max(old == null ? 1024 : old.capacity(), 1024);
                while (cap < want) {
                    cap = Math.min(CHUNK, cap * 2);
                }
                if (dir != null) { // the same file mapped bigger: on disk, files grow as needed too
                    if (j >= channels.length) {
                        channels = java.util.Arrays.copyOf(channels, j + 1);
                    }
                    if (channels[j] == null) {
                        channels[j] = open(name, j);
                    }
                    a[j] = map(channels[j], (long) cap * 4).asIntBuffer();
                } else {
                    int[] arr = new int[cap];
                    if (old != null) {
                        old.get(0, arr, 0, old.capacity());
                    }
                    a[j] = IntBuffer.wrap(arr);
                }
            }
            chunks = a;
        }

        void free() {
            chunks = new IntBuffer[0];
        }
    }

    /** A growable array of longs, all 0 at first. */
    final class Longs {
        private final String name;
        private LongBuffer[] chunks = new LongBuffer[0];
        private FileChannel[] channels = new FileChannel[0];

        Longs(String name) {
            this.name = name;
        }

        long get(long i) {
            return chunks[(int) (i >>> SHIFT)].get((int) (i & MASK));
        }

        void set(long i, long v) {
            chunk(i).put((int) (i & MASK), v);
        }

        void add(long i, long d) {
            LongBuffer c = chunk(i);
            int k = (int) (i & MASK);
            c.put(k, c.get(k) + d);
        }

        private LongBuffer chunk(long i) {
            int c = (int) (i >>> SHIFT);
            if (c >= chunks.length || (i & MASK) >= chunks[c].capacity()) {
                grow(c, (int) (i & MASK));
            }
            return chunks[c];
        }

        Longs size(long n) {
            if (n > 0) {
                chunk(n - 1);
            }
            return this;
        }

        /** Makes chunk {@code c} hold index {@code k}; in memory, the last chunk grows by doubling. */
        private void grow(int c, int k) {
            LongBuffer[] a = chunks.length > c ? chunks : java.util.Arrays.copyOf(chunks, c + 1);
            for (int j = 0; j <= c; j++) {
                int want = j < c ? CHUNK : k + 1;
                LongBuffer old = a[j];
                if (old != null && old.capacity() >= want) {
                    continue;
                }
                int cap = Math.max(old == null ? 1024 : old.capacity(), 1024);
                while (cap < want) {
                    cap = Math.min(CHUNK, cap * 2);
                }
                if (dir != null) { // the same file mapped bigger: on disk, files grow as needed too
                    if (j >= channels.length) {
                        channels = java.util.Arrays.copyOf(channels, j + 1);
                    }
                    if (channels[j] == null) {
                        channels[j] = open(name, j);
                    }
                    a[j] = map(channels[j], (long) cap * 8).asLongBuffer();
                } else {
                    long[] arr = new long[cap];
                    if (old != null) {
                        old.get(0, arr, 0, old.capacity());
                    }
                    a[j] = LongBuffer.wrap(arr);
                }
            }
            chunks = a;
        }

        void free() {
            chunks = new LongBuffer[0];
        }
    }
}
