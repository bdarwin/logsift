package logsift.jvm;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import jdk.management.jfr.FlightRecorderMXBean;

/**
 * A JVM reached through its JMX port ({@code -Dcom.sun.management.jmxremote.port=9010}), from
 * anywhere: no SSH and no JDK tools needed. Takes thread dumps and class histograms (the JVM's
 * diagnostic commands), and records with Flight Recorder, the recording streamed back over the
 * same connection. Heap dumps are written on the JVM's machine (they can't come back over JMX), to
 * be copied over SSH.
 */
public final class JmxJvms {
    private JmxJvms() {
    }

    /**
     * Where the JMX port is.
     *
     * @param host a host name, or a whole {@code service:jmx:…} URL (then {@code port} is ignored)
     * @param user the JMX user, or "" when the port has no passwords
     */
    public record Address(String host, int port, String user, String password, boolean ssl) {
        public Address {
            host = host == null ? "" : host.trim();
            user = user == null ? "" : user.trim();
        }

        /** "server:9010" */
        public String label() {
            return host.startsWith("service:jmx:") ? host : host + ":" + port;
        }

        JMXServiceURL url() throws IOException {
            if (host.startsWith("service:jmx:")) {
                return new JMXServiceURL(host);
            }
            String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host; // IPv6
            return new JMXServiceURL("service:jmx:rmi:///jndi/rmi://" + h + ":" + port + "/jmxrmi");
        }
    }

    /** Connects (giving up after {@code timeoutSeconds}). */
    public static Session connect(Address a, int timeoutSeconds) throws IOException {
        Map<String, Object> env = new HashMap<>();
        if (!a.user().isEmpty()) {
            env.put(JMXConnector.CREDENTIALS, new String[] {a.user(), a.password() == null ? "" : a.password()});
        }
        if (a.ssl()) {
            env.put("com.sun.jndi.rmi.factory.socket", new javax.rmi.ssl.SslRMIClientSocketFactory());
        }
        JMXServiceURL url = a.url();
        FutureTask<JMXConnector> f = new FutureTask<>(() -> JMXConnectorFactory.connect(url, env));
        Thread t = new Thread(f, "logsift-jmx-connect");
        t.setDaemon(true);
        t.start();
        JMXConnector c;
        try {
            c = f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new IOException(a.label() + " did not answer in " + timeoutSeconds + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (ExecutionException e) {
            throw new IOException(describe(e.getCause()), e.getCause());
        }
        try {
            return new Session(a, c);
        } catch (IOException | RuntimeException e) {
            try {
                c.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            throw e;
        }
    }

    /** A short reason why connecting failed. */
    static String describe(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SecurityException) {
                return "the JMX port refused the user or password";
            }
            if (t instanceof java.net.ConnectException) {
                return "nothing answers on that port (is the JVM started with -Dcom.sun.management.jmxremote.port?)";
            }
            if (t instanceof java.net.UnknownHostException) {
                return "unknown host " + t.getMessage();
            }
            if (t instanceof javax.net.ssl.SSLException) {
                return "SSL failed: " + t.getMessage();
            }
        }
        return e == null ? "unknown error" : e.getMessage() == null ? e.toString() : e.getMessage();
    }

    /** One JMX connection. */
    public static final class Session implements AutoCloseable {
        private final Address address;
        private final JMXConnector jmx;
        private final MBeanServerConnection mbeans;
        private final JvmProcess process;
        private final String tmpDir;
        private final String separator;
        private volatile boolean closed;

        private Session(Address address, JMXConnector jmx) throws IOException {
            this.address = address;
            this.jmx = jmx;
            this.mbeans = jmx.getMBeanServerConnection();
            RuntimeMXBean rt = ManagementFactory.newPlatformMXBeanProxy(mbeans,
                    ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
            Map<String, String> props = rt.getSystemProperties();
            String command = props.getOrDefault("sun.java.command", "");
            String name = command.isBlank() ? rt.getName() : JvmProcess.describe("java " + command);
            this.process = new JvmProcess(rt.getPid(), name, command, props.getOrDefault("user.name", ""),
                    ThreadAnalysis.duration(rt.getUptime()), false);
            this.tmpDir = props.getOrDefault("java.io.tmpdir", "/tmp");
            this.separator = props.getOrDefault("file.separator", "/");
        }

        /** Whether the JVM runs on this computer (its JMX port is on this machine). */
        public boolean isLocal() {
            String h = machine();
            return h != null && isThisMachine(h);
        }

        /** The name or address of the JVM's machine (also from a service:jmx: URL), or null. */
        public String machine() {
            return machineOf(address.host());
        }

        /**
         * Writes a heap dump on the JVM's machine, in its temporary folder (it can't come back over
         * JMX), and says where.
         *
         * @param live only objects in use (a full GC runs first)
         */
        public String heapDumpThere(boolean live) throws IOException {
            String dir = tmpDir.endsWith(separator) ? tmpDir : tmpDir + separator;
            String path = dir + "logsift-heap-" + process.pid() + "-" + System.currentTimeMillis() + ".hprof";
            dumpTo(path, live);
            return path;
        }

        /** Writes a heap dump to a path on the JVM's machine. */
        public void dumpTo(String path, boolean live) throws IOException {
            if (closed) {
                throw new IOException("closed");
            }
            String out = live ? LocalJvms.invoke(mbeans, "GC.heap_dump", path)
                    : LocalJvms.invoke(mbeans, "GC.heap_dump", "-all", path);
            if (out != null && (out.contains("rror") || out.contains("xception")) && !out.contains("Heap dump file created")) {
                throw new IOException("the JVM could not write the heap dump: " + LocalJvms.first(out));
            }
        }

        public Address address() {
            return address;
        }

        /** The JVM: its process id, what it runs, its user and uptime (as it says itself). */
        public JvmProcess process() {
            return process;
        }

        /** "orders.jar (1234) on server:9010" */
        public String label() {
            return process.name() + " (" + process.pid() + ") on " + address.label();
        }

        public boolean isConnected() {
            if (closed) {
                return false;
            }
            try {
                mbeans.getMBeanCount();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        public ThreadDump dump() throws IOException {
            String text = diagnostic("Thread.print", "-l");
            java.util.List<ThreadDump> d = DumpParser.parse(text);
            if (d.isEmpty()) {
                throw new IOException("the JVM's answer was not a thread dump: " + LocalJvms.first(text));
            }
            return new ThreadDump(System.currentTimeMillis(), d.get(0).vm(), d.get(0).threads(), -1);
        }

        /** A diagnostic command by its jcmd name; heap dumps are refused (the file would stay there). */
        public String diagnostic(String command, String... args) throws IOException {
            if (closed) {
                throw new IOException("closed");
            }
            if (command.equals("GC.heap_dump")) {
                throw new IOException(HEAP_DUMP);
            }
            return LocalJvms.invoke(mbeans, command, args);
        }

        /**
         * Records with Flight Recorder for {@code seconds} (its "profile" settings) and copies
         * the recording to {@code local}. False if cancelled.
         */
        public boolean record(int seconds, Path local, BooleanSupplier cancelled) throws IOException {
            FlightRecorderMXBean jfr;
            try {
                jfr = JMX.newMXBeanProxy(mbeans, new ObjectName("jdk.management.jfr:type=FlightRecorder"),
                        FlightRecorderMXBean.class);
            } catch (javax.management.MalformedObjectNameException e) {
                throw new IOException(e);
            }
            long id;
            try {
                id = jfr.newRecording();
            } catch (RuntimeException e) {
                throw new IOException("this JVM has no Flight Recorder over JMX: " + describe(e), e);
            }
            try {
                jfr.setPredefinedConfiguration(id, "profile");
                jfr.setRecordingOptions(id, Map.of("name", "logsift-" + System.nanoTime(), "disk", "true"));
                jfr.startRecording(id);
                long end = System.currentTimeMillis() + seconds * 1000L;
                while (System.currentTimeMillis() < end) {
                    if (cancelled.getAsBoolean()) {
                        jfr.stopRecording(id);
                        return false;
                    }
                    try {
                        Thread.sleep(Math.min(200, Math.max(1, end - System.currentTimeMillis())));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                jfr.stopRecording(id);
                long stream = jfr.openStream(id, Map.of("blockSize", Integer.toString(1 << 20)));
                try (OutputStream out = Files.newOutputStream(local)) {
                    for (byte[] b = jfr.readStream(stream); b != null; b = jfr.readStream(stream)) {
                        out.write(b);
                        if (cancelled.getAsBoolean()) {
                            return false;
                        }
                    }
                } finally {
                    jfr.closeStream(stream);
                }
                return true;
            } catch (RuntimeException e) {
                throw new IOException("Flight Recorder: " + describe(e), e);
            } finally {
                try {
                    jfr.closeRecording(id);
                } catch (RuntimeException e) {
                    // gone with the connection
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            try {
                jmx.close();
            } catch (IOException e) {
                // gone already
            }
        }
    }

    /** The machine of a host or {@code service:jmx:} URL (the last host named in it), or null. */
    static String machineOf(String host) {
        if (!host.startsWith("service:jmx:")) {
            return host;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("//([^:/\\[\\]]+|\\[[^\\]]+\\])[:/]").matcher(host);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last == null ? null : last.replace("[", "").replace("]", "");
    }

    /** Whether a host name or address is this computer. */
    static boolean isThisMachine(String host) {
        try {
            if (host.isEmpty() || host.equalsIgnoreCase("localhost")) {
                return true;
            }
            java.net.InetAddress a = java.net.InetAddress.getByName(host);
            return a.isLoopbackAddress() || a.isAnyLocalAddress() || java.net.NetworkInterface.getByInetAddress(a) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A heap dump written on the JVM's own machine (over JMX it can't be sent back): where it is,
     * so it can be copied over SSH.
     */
    public static final class DumpOnServer extends IOException {
        private final String host;
        private final String path;

        public DumpOnServer(String host, String path) {
            super("The heap dump is on " + host + " at " + path);
            this.host = host;
            this.path = path;
        }

        public String host() {
            return host;
        }

        public String path() {
            return path;
        }
    }

    static final String HEAP_DUMP = "Use Save Heap Dump: over a JMX port the JVM writes the dump on its own machine,"
            + " and Logsift copies it here over SSH.";
}
