package logsift.jvm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The heap in use, read from GC.heap_info as each collector writes it (from real JVMs). */
class HeapRoomTest {
    @Test
    void heapInUseForEveryCollector() {
        assertEquals(22660L << 10, HeapHistogram.heapUsed("""
                81494:
                garbage-first heap   total reserved 262144K, committed 262144K, used 22660K [0x0000000470000000, 0x0000000480000000)
                 region size 1024K, 21 young (21504K), 0 survivors (0K)
                """));
        assertEquals((23605L + 1156) << 10, HeapHistogram.heapUsed("""
                PSYoungGen      total 76288K, used 23605K [0x000000047ab00000, 0x0000000480000000, 0x0000000480000000)
                 eden space 65536K, 36% used [0x000000047ab00000,0x000000047c20d510,0x000000047eb00000)
                 from space 10752K, 0% used [0x000000047f580000,0x000000047f580000,0x0000000480000000)
                 to   space 10752K, 0% used [0x000000047eb00000,0x000000047eb00000,0x000000047f580000)
                ParOldGen       total 175104K, used 1156K [0x0000000470000000, 0x000000047ab00000, 0x000000047ab00000)
                 object space 175104K, 0% used [0x0000000470000000,0x00000004701211d8,0x000000047ab00000)
                """), "young and old added up, the percentages not counted");
        assertEquals((23860L + 1156) << 10, HeapHistogram.heapUsed("""
                DefNew     total 78656K, used 23860K [0x0000000470000000, 0x0000000475550000, 0x0000000475550000)
                 eden space 69952K,  34% used [0x0000000470000000, 0x000000047174d380, 0x0000000474450000)
                Tenured    total 174784K, used 1156K [0x0000000475550000, 0x0000000480000000, 0x0000000480000000)
                """));
        assertEquals(21191L << 10, HeapHistogram.heapUsed("""
                Shenandoah Heap
                 256M max, 256M soft max, 256M committed, 21191K used
                 1024 x 256 K regions
                """));
        assertEquals(22L << 20, HeapHistogram.heapUsed("""
                 ZHeap           used 22M, capacity 256M, max capacity 256M
                 Metaspace       used 125K, committed 320K, reserved 1056768K
                  class space    used 4K, committed 128K, reserved 1048576K
                """), "metaspace left out");
        assertEquals(-1, HeapHistogram.heapUsed("something else"));
    }

    @Test
    void bigHeapsGetTime() {
        assertEquals(3600, SshJvms.timeoutOf("GC.heap_dump", 120));
        assertEquals(900, SshJvms.timeoutOf("GC.class_histogram", 60));
        assertEquals(120, SshJvms.timeoutOf("Thread.print", 120));
    }
}
