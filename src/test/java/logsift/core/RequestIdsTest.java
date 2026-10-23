package logsift.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class RequestIdsTest {

    private static List<String> found(String line) {
        List<String> out = new ArrayList<>();
        for (RequestIds.Id id : RequestIds.find(line)) {
            out.add(id.name() + "=" + id.value());
        }
        return out;
    }

    @Test
    void findsKeyedIds() {
        assertEquals(List.of("traceId=4bf92f3577b34da6", "requestId=req-81"),
                found("10:02:13 INFO [http-1] c.a.Api - requestId=req-81 traceId=4bf92f3577b34da6 GET /orders"));
        assertEquals(List.of("request_id=a1b2c3"), found("2026-10-21 10:02:13,120 INFO orders: done request_id=a1b2c3."));
        assertEquals(List.of("X-Request-Id=77ab-0912"), found("headers X-Request-Id: 77ab-0912, Accept: */*"));
        assertEquals(List.of("correlationId=c-9001", "orderId=8812"),
                found("{\"level\":\"INFO\",\"orderId\":\"8812\",\"correlationId\":\"c-9001\",\"msg\":\"paid\"}"));
        assertEquals(List.of("userId=alice"), found("login ok userId=alice"));
    }

    @Test
    void findsTracingFormats() {
        assertEquals(List.of("traceId=5f1d6ac7a1b2c3d4"),
                found("2026-10-21 10:02:13.120 INFO [orders,5f1d6ac7a1b2c3d4,8a3b2c1d0e9f8a7b] 4211 --- c.a.Api : ok"));
        assertEquals(List.of("traceId=4bf92f3577b34da6a3ce929d0e0e4736"),
                found("INFO 4211 --- [orders] [nio-8080-exec-1] [4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7] c.a.Api : ok"));
        assertEquals(List.of("traceId=4bf92f3577b34da6a3ce929d0e0e4736"),
                found("traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        assertEquals(List.of("id=3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
                found("job 3f2504e0-4f89-11d3-9a0c-0305e82c3301 finished"));
    }

    @Test
    void skipsThingsThatAreNotRequests() {
        assertEquals(List.of(), found("pid=4211 tid=17 started"));
        assertEquals(List.of(), found("paid=true valid=yes"), "words ending in -id");
        assertEquals(List.of(), found("requestId=null userId=- traceId="));
        assertEquals(List.of(), found("ERROR [main] Database connection lost after 812 ms"));
        assertEquals(List.of(), found(null));
        assertEquals(List.of("traceId=abc123"), found("spanId=deadbeef traceId=abc123"), "spans are parts of a trace");
    }

    @Test
    void ranksTracesFirstAndLimits() {
        List<String> ids = found("userId=u-1 orderId=o-2 sessionId=s-3 requestId=r-4 traceId=t-5 "
                + "3f2504e0-4f89-11d3-9a0c-0305e82c3301");
        assertEquals(RequestIds.MAX, ids.size());
        assertEquals("traceId=t-5", ids.get(0));
        assertEquals("requestId=r-4", ids.get(1));
    }

    @Test
    void regexMatchesTheWholeToken() {
        Pattern p = Pattern.compile(RequestIds.regex("req-81"));
        assertTrue(p.matcher("requestId=req-81 done").find());
        assertTrue(p.matcher("[req-81]").find());
        assertFalse(p.matcher("requestId=req-812").find());
        assertFalse(p.matcher("xreq-81").find());
        assertTrue(Pattern.compile(RequestIds.regex("a.b+c")).matcher("id a.b+c").find());
        assertFalse(Pattern.compile(RequestIds.regex("a.b+c")).matcher("id axb+c").find());
    }

    @Test
    void labelsLongValuesShort() {
        assertEquals("traceId 4bf92f3577b3…0e4736",
                new RequestIds.Id("traceId", "4bf92f3577b34da6a3ce929d0e0e4736").label());
        assertEquals("orderId 8812", new RequestIds.Id("orderId", "8812").label());
    }
}
