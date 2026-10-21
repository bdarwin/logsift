package logsift.tools;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Writes realistic, reproducible application logs (logback style) for testing.
 *
 * <pre>
 *   LogGenerator out.log 500                 # 500 MB
 *   LogGenerator out.log 0 --follow=2000     # keep appending 2000 lines/s (tail demo)
 *   LogGenerator out.log 100 --crlf          # Windows line endings
 * </pre>
 */
public final class LogGenerator {

    /** What was written. {@code linesContaining} counts lines that contain each token. */
    public record Stats(long bytes, long lines, Map<String, Long> linesContaining) {}

    public static final List<String> DEFAULT_TOKENS =
            List.of("ERROR", "WARN", "Exception", "user=u1234 ", "Müller");

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String[] THREADS = {"main", "http-nio-8080-exec-1", "http-nio-8080-exec-2",
        "http-nio-8080-exec-7", "scheduler-1", "kafka-consumer-3", "pool-4-thread-12", "async-9"};
    private static final String[] LOGGERS = {"c.a.order.OrderService", "c.a.payment.PaymentGateway",
        "c.a.user.SessionManager", "c.a.inventory.StockSync", "o.h.engine.jdbc.SqlStatement",
        "c.a.web.RequestFilter", "c.a.cache.RedisCache", "c.a.mail.Notifier"};
    private static final String[] CUSTOMERS = {"Smith", "Müller", "García", "Ødegaard", "Tanaka 田中",
        "Nguyễn", "O'Brien", "Kowalski"};

    private final Random rnd;
    private final List<String> tokens;
    private final String eol;
    private LocalDateTime time = LocalDateTime.of(2026, 10, 21, 0, 0, 0);
    private long orderId = 100_000;

    public LogGenerator(long seed, List<String> tokens, boolean crlf) {
        this.rnd = new Random(seed);
        this.tokens = tokens;
        this.eol = crlf ? "\r\n" : "\n";
    }

    /** Writes about {@code targetBytes} (always whole log entries) and returns what was written. */
    public static Stats generate(Path out, long targetBytes, long seed) throws IOException {
        return new LogGenerator(seed, DEFAULT_TOKENS, false).write(out, targetBytes, false);
    }

    public Stats write(Path out, long targetBytes, boolean append) throws IOException {
        long[] counts = new long[tokens.size()];
        long bytes = 0;
        long lines = 0;
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING),
                1 << 20)) {
            StringBuilder entry = new StringBuilder(512);
            while (bytes < targetBytes) {
                entry.setLength(0);
                lines += nextEntry(entry, counts);
                byte[] b = entry.toString().getBytes(StandardCharsets.UTF_8);
                os.write(b);
                bytes += b.length;
            }
        }
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            m.put(tokens.get(i), counts[i]);
        }
        return new Stats(bytes, lines, m);
    }

    /** Appends one log entry (one line, or several for a stack trace). Returns lines added. */
    int nextEntry(StringBuilder sb, long[] counts) {
        time = time.plusNanos((1 + rnd.nextInt(40)) * 1_000_000L);
        int r = rnd.nextInt(1000);
        String level = r < 50 ? "TRACE" : r < 330 ? "DEBUG" : r < 880 ? "INFO" : r < 955 ? "WARN"
                : r < 997 ? "ERROR" : "FATAL";
        String thread = THREADS[rnd.nextInt(THREADS.length)];
        String logger = LOGGERS[rnd.nextInt(LOGGERS.length)];
        String head = TS.format(time) + " " + pad(level) + " [" + thread + "] " + logger + " - ";
        int lines = 0;
        lines += line(sb, head + message(level), counts);
        if ((level.equals("ERROR") || level.equals("FATAL")) && rnd.nextInt(3) == 0) {
            lines += stackTrace(sb, counts);
        }
        return lines;
    }

    private String message(String level) {
        String user = "user=u" + (1000 + rnd.nextInt(9000)) + " ";
        return switch (level) {
            case "ERROR", "FATAL" -> switch (rnd.nextInt(4)) {
                case 0 -> "Payment failed for order " + orderId++ + " " + user
                        + "reason=CARD_DECLINED";
                case 1 -> "Unhandled exception in request /api/orders/" + orderId++;
                case 2 -> "Database connection lost after " + rnd.nextInt(30000) + " ms";
                default -> "Could not send mail to customer " + customer();
            };
            case "WARN" -> switch (rnd.nextInt(3)) {
                case 0 -> "Slow query took " + (500 + rnd.nextInt(5000)) + " ms: SELECT * FROM orders WHERE id=?";
                case 1 -> "Retrying request (attempt " + (1 + rnd.nextInt(5)) + "/5) " + user;
                default -> "Cache miss rate high: " + rnd.nextInt(100) + "%";
            };
            case "INFO" -> switch (rnd.nextInt(4)) {
                case 0 -> "Order " + orderId++ + " created for " + user + "amount="
                        + rnd.nextInt(500) + "." + rnd.nextInt(10) + rnd.nextInt(10) + " EUR";
                case 1 -> "GET /api/products/" + rnd.nextInt(10000) + " 200 " + rnd.nextInt(300) + "ms";
                case 2 -> "Customer " + customer() + " logged in " + user;
                default -> "Stock synced: " + rnd.nextInt(5000) + " items updated";
            };
            default -> "state=" + Long.toHexString(rnd.nextLong()) + " step=" + rnd.nextInt(100)
                    + " " + user + "payload={\"id\":" + rnd.nextInt(100000) + ",\"ok\":true}";
        };
    }

    private String customer() {
        return CUSTOMERS[rnd.nextInt(CUSTOMERS.length)];
    }

    private int stackTrace(StringBuilder sb, long[] counts) {
        String[] ex = {"java.lang.IllegalStateException: Order already closed",
            "java.sql.SQLTransientConnectionException: Connection is not available",
            "java.net.SocketTimeoutException: Read timed out",
            "java.lang.NullPointerException: Cannot invoke \"Order.getId()\" because \"order\" is null"};
        int lines = line(sb, ex[rnd.nextInt(ex.length)], counts);
        int frames = 4 + rnd.nextInt(12);
        for (int i = 0; i < frames; i++) {
            String cls = LOGGERS[rnd.nextInt(LOGGERS.length)].replace("c.a.", "com.acme.");
            lines += line(sb, "\tat " + cls + ".handle" + rnd.nextInt(20) + "(" + simple(cls)
                    + ".java:" + (10 + rnd.nextInt(900)) + ")", counts);
        }
        if (rnd.nextBoolean()) {
            lines += line(sb, "Caused by: java.io.IOException: Broken pipe", counts);
            lines += line(sb, "\t... " + (5 + rnd.nextInt(40)) + " more", counts);
        }
        return lines;
    }

    private static String simple(String cls) {
        return cls.substring(cls.lastIndexOf('.') + 1);
    }

    private int line(StringBuilder sb, String text, long[] counts) {
        for (int i = 0; i < tokens.size(); i++) {
            if (text.contains(tokens.get(i))) {
                counts[i]++;
            }
        }
        sb.append(text).append(eol);
        return 1;
    }

    private static String pad(String level) {
        return level.length() == 5 ? level : level + " ";
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LogGenerator <file> <sizeMb> [--follow=linesPerSec] [--crlf] [--seed=N]");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        long sizeMb = Long.parseLong(args[1]);
        int follow = 0;
        boolean crlf = false;
        long seed = 42;
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--follow=")) {
                follow = Integer.parseInt(args[i].substring(9));
            } else if (args[i].equals("--crlf")) {
                crlf = true;
            } else if (args[i].startsWith("--seed=")) {
                seed = Long.parseLong(args[i].substring(7));
            }
        }
        LogGenerator g = new LogGenerator(seed, DEFAULT_TOKENS, crlf);
        long t0 = System.nanoTime();
        Stats s = g.write(out, sizeMb * 1024 * 1024, false);
        System.out.printf("Wrote %s: %,d bytes, %,d lines in %d ms%n", out, s.bytes(), s.lines(),
                (System.nanoTime() - t0) / 1_000_000);
        s.linesContaining().forEach((k, v) -> System.out.printf("  lines containing \"%s\": %,d%n", k, v));
        if (follow > 0) {
            System.out.printf("Appending about %d lines/s. Press Ctrl+C to stop.%n", follow);
            g.follow(out, follow);
        }
    }

    /** Appends entries forever at roughly {@code linesPerSecond}. */
    void follow(Path out, int linesPerSecond) throws IOException, InterruptedException {
        long[] counts = new long[tokens.size()];
        StringBuilder sb = new StringBuilder();
        int perTick = Math.max(1, linesPerSecond / 10);
        while (true) {
            sb.setLength(0);
            int n = 0;
            while (n < perTick) {
                n += nextEntry(sb, counts);
            }
            Files.writeString(out, sb, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            Thread.sleep(100);
        }
    }
}
