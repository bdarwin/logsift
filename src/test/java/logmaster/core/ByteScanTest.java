package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ByteScanTest {

    @Test
    void matchesNaiveAtAllOffsetsAndLengths() {
        Random rnd = new Random(1);
        for (int iter = 0; iter < 3000; iter++) {
            int len = rnd.nextInt(80);
            byte[] a = new byte[len];
            for (int i = 0; i < len; i++) {
                // small alphabet so hits are common; include high bytes to test sign handling
                a[i] = (byte) switch (rnd.nextInt(5)) {
                    case 0 -> '\n';
                    case 1 -> 0x80 | rnd.nextInt(128);
                    case 2 -> 'a';
                    case 3 -> 'A';
                    default -> rnd.nextInt(256);
                };
            }
            int from = len == 0 ? 0 : rnd.nextInt(len + 1);
            int to = from + rnd.nextInt(len - from + 1);
            for (byte b : new byte[] {'\n', 'a', (byte) 0xC3, 0, (byte) 0xFF}) {
                assertEquals(naiveIndexOf(a, from, to, b), ByteScan.indexOf(a, from, to, b));
                assertEquals(naiveCount(a, from, to, b), ByteScan.count(a, from, to, b));
                int either = ByteScan.indexOfEither(a, from, to, b, (byte) 'A');
                int expected = naiveIndexOf(a, from, to, b);
                int e2 = naiveIndexOf(a, from, to, (byte) 'A');
                if (expected < 0 || (e2 >= 0 && e2 < expected)) {
                    expected = e2;
                }
                assertEquals(expected, either);
            }
        }
    }

    private static int naiveIndexOf(byte[] a, int from, int to, byte b) {
        for (int i = from; i < to; i++) {
            if (a[i] == b) {
                return i;
            }
        }
        return -1;
    }

    private static int naiveCount(byte[] a, int from, int to, byte b) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (a[i] == b) {
                n++;
            }
        }
        return n;
    }
}
