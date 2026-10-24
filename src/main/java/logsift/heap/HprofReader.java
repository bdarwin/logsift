package logsift.heap;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Reads a .hprof file front to back (big-endian, ids of 4 or 8 bytes), through a large buffer. */
final class HprofReader {
    private final FileChannel ch;
    private final ByteBuffer buf;
    private long bufStart; // file position of buf's first byte
    private final long size;
    int idSize = 8;

    HprofReader(FileChannel ch, long from) throws IOException {
        this(ch, from, 8 << 20);
    }

    /** With a buffer of {@code bufferBytes} (small for jumping around, big for reading through). */
    HprofReader(FileChannel ch, long from, int bufferBytes) throws IOException {
        this.ch = ch;
        this.buf = ByteBuffer.allocateDirect(bufferBytes);
        this.buf.limit(0); // nothing read yet
        this.size = ch.size();
        seek(from);
    }

    long size() {
        return size;
    }

    /** The file position of the next byte. */
    long position() {
        return bufStart + buf.position();
    }

    void seek(long pos) throws IOException {
        if (pos >= bufStart && pos <= bufStart + buf.limit()) {
            buf.position((int) (pos - bufStart)); // already in the buffer
            return;
        }
        bufStart = pos;
        buf.clear();
        buf.limit(0);
    }

    boolean atEnd() throws IOException {
        return position() >= size;
    }

    private void need(int n) throws IOException {
        if (buf.remaining() >= n) {
            return;
        }
        long pos = position();
        buf.compact(); // the unread bytes move to the front
        bufStart = pos;
        while (buf.position() < n) {
            int r = ch.read(buf, bufStart + buf.position());
            if (r < 0) {
                throw new EOFException("the heap dump ends early (at byte " + position() + ")");
            }
        }
        buf.flip();
    }

    int u1() throws IOException {
        need(1);
        return buf.get() & 0xFF;
    }

    int u2() throws IOException {
        need(2);
        return buf.getShort() & 0xFFFF;
    }

    int u4() throws IOException {
        need(4);
        return buf.getInt();
    }

    long u4u() throws IOException {
        return Integer.toUnsignedLong(u4());
    }

    long u8() throws IOException {
        need(8);
        return buf.getLong();
    }

    long id() throws IOException {
        return idSize == 8 ? u8() : u4u();
    }

    void bytes(byte[] dst, int off, int len) throws IOException {
        while (len > 0) {
            need(1);
            int k = Math.min(len, buf.remaining());
            buf.get(dst, off, k);
            off += k;
            len -= k;
        }
    }

    void skip(long n) throws IOException {
        if (n <= buf.remaining()) {
            buf.position(buf.position() + (int) n);
        } else {
            seek(position() + n);
        }
    }

    /** A 64-bit hash of the next {@code n} bytes (for finding equal contents). */
    long hash(long n) throws IOException {
        long h = 0x9E3779B97F4A7C15L ^ n;
        while (n > 0) {
            need((int) Math.min(n, 8));
            while (n >= 8 && buf.remaining() >= 8) {
                h = mix(h ^ buf.getLong());
                n -= 8;
            }
            while (n > 0 && n < 8 && buf.hasRemaining()) {
                h = mix(h ^ (buf.get() & 0xFF));
                n--;
            }
        }
        return h;
    }

    private static long mix(long x) {
        x *= 0xFF51AFD7ED558CCDL;
        x ^= x >>> 33;
        x *= 0xC4CEB9FE1A85EC53L;
        return x ^ (x >>> 29);
    }

    /** A value of this basic type (as a long: ids, numbers, float and double bits). */
    long value(int type) throws IOException {
        return switch (type) {
            case HeapDump.T_OBJECT -> id();
            case HeapDump.T_BOOLEAN, HeapDump.T_BYTE -> u1();
            case HeapDump.T_CHAR, HeapDump.T_SHORT -> u2();
            case HeapDump.T_FLOAT, HeapDump.T_INT -> u4();
            case HeapDump.T_DOUBLE, HeapDump.T_LONG -> u8();
            default -> throw new IOException("unknown value type " + type + " at byte " + position());
        };
    }

    int sizeOf(int type) {
        return HeapDump.sizeOf(type, idSize);
    }
}
