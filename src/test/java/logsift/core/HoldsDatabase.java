package logsift.core;

/**
 * Another program with a DuckDB file open, for tests: it opens the file, prints "open", and keeps
 * it open until its input ends.
 */
final class HoldsDatabase {
    private HoldsDatabase() {
    }

    @SuppressWarnings("try") // held, not used
    public static void main(String[] args) throws Exception {
        try (io.quackjvm.core.Quack q = io.quackjvm.core.Quack.open(args[0])) {
            System.out.println("open");
            System.out.flush();
            while (System.in.read() >= 0) {
                // until the test closes our input
            }
        }
    }

    /** Starts one on {@code db} and waits until it has the file open. */
    static Process start(java.nio.file.Path db) throws Exception {
        String launcher = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(launcher, "-cp", System.getProperty("java.class.path"), HoldsDatabase.class.getName(),
                db.toString()).redirectErrorStream(true).start();
        java.io.BufferedReader out = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        String line = out.readLine();
        if (!"open".equals(line)) {
            p.destroyForcibly();
            throw new IllegalStateException("the other program didn't open " + db + ": " + line);
        }
        return p;
    }

    /** Lets it close the file and end. */
    static void stop(Process p) throws Exception {
        p.getOutputStream().close();
        if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
        }
    }
}
