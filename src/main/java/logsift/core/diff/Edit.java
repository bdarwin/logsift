package logsift.core.diff;

/**
 * One run of a diff between sequences {@code a} (left, older) and {@code b} (right, newer):
 * items {@code [aStart, aEnd)} of a and {@code [bStart, bEnd)} of b. Runs follow each other
 * without gaps and cover both sequences.
 */
public record Edit(Kind kind, int aStart, int aEnd, int bStart, int bEnd) {

    public enum Kind {
        /** The same items on both sides (as many on each). */
        EQUAL,
        /** Items only in b. */
        INSERT,
        /** Items only in a. */
        DELETE,
        /** Items of a replaced by items of b. */
        REPLACE
    }

    public int aLength() {
        return aEnd - aStart;
    }

    public int bLength() {
        return bEnd - bStart;
    }
}
