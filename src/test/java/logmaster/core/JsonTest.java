package logmaster.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void parsesAndPrints() {
        Map<String, Object> m = Json.parseObject(
                "{\"ts\":1729500000123,\"level\":\"WARN\",\"msg\":\"a \\\"quoted\\\" \\u00e9\",\"tags\":[\"x\",2,true,null],\"ctx\":{\"u\":\"42\"}}");
        assertEquals(List.of("ts", "level", "msg", "tags", "ctx"), new ArrayList<>(m.keySet()));
        assertEquals("a \"quoted\" é", m.get("msg"));
        assertEquals("1729500000123", m.get("ts").toString());
        assertEquals("[\"x\",2,true,null]", Json.display(m.get("tags")));
        String pretty = Json.pretty(m);
        assertTrue(pretty.contains("\n  \"level\": \"WARN\""), pretty);
        assertEquals(m, Json.parseObject(pretty), "pretty output parses back");
        for (String bad : new String[] {"", "x", "{", "{\"a\":}", "[1,2]", "{\"a\":1} trailing", "{\"a\" 1}"}) {
            assertNull(Json.parseObject(bad), bad);
        }
    }

    @Test
    void jsonLinesBecomeColumns() {
        List<String> sample = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            sample.add("{\"@timestamp\":\"2026-10-21T10:00:" + (10 + i) + ".000Z\",\"level\":\"INFO\","
                    + "\"logger\":\"c.a.Svc\",\"thread\":\"main\",\"message\":\"hello " + i + "\",\"user\":\"u" + i + "\"}");
        }
        LogFormat f = LogFormat.detect(sample);
        assertTrue(f.isJson());
        assertEquals(List.of("time", "level", "thread", "logger", "fields", "message"), f.columns());
        String[] v = f.split(sample.get(3), null);
        assertEquals("2026-10-21T10:00:13.000Z", v[0]);
        assertEquals("INFO", v[1]);
        assertEquals("hello 3", v[5]);
        assertEquals("user=u3", v[4]);
        assertNull(f.split("not json", null));
        TimeFormat tf = TimeFormat.detect(sample, 2026);
        assertTrue(tf != null && tf.parse(sample.get(0)) != TimeFormat.NONE, "ISO time inside JSON");

        List<String> epoch = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            epoch.add("{\"ts\":" + (1729500000123L + i) + ",\"lvl\":\"debug\",\"msg\":\"m\"}");
        }
        TimeFormat ef = TimeFormat.detect(epoch, 2026);
        assertEquals(1729500000123L, ef.parse(epoch.get(0)), ef.name());
    }
}
