package logsift.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Fast byte searching over byte arrays.
 *
 * <p>Uses "SWAR" (SIMD within a register): 8 bytes are checked at once by reading them as one
 * long. This is several times faster than a plain byte loop for newline scanning, which is the
 * hot path for both indexing and searching.
 */
public final class ByteScan {
    private static final VarHandle LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long ONES = 0x0101010101010101L;
    private static final long LOW7 = 0x7F7F7F7F7F7F7F7FL;

    private ByteScan() {}

    /** Returns a long whose high bit is set in exactly the bytes of {@code x} that are zero. */
    private static long zeroBytes(long x) {
        long y = (x & LOW7) + LOW7;
        return ~(y | x | LOW7);
    }

    /** Index of the first {@code b} in {@code a[from, to)}, or -1. */
    public static int indexOf(byte[] a, int from, int to, byte b) {
        long pattern = (b & 0xFFL) * ONES;
        int i = from;
        int limit = to - 8;
        while (i <= limit) {
            long z = zeroBytes((long) LONGS.get(a, i) ^ pattern);
            if (z != 0) {
                return i + (Long.numberOfTrailingZeros(z) >>> 3);
            }
            i += 8;
        }
        for (; i < to; i++) {
            if (a[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the first byte equal to {@code b1} or {@code b2} in {@code a[from, to)}, or -1. */
    public static int indexOfEither(byte[] a, int from, int to, byte b1, byte b2) {
        if (b1 == b2) {
            return indexOf(a, from, to, b1);
        }
        long p1 = (b1 & 0xFFL) * ONES;
        long p2 = (b2 & 0xFFL) * ONES;
        int i = from;
        int limit = to - 8;
        while (i <= limit) {
            long w = (long) LONGS.get(a, i);
            long z = zeroBytes(w ^ p1) | zeroBytes(w ^ p2);
            if (z != 0) {
                return i + (Long.numberOfTrailingZeros(z) >>> 3);
            }
            i += 8;
        }
        for (; i < to; i++) {
            if (a[i] == b1 || a[i] == b2) {
                return i;
            }
        }
        return -1;
    }

    /** Number of bytes equal to {@code b} in {@code a[from, to)}. */
    public static int count(byte[] a, int from, int to, byte b) {
        long pattern = (b & 0xFFL) * ONES;
        int n = 0;
        int i = from;
        int limit = to - 8;
        while (i <= limit) {
            n += Long.bitCount(zeroBytes((long) LONGS.get(a, i) ^ pattern));
            i += 8;
        }
        for (; i < to; i++) {
            if (a[i] == b) {
                n++;
            }
        }
        return n;
    }

    /** Index of the last {@code b} in {@code a[from, to)}, or -1. */
    public static int lastIndexOf(byte[] a, int from, int to, byte b) {
        for (int i = to - 1; i >= from; i--) {
            if (a[i] == b) {
                return i;
            }
        }
        return -1;
    }
}
