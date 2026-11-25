package logsift.agent;

/**
 * A busy little JVM for record_profile's test, apart from the fixture other tests look for by
 * name. Prints "ready", spins until its input closes.
 */
public final class RecordingApp {
    private RecordingApp() {}

    static volatile long sink;

    public static void main(String[] args) throws Exception {
        Thread busy = new Thread(() -> {
            long x = 0;
            while (true) {
                x += Long.numberOfTrailingZeros(x + System.nanoTime());
                sink = x;
            }
        }, "recording-busy");
        busy.setDaemon(true);
        busy.start();
        System.out.println("ready");
        System.out.flush();
        while (System.in.read() >= 0) {
            // until the test closes our input
        }
    }

    /** Starts it; {@link logsift.jvm.FixtureApp#stop} ends it. */
    static Process start() throws Exception {
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Process p = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", exe).toString(),
                "-Xmx64m", "-cp", System.getProperty("java.class.path"), RecordingApp.class.getName())
                .redirectErrorStream(true).start();
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null && !line.equals("ready")) {
            // JVM warnings
        }
        if (!"ready".equals(line)) {
            p.destroyForcibly();
            throw new IllegalStateException("the recording JVM did not start");
        }
        return p;
    }
}
