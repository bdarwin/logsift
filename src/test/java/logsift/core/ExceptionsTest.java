package logsift.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ExceptionsTest {

    private static final List<String> JAVA = List.of(
            "2026-10-21 10:02:13.120 ERROR [http-1] c.a.Api - Order failed",
            "org.springframework.web.util.NestedServletException: Request processing failed",
            "\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1014)",
            "\tat javax.servlet.http.HttpServlet.service(HttpServlet.java:665)",
            "Caused by: java.lang.IllegalStateException: Could not place order 812",
            "\tat com.acme.shop.OrderService.place(OrderService.java:142)",
            "\tat com.acme.shop.OrderController.create(OrderController.java:57)",
            "\t... 40 more",
            "\tSuppressed: java.io.IOException: close failed",
            "\t\tat com.acme.shop.Io.close(Io.java:9)",
            "Caused by: java.lang.NullPointerException: Cannot invoke \"Order.total()\" because \"order\" is null",
            "\tat java.base/java.util.Objects.requireNonNull(Objects.java:233)",
            "\tat com.acme.shop.PriceCalculator.total(PriceCalculator.java:42)",
            "\tat com.acme.shop.OrderService.place(OrderService.java:139)",
            "\t... 42 common frames omitted");

    @Test
    void parsesAJavaChain() {
        List<Exceptions.Thrown> chain = Exceptions.parse(JAVA);
        assertEquals(List.of("org.springframework.web.util.NestedServletException", "java.lang.IllegalStateException",
                "java.lang.NullPointerException"), chain.stream().map(Exceptions.Thrown::type).toList());
        Exceptions.Thrown root = chain.get(2);
        assertEquals("Cannot invoke \"Order.total()\" because \"order\" is null", root.message());
        assertEquals(3, root.frames().size(), "the suppressed one's frames are not in it");
        assertEquals("java.util.Objects", root.frames().get(0).cls(), "the module name is dropped");
        assertFalse(root.frames().get(0).own());
        Exceptions.Frame where = root.where();
        assertEquals("com.acme.shop.PriceCalculator", where.cls());
        assertEquals("total", where.method());
        assertEquals("PriceCalculator.java", where.file());
        assertEquals(42, where.line());
        assertEquals("PriceCalculator.total(PriceCalculator.java:42)", where.shortText());
        assertEquals("Could not place order 812", chain.get(1).message());
    }

    @Test
    void findsTheExceptionInTheLogLineWhenNoHeaderFollows() {
        List<Exceptions.Thrown> chain = Exceptions.parse(List.of(
                "10:02:13 WARN c.a.Io - write failed: java.io.IOException: Broken pipe",
                "\tat com.acme.Io.write(Io.java:12)"));
        assertEquals(1, chain.size());
        assertEquals("java.io.IOException", chain.get(0).type());
        assertEquals("Broken pipe", chain.get(0).message());
        // a message over several lines
        chain = Exceptions.parse(List.of("ERROR boom", "java.lang.IllegalArgumentException: first line", "second line",
                "\tat com.acme.A.b(A.java:1)"));
        assertEquals("first line\nsecond line", chain.get(0).message());
        // an uncaught one
        chain = Exceptions.parse(List.of("Exception in thread \"main\" java.lang.RuntimeException: x",
                "\tat com.acme.Main.main(Main.java:5)"));
        assertEquals("java.lang.RuntimeException", chain.get(0).type());
    }

    @Test
    void parsesChainedPythonTracebacks() {
        List<String> lines = List.of(
                "2026-10-21 10:02:13,120 ERROR orders.api: payment failed",
                "Traceback (most recent call last):",
                "  File \"/app/orders/payment.py\", line 88, in charge",
                "    resp = client.post(url, json=body)",
                "  File \"/usr/lib/python3.11/site-packages/requests/api.py\", line 115, in post",
                "    return request(\"post\", url, data=data, json=json, **kwargs)",
                "requests.exceptions.ConnectionError: HTTPSConnectionPool(host='bank', port=443): Max retries",
                "",
                "During handling of the above exception, another exception occurred:",
                "",
                "Traceback (most recent call last):",
                "  File \"/app/orders/api.py\", line 41, in create",
                "    payment.charge(order)",
                "  File \"/app/orders/payment.py\", line 92, in charge",
                "    raise PaymentError(\"bank unreachable\") from None",
                "orders.payment.PaymentError: bank unreachable");
        List<Exceptions.Thrown> chain = Exceptions.parse(lines);
        assertEquals(List.of("orders.payment.PaymentError", "requests.exceptions.ConnectionError"),
                chain.stream().map(Exceptions.Thrown::type).toList(), "the outermost first");
        Exceptions.Thrown root = chain.get(1);
        assertEquals("HTTPSConnectionPool(host='bank', port=443): Max retries", root.message());
        assertFalse(root.frames().get(0).own(), "site-packages");
        assertEquals("payment.py:88 in charge", root.where().shortText());
        assertEquals("/app/orders/payment.py", root.where().file());
        assertEquals("payment.py:92 in charge", chain.get(0).where().shortText(), "where it was raised");
    }

    @Test
    void tellsOwnCodeFromFrameworks() {
        assertTrue(Exceptions.isOwnJava("com.acme.shop.OrderService"));
        assertFalse(Exceptions.isOwnJava("org.springframework.web.servlet.DispatcherServlet"));
        assertFalse(Exceptions.isOwnJava("com.acme.shop.OrderService$$SpringCGLIB$$0"));
        assertFalse(Exceptions.isOwnJava("jdk.internal.reflect.DirectMethodHandleAccessor"));
        assertTrue(Exceptions.isOwnPython("/app/orders/api.py"));
        assertTrue(Exceptions.isOwnPython("C:\\work\\shop\\orders\\api.py"));
        assertFalse(Exceptions.isOwnPython("/usr/lib/python3.11/json/decoder.py"));
        assertFalse(Exceptions.isOwnPython("/home/me/shop/.venv/lib/python3.12/site-packages/flask/app.py"));
        assertFalse(Exceptions.isOwnPython("<frozen importlib._bootstrap>"));
    }

    @Test
    void groupsByRootCauseAndPlace() {
        List<String> log = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            log.add("2026-10-21 10:02:" + String.format("%02d", i) + ".000 ERROR [w] c.a.Api - failed " + i);
            if (i % 3 == 0) { // the same NPE, wrapped differently, with another message
                log.add("java.lang.IllegalStateException: wrapped " + i);
                log.add("\tat com.acme.shop.OrderService.place(OrderService.java:142)");
                log.add("Caused by: java.lang.NullPointerException: order " + i + " is null");
                log.add("\tat com.acme.shop.PriceCalculator.total(PriceCalculator.java:42)");
                log.add("\tat com.acme.shop.OrderService.place(OrderService.java:139)");
                log.add("\t... 1 more");
            } else if (i % 3 == 1) { // the same NPE, not wrapped
                log.add("java.lang.NullPointerException: order " + i + " is null");
                log.add("\tat com.acme.shop.PriceCalculator.total(PriceCalculator.java:42)");
                log.add("\tat com.acme.shop.OrderService.place(OrderService.java:139)");
            } else { // the same type from another place
                log.add("java.lang.NullPointerException");
                log.add("\tat com.acme.shop.Mailer.send(Mailer.java:12)");
            }
            log.add("2026-10-21 10:02:" + String.format("%02d", i) + ".500 INFO  [w] c.a.Api - next");
        }
        Exceptions.Result r = analyze(log);
        assertEquals(30, r.exceptions());
        assertEquals(2, r.groups().size(), "" + r.groups().stream().map(Exceptions.Group::key).toList());
        Exceptions.Group npe = r.groups().get(0);
        assertEquals(20, npe.count());
        assertEquals("java.lang.NullPointerException", npe.root().type());
        assertEquals("PriceCalculator.total(PriceCalculator.java:42)", npe.root().where().shortText());
        assertEquals(List.of("IllegalStateException"), npe.wrappers());
        assertEquals(20, npe.messages());
        assertEquals(0, npe.firstLine(), "the log line the exception belongs to");
        assertEquals(20, npe.keptLines().length);
        Exceptions.Group mail = r.groups().get(1);
        assertEquals(10, mail.count());
        assertEquals("Mailer.send(Mailer.java:12)", mail.root().where().shortText());
        assertEquals(List.of(), mail.wrappers());
    }

    @Test
    void entriesCloseTogetherAreCountedApart() {
        List<String> log = List.of(
                "2026-10-21 10:00:00.000 ERROR [w] c.a.A - one",
                "java.lang.IllegalStateException: a",
                "\tat com.acme.A.a(A.java:1)",
                "2026-10-21 10:00:00.001 ERROR [w] c.a.A - two",
                "java.lang.IllegalArgumentException: b",
                "\tat com.acme.B.b(B.java:2)",
                "2026-10-21 10:00:00.002 INFO  [w] c.a.A - fine");
        Exceptions.Result r = analyze(log);
        assertEquals(2, r.exceptions());
        assertEquals(List.of(0L, 3L), r.groups().stream().map(Exceptions.Group::firstLine).sorted().toList());
    }

    @Test
    void logsWithoutTimesWork() {
        List<String> log = List.of("ERROR failed", "java.lang.IllegalStateException: a", "\tat com.acme.A.a(A.java:1)",
                "INFO next");
        Exceptions.Result r = Exceptions.analyze(stackLines(log), stackLines(log).length, l -> l < log.size()
                ? log.get((int) l) : null, null, () -> false, null);
        assertEquals(1, r.exceptions());
        assertEquals(0, r.groups().get(0).firstLine());
        assertArrayEquals(new long[] {0}, r.groups().get(0).keptLines());
    }

    @Test
    void noStacksNoGroups() {
        assertEquals(0, analyze(List.of("2026-10-21 10:00:00.000 INFO all good", "  at the office")).exceptions());
        assertNull(Exceptions.analyze(new long[] {1}, 1, l -> "x", null, () -> true, null), "cancelled");
    }

    private static long[] stackLines(List<String> log) {
        Pattern p = Pattern.compile(Exceptions.STACK_LINES);
        return java.util.stream.IntStream.range(0, log.size()).filter(i -> p.matcher(log.get(i)).find())
                .mapToLong(i -> i).toArray();
    }

    private static Exceptions.Result analyze(List<String> log) {
        EntryFormat ef = EntryFormat.detect(log);
        long[] lines = stackLines(log);
        return Exceptions.analyze(lines, lines.length, l -> l < log.size() ? log.get((int) l) : null,
                t -> ef.startsEntry(t), () -> false, null);
    }
}
