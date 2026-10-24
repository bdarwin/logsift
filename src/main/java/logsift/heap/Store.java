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
            synchronized (files) { // arrays are made from more than one thread (the UI, the waste report)
                Path f = dir.resolve(name + "-" + (next++) + "-" + index + ".bin");
                FileChannel ch = FileChannel.open(f, StandardOpenOption.CREATE, StandardOpenOption.READ,
                        StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
                files.add(ch);
                return ch;
            }
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

    /** Empties and closes files whose arrays are no longer used (the mappings go when collected). */
    private void release(FileChannel[] chs) {
        for (FileChannel ch : chs) {
            if (ch == null) {
                continue;
            }
            try {
                ch.truncate(0); // the disk space now, not when the mapping is collected
            } catch (IOException e) {
                // a file that is mapped can't be shortened on Windows: it goes on close
            }
            try {
                ch.close();
            } catch (IOException e) {
                // closing
            }
            synchronized (files) {
                files.remove(ch);
            }
        }
    }

    /**
     * Non-decreasing longs (file positions, edge starts) in 4 bytes each: the low half stored, and
     * where each step of the high half starts. Values are set in index order.
     */
    final class Rising {
        private final Ints low;
        private long[] steps = new long[0]; // steps[h-1]: the first index whose value is >= h << 32
        private long last = -1;
        private long top;

        Rising(String name) {
            low = new Ints(name);
        }

        long get(long i) {
            int h = 0;
            while (h < steps.length && steps[h] <= i) {
                h++;
            }
            return ((long) h << 32) | (low.get(i) & 0xFFFFFFFFL);
        }

        void set(long i, long v) {
            if (i < last || v < top) {
                throw new IllegalArgumentException("not in order: [" + i + "] = " + v);
            }
            while ((v >>> 32) > steps.length) {
                steps = java.util.Arrays.copyOf(steps, steps.length + 1);
                steps[steps.length - 1] = i;
            }
            low.set(i, (int) v);
            last = i;
            top = v;
        }

        Rising size(long n) {
            low.size(n);
            return this;
        }

        void free() {
            low.free();
        }
    }

    Rising rising(String name) {
        return new Rising(name);
    }

    /**
     * Byte counts that are multiples of 8 (object sizes and sums of them), in 4 bytes each: up to
     * 32 GB each, and the few above that (what holds most of a big heap) kept aside.
     */
    final class Sizes {
        private static final long BIG = 0xFFFFFFFFL;
        private final Ints units;
        private final java.util.Map<Long, Long> big = new java.util.concurrent.ConcurrentHashMap<>();

        Sizes(String name) {
            units = new Ints(name);
        }

        Sizes size(long n) {
            units.size(n);
            return this;
        }

        long get(long i) {
            long u = units.get(i) & 0xFFFFFFFFL;
            return u == BIG ? big.get(i) : u << 3;
        }

        void set(long i, long bytes) {
            if ((bytes & 7) != 0 || bytes < 0) {
                throw new IllegalArgumentException("not a size: " + bytes);
            }
            long u = bytes >>> 3;
            if (u >= BIG) {
                big.put(i, bytes);
                units.set(i, (int) BIG);
            } else {
                units.set(i, (int) u);
                if (!big.isEmpty()) {
                    big.remove(i);
                }
            }
        }

        void add(long i, long bytes) {
            set(i, get(i) + bytes);
        }

        void free() {
            units.free();
            big.clear();
        }
    }

    Sizes sizes(String name) {
        return new Sizes(name);
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

        /** Gives the space back (on disk: the files are emptied and removed). */
        void free() {
            chunks = new IntBuffer[0];
            release(channels);
            channels = new FileChannel[0];
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

        /** Gives the space back (on disk: the files are emptied and removed). */
        void free() {
            chunks = new LongBuffer[0];
            release(channels);
            channels = new FileChannel[0];
        }
    }
}
