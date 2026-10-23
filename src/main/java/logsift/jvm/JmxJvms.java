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
 * same connection. Heap dumps would stay on the JVM's machine, so they are not offered.
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

    static final String HEAP_DUMP = "Over a JMX port the heap dump would be written on the JVM's own machine;"
            + " connect over SSH (or on that computer) to save one here.";
}
