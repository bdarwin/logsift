package logsift.jvm;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A program for tests to attach to (in its own JVM): two deadlocks, five threads queueing for a
 * lock held by a sleeping thread, an idle pool and a thread spinning on the CPU. Prints "ready"
 * and ends when its input is closed.
 */
public final class FixtureApp {
    static final Object A = new Object();
    static final Object B = new Object();
    static final Object DB = new Object();
    static final ReentrantLock L1 = new ReentrantLock();
    static final ReentrantLock L2 = new ReentrantLock();
    static volatile long sink;

    private FixtureApp() {
    }

    /** Starts this program in a JVM of its own and waits until it is ready (for tests). */
    public static Process start() throws Exception {
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        java.nio.file.Path javaExe = java.nio.file.Path.of(System.getProperty("java.home"), "bin", exe);
        Process p = new ProcessBuilder(javaExe.toString(), "-cp", System.getProperty("java.class.path"),
                "-Xmx64m", FixtureApp.class.getName()).redirectErrorStream(true).start();
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null && !line.equals("ready")) {
            // JVM warnings
        }
        if (!"ready".equals(line)) {
            p.destroyForcibly();
            throw new IllegalStateException("the fixture JVM did not start");
        }
        return p;
    }

    /** Ends a fixture started with {@link #start}. */
    public static void stop(Process p) throws Exception {
        p.getOutputStream().close();
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void start(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    public static void main(String[] args) throws Exception {
        start("worker-A", () -> {
            synchronized (A) {
                sleep(300);
                synchronized (B) {
                    sleep(1);
                }
            }
        });
        start("worker-B", () -> {
            synchronized (B) {
                sleep(300);
                synchronized (A) {
                    sleep(1);
                }
            }
        });
        start("lock-1", () -> {
            L1.lock();
            sleep(300);
            L2.lock();
        });
        start("lock-2", () -> {
            L2.lock();
            sleep(300);
            L1.lock();
        });
        start("db-writer", () -> {
            synchronized (DB) {
                sleep(10_000_000);
            }
        });
        sleep(100);
        for (int i = 1; i <= 5; i++) {
            start("http-nio-8080-exec-" + i, () -> {
                synchronized (DB) {
                    sink++;
                }
            });
        }
        ExecutorService idle = Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < 3; i++) {
            idle.submit(() -> { });
        }
        start("hot-loop", () -> {
            while (true) {
                sink += System.nanoTime() % 7;
            }
        });
        sleep(400);
        System.out.println("ready");
        System.out.flush();
        // until the test closes our input (or goes away)
        while (System.in.read() >= 0) {
            // keep going
        }
        System.exit(0);
    }
}
