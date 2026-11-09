package logsift.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Random;

/** The files the videos open: a shop's logs, tables and dumps, made the same way each time. */
final class TourData {
    private TourData() {}

    /** A shop's log with three kinds of exceptions; the most common one is a NullPointerException. */
    static void writeErrors(Path log) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.util.Random rnd = new java.util.Random(3);
        long t0 = java.time.LocalDateTime.of(2026, 10, 21, 10, 0, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        for (int i = 0; i < 3000; i++) {
            String time = TimelineBar.formatFull(t0 + i * 1200L).substring(0, 23);
            int kind = i < 900 ? -1 : i % 50 == 7 ? 0 : i % 160 == 11 ? 1 : i % 400 == 13 ? 2 : -1;
            if (kind < 0) {
                sb.append(time).append(" INFO  [http-nio-8080-exec-").append(i % 8 + 1)
                        .append("] c.a.shop.OrderController - order ").append(1000 + i).append(" placed in ")
                        .append(20 + rnd.nextInt(200)).append(" ms\n");
                continue;
            }
            sb.append(time).append(" ERROR [http-nio-8080-exec-").append(i % 8 + 1)
                    .append("] o.a.c.c.C.[dispatcherServlet] - Servlet.service() threw exception\n");
            if (kind == 0) {
                sb.append("org.springframework.web.util.NestedServletException: Request processing failed\n")
                        .append("\tat org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1014)\n")
                        .append("Caused by: java.lang.IllegalStateException: Could not place order ").append(1000 + i).append('\n')
                        .append("\tat com.acme.shop.OrderService.place(OrderService.java:142)\n")
                        .append("\tat com.acme.shop.OrderController.create(OrderController.java:57)\n")
                        .append("Caused by: java.lang.NullPointerException: Cannot invoke \"com.acme.shop.Coupon.percent()\" because \"coupon\" is null\n")
                        .append("\tat com.acme.shop.PriceCalculator.discount(PriceCalculator.java:88)\n")
                        .append("\tat com.acme.shop.PriceCalculator.total(PriceCalculator.java:42)\n")
                        .append("\tat com.acme.shop.OrderService.place(OrderService.java:139)\n");
            } else if (kind == 1) {
                sb.append("org.springframework.dao.DataAccessResourceFailureException: could not acquire connection\n")
                        .append("\tat com.acme.shop.OrderRepository.save(OrderRepository.java:31)\n")
                        .append("Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.\n")
                        .append("\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)\n")
                        .append("\tat com.acme.shop.OrderRepository.save(OrderRepository.java:31)\n");
            } else {
                sb.append("java.lang.ArithmeticException: / by zero\n")
                        .append("\tat com.acme.shop.Stats.average(Stats.java:19)\n")
                        .append("\tat com.acme.shop.ReportJob.run(ReportJob.java:44)\n");
            }
        }
        Files.writeString(log, sb.toString());
    }

    /** Requests handled by web threads and paid on a pool thread; r-1042 waits 8 s for the bank. Gives its first line. */
    static long writeRequests(Path log) throws Exception {
        java.util.List<String[]> events = new java.util.ArrayList<>();
        long t0 = java.time.LocalDateTime.of(2026, 10, 21, 10, 2, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli();
        java.util.Random rnd = new java.util.Random(7);
        for (int r = 0; r < 400; r++) {
            long st = t0 + r * 450L + rnd.nextInt(200);
            String id = "r-" + (1000 + r);
            String web = "http-nio-8080-exec-" + (r % 40 + 1);
            String pool = "payments-" + (r % 3 + 1);
            long pay = r == 42 ? 8_200 : 40 + rnd.nextInt(300);
            boolean declined = r == 42 || r % 37 == 0;
            Object[][] steps = {
                {0L, "INFO ", web, "c.a.shop.OrderController", "POST /orders requestId=" + id},
                {12L, "DEBUG", web, "c.a.shop.OrderRepository", "select * from orders where customer=?"},
                {30L, "INFO ", pool, "c.a.shop.PaymentClient", "charging card requestId=" + id},
                {30 + pay, declined ? "ERROR" : "INFO ", pool, "c.a.shop.PaymentClient",
                    (declined ? "card declined by bank" : "payment authorized") + " requestId=" + id},
                {40 + pay, declined ? "WARN " : "INFO ", web, "c.a.shop.OrderController",
                    (declined ? "402 Payment Required" : "201 Created") + " requestId=" + id}};
            for (Object[] s : steps) {
                long t = st + (Long) s[0];
                events.add(new String[] {Long.toString(t), TimelineBar.formatFull(t).substring(0, 23) + " " + s[1]
                        + " [" + s[2] + "] " + s[3] + " - " + s[4]});
            }
        }
        events.sort(java.util.Comparator.comparingLong(e -> Long.parseLong(e[0])));
        StringBuilder sb = new StringBuilder();
        long line = -1;
        for (int i = 0; i < events.size(); i++) {
            sb.append(events.get(i)[1]).append('\n');
            if (events.get(i)[1].endsWith("POST /orders requestId=r-1042")) {
                line = i;
            }
        }
        Files.writeString(log, sb.toString());
        return line;
    }

    /** Sales over three months: a region, a product, an amount, a quantity, when. */
    static void writeSales(Path csv) throws Exception {
        StringBuilder sb = new StringBuilder("region,product,amount,qty,sold_at\n");
        String[] regions = {"North", "South", "East", "West"};
        String[] products = {"Tea", "Coffee", "Cake"};
        Random rnd = new Random(11);
        for (int i = 0; i < 1200; i++) {
            String region = regions[rnd.nextInt(4)];
            String product = products[rnd.nextInt(3)];
            // the North sells more cake
            double amount = 5 + rnd.nextInt(200) / 4.0 + (region.equals("North") && product.equals("Cake") ? 30 : 0);
            sb.append(String.format(Locale.ROOT, "%s,%s,%.2f,%d,2026-%02d-%02d %02d:15:00%n", region, product, amount,
                    1 + rnd.nextInt(5), 1 + i % 3, 1 + i % 28, i % 24));
        }
        Files.writeString(csv, sb.toString());
    }

    /** Two exports of the same orders a day apart: some gone, some new, some changed. */
    static void writeOrders(Path monday, Path tuesday) throws Exception {
        Random r = new Random(8);
        StringBuilder a = new StringBuilder("id,customer,status,amount\n");
        StringBuilder b = new StringBuilder("id,customer,status,amount\n");
        String[] status = {"NEW", "PAID", "SHIPPED"};
        for (int i = 1; i <= 5000; i++) {
            String row = String.format(Locale.ROOT, "%d,customer-%d,%s,%.2f%n", i, r.nextInt(800), status[r.nextInt(3)],
                    r.nextDouble() * 500);
            a.append(row);
            if (i % 97 == 0) {
                continue;
            }
            b.append(i % 41 == 0 ? row.replace("NEW", "CANCELLED").replace("PAID", "REFUNDED") : row);
        }
        for (int i = 5001; i <= 5040; i++) {
            b.append(String.format(Locale.ROOT, "%d,customer-%d,NEW,%.2f%n", i, r.nextInt(800), r.nextDouble() * 500));
        }
        Files.writeString(monday, a);
        Files.writeString(tuesday, b);
    }

    /** A heap dump of a shop whose session cache leaks (made once; it takes a JVM of its own). */
    static Path heapDump(Path dir) throws Exception {
        Path hprof = dir.resolve("shop.hprof");
        if (!Files.exists(hprof)) {
            logsift.heap.LeakApp.run(hprof, dir.resolve("shop-histogram.txt"));
        }
        return hprof;
    }

    static Path gcLog(Path dir) throws Exception {
        Path gc = dir.resolve("orders-gc.log");
        try (var in = new java.util.zip.GZIPInputStream(TourData.class.getResourceAsStream("/gc/j17-g1-time.log.gz"))) {
            Files.write(gc, in.readAllBytes());
        }
        return gc;
    }

    /** A program with deadlocks and a busy thread, with a JMX port on 127.0.0.1, writing a Spring Boot log. */
    static Process jvmWithJmx(int port, Path log) throws Exception {
        Files.deleteIfExists(log);
        return logsift.jvm.FixtureApp.start(false, log, "-Dcom.sun.management.jmxremote.port=" + port,
                "-Dcom.sun.management.jmxremote.rmi.port=" + port, "-Dcom.sun.management.jmxremote.host=127.0.0.1",
                "-Djava.rmi.server.hostname=127.0.0.1", "-Dcom.sun.management.jmxremote.authenticate=false",
                "-Dcom.sun.management.jmxremote.ssl=false");
    }
}
