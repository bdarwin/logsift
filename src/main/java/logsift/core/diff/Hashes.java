package logsift.core.diff;

/**
 * 64-bit hashes of strings, two independent ones: a key is matched by both (128 bits), so two
 * different keys are never taken for one.
 */
final class Hashes {
    private Hashes() {}

    static final long SEED1 = 0xCBF29CE484222325L;
    static final long SEED2 = 0x84222325CBF29CE4L;

    /** Adds a value (null: one that isn't there) to hash {@code h} of kind 1. */
    static long add1(long h, String s) {
        if (s == null) {
            return mix(h ^ 0x51AFD7ED558CCDL);
        }
        for (int i = 0; i < s.length(); i++) {
            h = (h ^ s.charAt(i)) * 0x100000001B3L;
        }
        return mix(h ^ s.length());
    }

    /** Like {@link #add1}, another hash. */
    static long add2(long h, String s) {
        if (s == null) {
            return mix(h ^ 0xC4CEB9FE1A85EC53L);
        }
        for (int i = 0; i < s.length(); i++) {
            h = Long.rotateLeft(h + s.charAt(i), 23) * 0x9E3779B97F4A7C15L;
        }
        return mix(h + s.length() * 0xFF51AFD7ED558CCDL);
    }

    /** The finish of MurmurHash3: every input bit changes about half of the output. */
    static long mix(long k) {
        k ^= k >>> 33;
        k *= 0xFF51AFD7ED558CCDL;
        k ^= k >>> 33;
        k *= 0xC4CEB9FE1A85EC53L;
        k ^= k >>> 33;
        return k;
    }
}
