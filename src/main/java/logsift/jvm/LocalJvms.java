package logsift.jvm;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * JVMs running on this computer (as the same user): listed and attached to with the JDK's attach
 * mechanism, then read through JMX. Thread dumps come from the JVM's own diagnostic command (the
 * same text as {@code jcmd <pid> Thread.print -l}). If attaching fails, {@code jcmd} from a JDK
 * is tried (the path set in Settings, {@code JAVA_HOME}, or the PATH).
 */
public final class LocalJvms {
    private LocalJvms() {
    }

    /** True if this Java can attach to other JVMs (the jdk.attach module is there). */
    public static boolean canAttach() {
        return ModuleLayer.boot().findModule("jdk.attach").isPresent();
    }

    /** JVMs running as this user, except Logsift itself, newest first. */
    public static List<JvmProcess> list() {
        List<JvmProcess> out = new ArrayList<>();
        long self = ProcessHandle.current().pid();
        if (canAttach()) {
            for (VirtualMachineDescriptor d : VirtualMachine.list()) {
                long pid;
                try {
                    pid = Long.parseLong(d.id());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid != self) {
                    out.add(process(pid, d.displayName()));
                }
            }
        }
        out.sort(Comparator.comparingLong(JvmProcess::pid).reversed());
        return out;
    }

    private static JvmProcess process(long pid, String display) {
        ProcessHandle.Info info = ProcessHandle.of(pid).map(ProcessHandle::info).orElse(null);
        String cmd = info == null ? "" : info.commandLine().orElse("");
        String name = display == null || display.isBlank() ? JvmProcess.describe(cmd) : display.split(" ")[0];
        if (name.endsWith(".jar")) {
            name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        }
        String user = info == null ? "" : info.user().orElse("");
        String up = info == null ? "" : info.startInstant()
                .map(s -> ThreadAnalysis.duration(Duration.between(s, Instant.now()).toMillis())).orElse("");
        return new JvmProcess(pid, name, cmd.isEmpty() ? display : cmd, user, up, false);
    }

    /** Takes thread dumps from one JVM; reuses its JMX connection. */
    public static final class Session implements AutoCloseable {
        private final long pid;
        private final JMXConnector jmx;
        private final MBeanServerConnection mbeans;
        private final Path jcmd;
        private volatile boolean closed;

        private Session(long pid, JMXConnector jmx, Path jcmd) throws IOException {
            this.pid = pid;
            this.jmx = jmx;
            this.mbeans = jmx == null ? null : jmx.getMBeanServerConnection();
            this.jcmd = jcmd;
        }

        public long pid() {
            return pid;
        }

        /** "jmx" or "jcmd": how dumps are taken. */
        public String how() {
            return jmx != null ? "attach" : "jcmd";
        }

        public ThreadDump dump() throws IOException {
            String text = diagnostic("Thread.print", "-l");
            List<ThreadDump> d = DumpParser.parse(text);
            if (d.isEmpty()) {
                throw new IOException("the JVM's answer was not a thread dump: " + first(text));
            }
            // the JVM's clock, to the millisecond (the dump says seconds)
            ThreadDump t = d.get(0);
            return new ThreadDump(System.currentTimeMillis(), t.vm(), t.threads(), -1);
        }

        /**
         * Runs a diagnostic command by its jcmd name ({@code Thread.print}, {@code JFR.start},
         * {@code GC.class_histogram}…) and returns its text.
         */
        public String diagnostic(String command, String... args) throws IOException {
            if (closed) {
                throw new IOException("closed");
            }
            if (mbeans != null) {
                try {
                    if (command.equals("GC.heap_dump")) {
                        // not offered as a diagnostic command over JMX; the HotSpot MBean does it
                        String file = java.util.Arrays.stream(args).filter(a -> !a.startsWith("-")).findFirst()
                                .orElseThrow(() -> new IOException("no file for the heap dump"));
                        boolean all = java.util.Arrays.asList(args).contains("-all");
                        mbeans.invoke(new ObjectName("com.sun.management:type=HotSpotDiagnostic"), "dumpHeap",
                                new Object[] {file, !all}, new String[] {String.class.getName(), boolean.class.getName()});
                        return "Heap dump file created";
                    }
                    ObjectName dc = new ObjectName("com.sun.management:type=DiagnosticCommand");
                    Object r;
                    try {
                        r = mbeans.invoke(dc, jmxName(command), new Object[] {args},
                                new String[] {String[].class.getName()});
                    } catch (javax.management.ReflectionException e) {
                        if (args.length > 0) {
                            throw e;
                        }
                        r = mbeans.invoke(dc, jmxName(command), new Object[0], new String[0]); // takes no arguments
                    }
                    return String.valueOf(r);
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
                }
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(jcmd.toString());
            cmd.add(String.valueOf(pid));
            cmd.add(command);
            cmd.addAll(List.of(args));
            return run(cmd, 60);
        }

        @Override
        public void close() {
            closed = true;
            if (jmx != null) {
                try {
                    jmx.close();
                } catch (IOException e) {
                    // gone already
                }
            }
        }
    }

    /** The diagnostic command MBean's name for a jcmd command: {@code GC.class_histogram} → {@code gcClassHistogram}. */
    static String jmxName(String command) {
        StringBuilder sb = new StringBuilder();
        boolean firstPart = true;
        for (String part : command.split("[._]")) {
            if (part.isEmpty()) {
                continue;
            }
            if (firstPart) {
                sb.append(part.toLowerCase(java.util.Locale.ROOT));
                firstPart = false;
            } else {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * Connects to a JVM: attaches and starts its local JMX agent; if that fails and a
     * {@code jcmd} can be found ({@code jdkHome} first), uses that instead.
     */
    public static Session attach(long pid, String jdkHome) throws IOException {
        IOException attachFailed = null;
        if (canAttach()) {
            try {
                return new Session(pid, connect(pid), null);
            } catch (IOException e) {
                attachFailed = e;
            } catch (Exception | LinkageError e) {
                attachFailed = new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }
        Path jcmd = findJcmd(jdkHome);
        if (jcmd != null) {
            Session s = new Session(pid, null, jcmd);
            s.dump(); // check it works
            return s;
        }
        throw new IOException(attachFailed != null ? "cannot attach: " + attachFailed.getMessage()
                : "this Logsift cannot attach to JVMs and no jcmd was found; set the JDK folder in Settings");
    }

    /** A session that uses {@code jcmd} (for tests, and when attaching isn't possible). */
    static Session viaJcmd(long pid, Path jcmd) throws IOException {
        return new Session(pid, null, jcmd);
    }

    private static JMXConnector connect(long pid) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(String.valueOf(pid));
        String address;
        try {
            address = vm.startLocalManagementAgent();
        } finally {
            vm.detach();
        }
        JMXConnector c = JMXConnectorFactory.connect(new JMXServiceURL(address), Map.of());
        try {
            c.getMBeanServerConnection().getMBeanCount();
            return c;
        } catch (IOException e) {
            c.close();
            throw e;
        }
    }

    /**
     * The {@code jcmd} of a JDK: in {@code jdkHome} (a JDK folder or its bin), then
     * {@code JAVA_HOME}, the Java running Logsift, and the PATH. Null if none.
     */
    public static Path findJcmd(String jdkHome) {
        String exe = isWindows() ? "jcmd.exe" : "jcmd";
        List<Path> places = new ArrayList<>();
        if (jdkHome != null && !jdkHome.isBlank()) {
            Path h = Path.of(jdkHome.trim());
            places.add(h.resolve("bin").resolve(exe));
            places.add(h.resolve(exe));
            if (h.getFileName() != null && h.getFileName().toString().equals(exe)) {
                places.add(h);
            }
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            places.add(Path.of(javaHome, "bin", exe));
        }
        places.add(Path.of(System.getProperty("java.home"), "bin", exe));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) {
                    places.add(Path.of(dir, exe));
                }
            }
        }
        for (Path p : places) {
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return p;
            }
        }
        return null;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** Runs a program and returns what it printed; fails if it fails. */
    static String run(List<String> cmd, int timeoutSeconds) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        byte[] out;
        try (var in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        try {
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(cmd.get(0) + " did not finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        String text = new String(out, java.nio.charset.StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IOException(first(text));
        }
        return text;
    }

    static String first(String text) {
        String t = text.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }

    /** Logsift's own process id (never offered). */
    public static long self() {
        return ManagementFactory.getRuntimeMXBean().getPid();
    }
}
