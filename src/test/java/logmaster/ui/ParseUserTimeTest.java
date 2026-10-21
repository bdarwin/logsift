package logmaster.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import logmaster.core.TimeFormat;
import org.junit.jupiter.api.Test;

class ParseUserTimeTest {
    private static long wall(int y, int mo, int d, int h, int mi, int s, int ms) {
        return LocalDateTime.of(y, mo, d, h, mi, s, ms * 1_000_000).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    void parsesTimesOnTheReferenceDate() {
        long ref = wall(2026, 10, 21, 23, 59, 0, 0);
        assertEquals(wall(2026, 10, 21, 10, 5, 0, 0), TimelineBar.parseUserTime("10:05", ref));
        assertEquals(wall(2026, 10, 21, 10, 5, 30, 250), TimelineBar.parseUserTime("10:05:30.25", ref));
        assertEquals(wall(2026, 10, 21, 7, 5, 30, 0), TimelineBar.parseUserTime("7:05:30", ref));
        assertEquals(wall(2025, 1, 2, 3, 4, 5, 6), TimelineBar.parseUserTime("2025-01-02 03:04:05.006", ref));
        assertEquals(TimeFormat.NONE, TimelineBar.parseUserTime("ten past", ref));
    }
}
