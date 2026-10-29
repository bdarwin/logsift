package logsift.crash;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A growable little-endian byte buffer, for writing test files. */
final class LeBytes {
    private byte[] a = new byte[256];
    private int n;

    int pos() {
        return n;
    }

    private void room(int more) {
        if (n + more > a.length) {
            a = Arrays.copyOf(a, Math.max(a.length * 2, n + more));
        }
    }

    LeBytes u8(int v) {
        room(1);
        a[n++] = (byte) v;
        return this;
    }

    LeBytes u16(int v) {
        return u8(v).u8(v >>> 8);
    }

    LeBytes u32(long v) {
        return u16((int) v).u16((int) (v >>> 16));
    }

    LeBytes u64(long v) {
        return u32(v).u32(v >>> 32);
    }

    LeBytes bytes(byte[] b) {
        room(b.length);
        System.arraycopy(b, 0, a, n, b.length);
        n += b.length;
        return this;
    }

    LeBytes zeros(int count) {
        room(count);
        n += count;
        return this;
    }

    LeBytes cstring(String s) {
        return bytes(s.getBytes(StandardCharsets.UTF_8)).u8(0);
    }

    LeBytes align(int to) {
        while (n % to != 0) {
            u8(0);
        }
        return this;
    }

    void putU32(int at, long v) {
        for (int i = 0; i < 4; i++) {
            a[at + i] = (byte) (v >>> (8 * i));
        }
    }

    byte[] toArray() {
        return Arrays.copyOf(a, n);
    }

    /** Writes into a fixed-size array at an offset. */
    static void put64(byte[] b, int at, long v) {
        for (int i = 0; i < 8; i++) {
            b[at + i] = (byte) (v >>> (8 * i));
        }
    }

    static void put32(byte[] b, int at, long v) {
        for (int i = 0; i < 4; i++) {
            b[at + i] = (byte) (v >>> (8 * i));
        }
    }

    static void put16(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >>> 8);
    }
}
