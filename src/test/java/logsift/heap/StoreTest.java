package logsift.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StoreTest {
    @Test
    void risingValuesPastFourGigabytes() throws Exception {
        for (Store st : new Store[] {Store.inMemory(), Store.onDisk(Files.createTempDirectory("logsift-store"))}) {
            try (st) {
                Store.Rising r = st.rising("r").size(10);
                long[] v = {0, 5, 0xFFFFFFFFL, 1L << 32, (1L << 32) + 7, 40L << 30, 40L << 30, (40L << 30) + 1, 1L << 36,
                    (1L << 36) + 3};
                for (int i = 0; i < v.length; i++) {
                    r.set(i, v[i]);
                }
                for (int i = 0; i < v.length; i++) {
                    assertEquals(v[i], r.get(i), "at " + i);
                }
                assertThrows(IllegalArgumentException.class, () -> r.set(3, 1));
            }
        }
    }

    @Test
    void sizesPastThirtyTwoGigabytes() throws Exception {
        Path dir = Files.createTempDirectory("logsift-store");
        try (Store st = Store.onDisk(dir)) {
            Store.Sizes s = st.sizes("s").size(4);
            s.set(0, 24);
            s.set(1, 32L << 30);
            s.set(2, 40L << 30);
            s.add(0, 16);
            s.add(2, 8);
            assertEquals(40, s.get(0));
            assertEquals(32L << 30, s.get(1));
            assertEquals((40L << 30) + 8, s.get(2));
            assertEquals(0, s.get(3));
            s.set(2, 64);
            assertEquals(64, s.get(2));
            assertThrows(IllegalArgumentException.class, () -> s.set(3, 12));
        }
        assertEquals(false, Files.exists(dir));
    }

    @Test
    void freedFilesGiveTheirSpaceBack() throws Exception {
        Path dir = Files.createTempDirectory("logsift-store");
        try (Store st = Store.onDisk(dir)) {
            Store.Ints a = st.ints("a").size(1 << 20);
            a.set((1 << 20) - 1, 7);
            Store.Ints b = st.ints("b").size(10);
            a.free();
            b.set(3, 4);
            assertEquals(4, b.get(3));
        }
    }

    @Test
    void aDumpTooBigForTheDiskIsToldBeforeReading() throws Exception {
        Path dir = Files.createTempDirectory("logsift-room");
        assertEquals(null, HeapDump.roomProblem(dir, 1000));
        String said = HeapDump.roomProblem(dir.resolve("heap-index"), 1L << 52);
        org.junit.jupiter.api.Assertions.assertTrue(said != null && said.contains("free disk space"), said);
        Files.delete(dir);
    }
}
