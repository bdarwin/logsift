package logsift.jvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Profiles a running JVM for some seconds: with async-profiler (Linux and macOS JVMs: CPU, wall
 * clock, allocations, live objects, locks, native memory and its leaks, native locks, calls of a
 * method and slow calls, hardware events, or all at once), or with the JVM's own Flight Recorder
 * (any JVM with JFR, Windows too). The result is one {@link Profile} per thing measured. Also
 * reads what fills the heap (class histograms) and saves heap dumps.
 */
public final class Profiler {
    private Profiler() {
    }

    /** What to measure. */
    public enum Mode {
        CPU("CPU", "Where the threads use the CPU", "samples"),
        WALL("Wall clock", "Where the threads spend their time, running or waiting", "samples"),
        ALLOC("Allocations", "Where memory is allocated, and for what", "bytes"),
        LIVE("Live objects (memory leaks)", "Objects allocated while profiling that are still alive at its end:"
                + " what makes the heap grow, and where", "bytes"),
        LOCK("Locks", "Where threads wait to get locks", "ns"),
        NATIVE_MEMORY("Native memory", "Where native memory (malloc, direct buffers) is allocated", "bytes"),
        NATIVE_LEAKS("Native memory leaks", "Native memory allocated while profiling and never freed", "bytes"),
        NATIVE_LOCKS("Native locks", "Where threads wait for native (pthread) locks", "ns"),
        METHOD_CALLS("Calls of a method", "Every call of a method, and where it comes from", "calls"),
        METHOD_LATENCY("Slow calls of a method", "Calls of a method that take longer than a time, and their total"
                + " time", "ns"),
        HARDWARE("Hardware event", "CPU cache misses, page faults, context switches and the like (Linux)", "events"),
        ALL("Everything at once", "CPU, wall clock, allocations, live objects, locks and native memory in one run",
                "samples");

        public final String label;
        public final String description;
        final String unit;

        Mode(String label, String description, String unit) {
            this.label = label;
            this.description = description;
            this.unit = unit;
        }
    }

    public enum Engine {
        ASYNC_PROFILER("async-profiler"), JFR("Flight Recorder");

        public final String label;

        Engine(String label) {
            this.label = label;
        }
    }

    /**
     * One profiling run.
     *
     * @param method    for the method modes: {@code com.acme.Api.handle}, {@code java.io.FileInputStream.read*}
     * @param latencyMs for slow calls: the time a call must take at least
     * @param event     for a hardware event: its name ({@code cache-misses})
     * @param perThread a flame graph per thread (each thread's name on top)
     */
    public record Request(Engine engine, Mode mode, int seconds, boolean perThread, String method, int latencyMs,
                          String event) {
        public static Request of(Engine engine, Mode mode, int seconds) {
            return new Request(engine, mode, seconds, false, "", 0, "");
        }

        /** Why this can't run, or null. */
        public String problem() {
            if ((mode == Mode.METHOD_CALLS || mode == Mode.METHOD_LATENCY)
                    && (method == null || !METHOD.matcher(method.trim()).matches())) {
                return "Give the method as package.Class.method (a * matches anything), for example"
                        + " com.acme.orders.Api.handle";
            }
            if (mode == Mode.HARDWARE && (event == null || !EVENT.matcher(event.trim()).matches())) {
                return "Choose a hardware event";
            }
            if (engine == Engine.JFR && !JFR_MODES.contains(mode)) {
                return mode.label + " needs async-profiler";
            }
            return null;
        }
    }

    private static final Pattern METHOD = Pattern.compile("[\\w$*]+(?:\\.[\\w$*<>]+)+");
    private static final Pattern EVENT = Pattern.compile("[\\w:./=,-]+");
    private static final Set<Mode> JFR_MODES = EnumSet.of(Mode.CPU, Mode.WALL, Mode.ALLOC, Mode.LOCK, Mode.ALL);

    /** A JVM that can be profiled. */
    public interface Target {
        String label();

        /** The engines that work here, the better one first. */
        List<Engine> engines();

        /** What an engine can measure here. */
        List<Mode> modes(Engine engine);

        /** Hardware (perf) events async-profiler offers here; empty where there are none (macOS). */
        List<String> hardwareEvents();

        /** Runs a profile: one {@link Profile} per thing measured, the one asked for first; null if cancelled. */
        List<Profile> run(Request request, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException;

        /** A diagnostic command in the JVM, by its jcmd name ({@code GC.class_histogram}). */
        String diagnostic(String command, String... args) throws IOException;

        /** Writes a heap dump (.hprof) of the JVM to {@code local}. */
        void heapDump(Path local, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException;
    }

    // ---------------------------------------------------------------- where the JVM is

    /** What a profile run needs from the computer the JVM runs on. */
    interface Shell {
        String label();

        long pid();

        /** async-profiler's platform there, or null where it doesn't run. */
        String platform() throws IOException;

        /** Runs asprof with these arguments; {@code stop} ends a run early. */
        SshJvms.Result asprof(List<String> args, int timeoutSeconds, BooleanSupplier cancelled) throws IOException;

        String diagnostic(String command, String... args) throws IOException;

        /** A new file name the JVM can write to. */
        String tempFile(String suffix) throws IOException;

        /** Makes a file the JVM wrote readable for Logsift, and copies it here. */
        void fetch(String file, Path local, DoubleConsumer progress) throws IOException;

        void remove(String file);
    }

    /** A JVM on this computer (attached with {@code session}). */
    public static Target local(JvmProcess jvm, LocalJvms.Session session) {
        return new ShellTarget(new Shell() {
            @Override
            public String label() {
                return jvm.name() + " (" + jvm.pid() + ")";
            }

            @Override
            public long pid() {
                return jvm.pid();
            }

            @Override
            public String platform() {
                String p = Agents.localPlatform();
                return Agents.has(p) ? p : null;
            }

            @Override
            public SshJvms.Result asprof(List<String> args, int timeoutSeconds, BooleanSupplier cancelled)
                    throws IOException {
                Path asprof = Agents.localAsprof();
                if (asprof == null) {
                    throw new IOException("async-profiler doesn't run on this computer; use Flight Recorder");
                }
                List<String> cmd = new ArrayList<>();
                cmd.add(asprof.toString());
                cmd.addAll(args);
                Path out = Files.createTempFile("logsift-profile-", ".txt");
                Path err = Files.createTempFile("logsift-profile-", ".err");
                try {
                    Process proc = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
                    long end = System.currentTimeMillis() + timeoutSeconds * 1000L;
                    while (true) {
                        try {
                            if (proc.waitFor(200, TimeUnit.MILLISECONDS)) {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (cancelled.getAsBoolean() || System.currentTimeMillis() > end) {
                            // stop the profiler in the JVM too, not just the launcher
                            new ProcessBuilder(asprof.toString(), "stop", String.valueOf(jvm.pid())).start();
                            proc.destroy();
                            return null;
                        }
                    }
                    return new SshJvms.Result(proc.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                            Files.readString(err, StandardCharsets.UTF_8));
                } finally {
                    Files.deleteIfExists(out);
                    Files.deleteIfExists(err);
                }
            }

            @Override
            public String diagnostic(String command, String... args) throws IOException {
                return session.diagnostic(command, args);
            }

            @Override
            public String tempFile(String suffix) throws IOException {
                Path f = Files.createTempFile("logsift-", suffix);
                Files.delete(f); // the JVM makes it
                return f.toString();
            }

            @Override
            public void fetch(String file, Path local, DoubleConsumer progress) throws IOException {
                if (!Path.of(file).equals(local)) {
                    Files.copy(Path.of(file), local, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            @Override
            public void remove(String file) {
                try {
                    Files.deleteIfExists(Path.of(file));
                } catch (IOException e) {
                    // a temp file
                }
            }
        });
    }

    /** A JVM on a server, reached over {@code ssh}. */
    public static Target remote(JvmProcess jvm, SshJvms ssh, SshJvms.Options options) {
        return new ShellTarget(new Shell() {
            @Override
            public String label() {
                return jvm.name() + " (" + jvm.pid() + ") on " + ssh.label().substring(ssh.label().indexOf('@') + 1);
            }

            @Override
            public long pid() {
                return jvm.pid();
            }

            @Override
            public String platform() throws IOException {
                String p = ssh.platform();
                return Agents.has(p) ? p : null;
            }

            @Override
            public SshJvms.Result asprof(List<String> args, int timeoutSeconds, BooleanSupplier cancelled)
                    throws IOException {
                String asprof = ssh.asprof();
                if (asprof == null) {
                    throw new IOException("async-profiler doesn't run on this server (" + ssh.platform()
                            + "); use Flight Recorder");
                }
                StringBuilder cmd = new StringBuilder(SshJvms.q(asprof));
                for (String a : args) {
                    cmd.append(' ').append(SshJvms.q(a));
                }
                boolean[] stopped = {false};
                Thread watch = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        if (cancelled.getAsBoolean()) {
                            stopped[0] = true;
                            try {
                                ssh.run(jvm, options, true, SshJvms.q(asprof) + " stop " + jvm.pid(), 30);
                            } catch (IOException e) {
                                // the run ends by itself anyway
                            }
                            return;
                        }
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }, "logsift-profile-stop");
                watch.setDaemon(true);
                watch.start();
                try {
                    SshJvms.Result r = ssh.run(jvm, options, true, cmd.toString(), timeoutSeconds);
                    return stopped[0] ? null : r;
                } finally {
                    watch.interrupt();
                }
            }

            @Override
            public String diagnostic(String command, String... args) throws IOException {
                return ssh.diagnostic(jvm, options, prepend(command, args));
            }

            @Override
            public String tempFile(String suffix) {
                return "/tmp/logsift-" + Long.toHexString(System.nanoTime()) + suffix;
            }

            @Override
            public void fetch(String file, Path local, DoubleConsumer progress) throws IOException {
                // written by the JVM's user: readable for the login user too
                ssh.run(jvm, options, true, "chmod 0644 " + SshJvms.q(file), 30);
                ssh.download(file, local, progress);
            }

            @Override
            public void remove(String file) {
                try {
                    ssh.run(jvm, options, true, "rm -f " + SshJvms.q(file), 30);
                } catch (IOException e) {
                    // left in /tmp
                }
            }
        });
    }

    private static String[] prepend(String first, String... rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    /** Profiling, histograms and heap dumps, the same wherever the JVM is. */
    private static final class ShellTarget implements Target {
        private final Shell sh;
        private List<String> hardware;

        ShellTarget(Shell sh) {
            this.sh = sh;
        }

        @Override
        public String label() {
            return sh.label();
        }

        @Override
        public List<Engine> engines() {
            try {
                return sh.platform() != null ? List.of(Engine.ASYNC_PROFILER, Engine.JFR) : List.of(Engine.JFR);
            } catch (IOException e) {
                return List.of(Engine.ASYNC_PROFILER, Engine.JFR);
            }
        }

        @Override
        public List<Mode> modes(Engine engine) {
            if (engine == Engine.JFR) {
                return List.of(Mode.CPU, Mode.WALL, Mode.ALLOC, Mode.LOCK, Mode.ALL);
            }
            List<Mode> m = new ArrayList<>(List.of(Mode.CPU, Mode.WALL, Mode.ALLOC, Mode.LIVE, Mode.LOCK,
                    Mode.NATIVE_MEMORY, Mode.NATIVE_LEAKS, Mode.NATIVE_LOCKS, Mode.METHOD_CALLS, Mode.METHOD_LATENCY));
            if (!hardwareEvents().isEmpty()) {
                m.add(Mode.HARDWARE);
            }
            m.add(Mode.ALL);
            return m;
        }

        @Override
        public synchronized List<String> hardwareEvents() {
            if (hardware == null) {
                hardware = List.of();
                try {
                    if (sh.platform() != null && sh.platform().startsWith("linux")) {
                        SshJvms.Result r = sh.asprof(List.of("list", String.valueOf(sh.pid())), 60, () -> false);
                        hardware = r == null ? List.of() : perfEvents(r.out());
                    }
                } catch (IOException e) {
                    // none then
                }
            }
            return hardware;
        }

        @Override
        public List<Profile> run(Request r, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
            String problem = r.problem();
            if (problem != null) {
                throw new IOException(problem);
            }
            String what = r.mode() == Mode.METHOD_CALLS || r.mode() == Mode.METHOD_LATENCY
                    ? r.mode().label + " " + r.method().trim()
                    + (r.mode() == Mode.METHOD_LATENCY ? " over " + r.latencyMs() + " ms" : "")
                    : r.mode() == Mode.HARDWARE ? r.event() : r.mode().label;
            String suffix = " of " + sh.label() + " · " + r.seconds() + " s";
            Thread ticker = ticker(r.seconds(), progress, cancelled);
            try {
                if (r.engine() == Engine.JFR) {
                    return flightRecorder(r, suffix, cancelled);
                }
                if (r.mode() == Mode.ALL || r.mode() == Mode.NATIVE_LEAKS) {
                    String file = sh.tempFile(".jfr");
                    SshJvms.Result res = sh.asprof(asprofArgs(r, sh.pid(), file, sh.platform()), r.seconds() + 120,
                            cancelled);
                    if (res == null) {
                        sh.remove(file);
                        return null;
                    }
                    if (res.exit() != 0) {
                        sh.remove(file);
                        throw new IOException("async-profiler: " + res.message());
                    }
                    Path local = Files.createTempFile("logsift-", ".jfr");
                    try {
                        sh.fetch(file, local, null);
                        Set<Mode> want = r.mode() == Mode.ALL
                                ? EnumSet.of(Mode.CPU, Mode.WALL, Mode.ALLOC, Mode.LIVE, Mode.LOCK, Mode.NATIVE_LEAKS,
                                Mode.NATIVE_MEMORY, Mode.NATIVE_LOCKS)
                                : EnumSet.of(Mode.NATIVE_LEAKS, Mode.NATIVE_MEMORY);
                        Mode first = r.mode() == Mode.ALL ? Mode.CPU : Mode.NATIVE_LEAKS;
                        List<Profile> views = views(fromJfr(local, want, r.perThread(), m -> m.label + suffix), first);
                        warnings(res.err(), views);
                        return views;
                    } finally {
                        Files.deleteIfExists(local);
                        sh.remove(file);
                    }
                }
                Profile p = collapsed(r, what + suffix, r.mode() == Mode.CPU ? "cpu" : null, cancelled);
                if (p != null && p.total() == 0 && r.mode() == Mode.CPU && p.warnings().stream().anyMatch(Profiler::noPerf)) {
                    p = collapsed(r, what + suffix, "itimer", cancelled);
                    if (p != null) {
                        p.warn(ITIMER);
                    }
                }
                return p == null ? null : List.of(p);
            } finally {
                ticker.interrupt();
            }
        }

        private Profile collapsed(Request r, String title, String cpuEvent, BooleanSupplier cancelled) throws IOException {
            List<String> args = asprofArgs(r, sh.pid(), null, sh.platform());
            if (cpuEvent != null) {
                args.set(args.indexOf("cpu"), cpuEvent);
            }
            SshJvms.Result res = sh.asprof(args, r.seconds() + 120, cancelled);
            if (res == null) {
                return null;
            }
            if (res.exit() != 0 && res.out().isBlank()) {
                throw new IOException("async-profiler: " + res.message());
            }
            Profile p = Profile.fromCollapsed(res.out(), title, r.mode().unit);
            warnings(res.err(), List.of(p));
            return p;
        }

        private List<Profile> flightRecorder(Request r, String suffix, BooleanSupplier cancelled) throws IOException {
            String name = "logsift-" + System.nanoTime();
            String file = sh.tempFile(".jfr");
            sh.diagnostic("JFR.start", "name=" + name, "settings=profile");
            waitFor(r.seconds(), cancelled);
            sh.diagnostic("JFR.stop", "name=" + name, "filename=" + file);
            if (cancelled.getAsBoolean()) {
                sh.remove(file);
                return null;
            }
            Path local = Files.createTempFile("logsift-", ".jfr");
            try {
                sh.fetch(file, local, null);
                Map<Mode, Profile> all = fromJfr(local, EnumSet.of(Mode.CPU, Mode.WALL, Mode.ALLOC, Mode.LOCK),
                        r.perThread(), m -> m.label + suffix);
                return views(all, r.mode() == Mode.ALL ? Mode.CPU : r.mode());
            } finally {
                Files.deleteIfExists(local);
                sh.remove(file);
            }
        }

        @Override
        public String diagnostic(String command, String... args) throws IOException {
            return sh.diagnostic(command, args);
        }

        @Override
        public void heapDump(Path local, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
            String file = sh.tempFile(".hprof");
            try {
                String out = sh.diagnostic("GC.heap_dump", file);
                if (out.toLowerCase(Locale.ROOT).contains("error") || out.toLowerCase(Locale.ROOT).contains("failed")) {
                    throw new IOException(LocalJvms.first(out));
                }
                if (cancelled.getAsBoolean()) {
                    return;
                }
                sh.fetch(file, local, progress);
            } finally {
                sh.remove(file);
            }
        }
    }

    /** The asprof arguments for a run ({@code jfr}: write a JFR file there instead of collapsed text). */
    static List<String> asprofArgs(Request r, long pid, String jfr) {
        return asprofArgs(r, pid, jfr, "linux-x64");
    }

    /**
     * The same for a platform. On macOS, CPU and wall clock can't both use their usual signals,
     * so "everything" there samples CPU time with a timer, and names each event itself.
     */
    static List<String> asprofArgs(Request r, long pid, String jfr, String platform) {
        List<String> a = new ArrayList<>(List.of("-d", String.valueOf(r.seconds())));
        switch (r.mode()) {
            case CPU -> a.addAll(List.of("-e", "cpu"));
            case WALL -> a.addAll(List.of("-e", "wall"));
            case ALLOC -> a.addAll(List.of("-e", "alloc"));
            case LIVE -> a.addAll(List.of("-e", "alloc", "--live"));
            case LOCK -> a.addAll(List.of("-e", "lock"));
            case NATIVE_MEMORY, NATIVE_LEAKS -> a.addAll(List.of("-e", "nativemem"));
            case NATIVE_LOCKS -> a.addAll(List.of("-e", "nativelock"));
            case METHOD_CALLS -> a.addAll(List.of("-e", r.method().trim()));
            case METHOD_LATENCY -> a.addAll(List.of("--trace", r.method().trim() + ":" + r.latencyMs() + "ms"));
            case HARDWARE -> a.addAll(List.of("-e", r.event().trim()));
            case ALL -> {
                if ("macos".equals(platform)) {
                    a.addAll(List.of("-e", "itimer", "--alloc", "512k", "--live", "--lock", "10ms", "--wall", "20ms",
                            "--nativemem", "0"));
                } else {
                    a.add("--all");
                }
            }
        }
        if (r.perThread()) {
            a.add("-t");
        }
        if (jfr != null) {
            a.addAll(List.of("-o", "jfr", "-f", jfr));
        } else {
            a.addAll(List.of("-o", "collapsed", "-a"));
            if (EnumSet.of(Mode.ALLOC, Mode.LIVE, Mode.LOCK, Mode.NATIVE_MEMORY, Mode.NATIVE_LOCKS, Mode.METHOD_LATENCY)
                    .contains(r.mode())) {
                a.add("--total");
            }
        }
        a.add(String.valueOf(pid));
        return a;
    }

    /** The hardware events in {@code asprof list} output (its "Perf events" part). */
    static List<String> perfEvents(String list) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String l : list.split("\n")) {
            if (!l.startsWith(" ") && !l.isBlank()) {
                in = l.toLowerCase(Locale.ROOT).startsWith("perf events");
                continue;
            }
            String t = l.strip();
            // placeholders like rNNN, mem:breakpoint, pmu/event-descriptor/ need values: left out
            if (in && !t.isEmpty() && EVENT.matcher(t).matches() && !t.contains(":") && !t.contains("/")
                    && !t.equals("rNNN")) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * The profiles to show: the one asked for first if it has anything, else the first that has;
     * the empty ones are left out, and said so.
     */
    private static List<Profile> views(Map<Mode, Profile> all, Mode first) {
        List<Profile> out = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        Profile f = all.get(first);
        if (f != null && f.total() > 0) {
            out.add(f);
        }
        for (Map.Entry<Mode, Profile> e : all.entrySet()) {
            if (e.getValue() == f && f.total() > 0) {
                continue;
            }
            if (e.getValue().total() > 0) {
                out.add(e.getValue());
            } else {
                empty.add(e.getKey().label);
            }
        }
        if (out.isEmpty()) {
            out.add(f != null ? f : all.values().iterator().next()); // nothing at all: say so on the one asked for
            return out;
        }
        if (all.size() > 1 && !empty.isEmpty()) {
            String note = "Nothing came back for: " + String.join(", ", empty) + ".";
            for (Profile p : out) {
                p.warnings().removeIf(w -> w.startsWith("The recording has no"));
                p.warn(note);
            }
        }
        return out;
    }

    private static final String ITIMER = "The kernel doesn't allow perf events here, so CPU time was sampled"
            + " with a timer (no kernel frames). To allow them: sysctl kernel.perf_event_paranoid=1";

    private static boolean noPerf(String warning) {
        String w = warning.toLowerCase(Locale.ROOT);
        return w.contains("perf_event") || w.contains("perf events");
    }

    /** async-profiler's [WARN] and [ERROR] lines, as warnings. */
    private static void warnings(String errors, List<Profile> ps) {
        for (String l : errors.split("\n")) {
            String t = l.strip();
            if (t.startsWith("[WARN]") || t.startsWith("[ERROR]")) {
                for (Profile p : ps) {
                    p.warn(t.substring(t.indexOf(']') + 1).strip());
                }
            }
        }
    }

    private static void waitFor(int seconds, BooleanSupplier cancelled) {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end && !cancelled.getAsBoolean()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Reports progress while a run waits. */
    private static Thread ticker(int seconds, DoubleConsumer progress, BooleanSupplier cancelled) {
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted() && !cancelled.getAsBoolean()) {
                if (progress != null) {
                    progress.accept(Math.min(1, (System.currentTimeMillis() - start) / (seconds * 1000.0)));
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "logsift-profile-progress");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ---------------------------------------------------------------- recordings (.jfr)

    /**
     * Profiles from a recording: Flight Recorder's own, or async-profiler's (which adds wall clock,
     * live objects, native memory and native locks). Each mode asked for gets a profile, maybe empty.
     */
    public static Map<Mode, Profile> fromJfr(Path file, Set<Mode> want, boolean perThread,
                                             java.util.function.Function<Mode, String> title) throws IOException {
        Map<Mode, Profile> out = new EnumMap<>(Mode.class);
        for (Mode m : want) {
            out.put(m, new Profile(title.apply(m), m.unit));
        }
        Profile allocSamples = new Profile("", "bytes");
        Profile allocTlab = new Profile("", "bytes");
        Profile wallSamples = new Profile("", "samples");
        Profile wallJdk = new Profile("", "samples");
        record Malloc(long time, long address, long size, List<String> frames) {}
        List<long[]> frees = new ArrayList<>();
        List<Malloc> mallocs = new ArrayList<>();
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                long t = e.getStartTime().toEpochMilli();
                switch (type) {
                    case "jdk.ExecutionSample" -> {
                        add(out.get(Mode.CPU), e, perThread, null, 1);
                        add(wallJdk, e, perThread, null, 1);
                    }
                    case "jdk.NativeMethodSample" -> add(wallJdk, e, perThread, null, 1);
                    case "profiler.WallClockSample" -> add(wallSamples, e, perThread, null,
                            Math.max(1, longField(e, "samples")));
                    case "jdk.ObjectAllocationSample" -> add(allocSamples, e, perThread, classOf(e, "objectClass"),
                            longField(e, "weight"));
                    case "jdk.ObjectAllocationInNewTLAB" -> add(allocTlab, e, perThread, classOf(e, "objectClass"),
                            longField(e, "tlabSize"));
                    case "jdk.ObjectAllocationOutsideTLAB" -> add(allocTlab, e, perThread, classOf(e, "objectClass"),
                            longField(e, "allocationSize"));
                    case "profiler.LiveObject" -> add(out.get(Mode.LIVE), e, perThread, classOf(e, "objectClass"),
                            longField(e, "allocationSize"));
                    case "jdk.JavaMonitorEnter" -> add(out.get(Mode.LOCK), e, perThread, classOf(e, "monitorClass"),
                            Math.max(1, e.getDuration().toNanos()));
                    case "jdk.ThreadPark" -> {
                        List<String> fr = frames(e.getStackTrace());
                        if (fr.stream().noneMatch(f -> f.contains("getTask") || f.contains("awaitWork"))) {
                            add(out.get(Mode.LOCK), e, perThread, classOf(e, "parkedClass"),
                                    Math.max(1, e.getDuration().toNanos()));
                        }
                    }
                    case "profiler.NativeLock" -> add(out.get(Mode.NATIVE_LOCKS), e, perThread, null,
                            Math.max(1, e.getDuration().toNanos()));
                    case "profiler.Malloc" -> {
                        long size = longField(e, "size");
                        add(out.get(Mode.NATIVE_MEMORY), e, perThread, null, size);
                        if (out.containsKey(Mode.NATIVE_LEAKS)) {
                            List<String> fr = frames(e.getStackTrace());
                            if (perThread) {
                                fr.add(0, threadFrame(e));
                            }
                            mallocs.add(new Malloc(t, longField(e, "address"), size, fr));
                            first = Math.min(first, t);
                            last = Math.max(last, t);
                        }
                    }
                    case "profiler.Free" -> {
                        if (out.containsKey(Mode.NATIVE_LEAKS)) {
                            frees.add(new long[] {t, longField(e, "address")});
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        if (out.containsKey(Mode.ALLOC)) {
            merge(out.get(Mode.ALLOC), allocSamples.total() > 0 ? allocSamples : allocTlab);
        }
        if (out.containsKey(Mode.WALL)) {
            merge(out.get(Mode.WALL), wallSamples.total() > 0 ? wallSamples : wallJdk);
        }
        if (out.containsKey(Mode.NATIVE_LEAKS) && !mallocs.isEmpty()) {
            // a malloc counts as leaked if no free of its address follows it; the last 10% of the
            // run is left out (that memory may simply not have been freed yet)
            mallocs.sort((a, b) -> Long.compare(a.time(), b.time()));
            frees.sort((a, b) -> Long.compare(a[0], b[0]));
            long cutoff = last - (last - first) / 10;
            Map<Long, Malloc> live = new HashMap<>();
            int fi = 0;
            for (Malloc m : mallocs) {
                while (fi < frees.size() && frees.get(fi)[0] < m.time()) {
                    live.remove(frees.get(fi)[1]);
                    fi++;
                }
                live.put(m.address(), m);
            }
            for (; fi < frees.size(); fi++) {
                live.remove(frees.get(fi)[1]);
            }
            Profile leaks = out.get(Mode.NATIVE_LEAKS);
            for (Malloc m : live.values()) {
                if (m.time() <= cutoff) {
                    leaks.add(m.frames(), m.size());
                }
            }
        }
        for (Profile p : out.values()) {
            if (p.total() == 0) {
                p.warn("The recording has no " + p.title().replaceFirst(" of .*", "").toLowerCase(Locale.ROOT)
                        + " events.");
            }
        }
        return out;
    }

    private static void merge(Profile into, Profile from) {
        from.forEachStack((path, w) -> into.add(path.stream().map(n -> n.name() + suffix(n.kind())).toList(), w));
    }

    private static String suffix(Profile.Kind k) {
        return switch (k) {
            case JAVA -> "_[j]";
            case INLINED -> "_[i]";
            case INTERPRETED -> "_[0]";
            case C1 -> "_[1]";
            case KERNEL -> "_[k]";
            default -> "";
        };
    }

    private static long longField(RecordedEvent e, String name) {
        try {
            return e.getLong(name);
        } catch (IllegalArgumentException ex) {
            return 0;
        }
    }

    private static String classOf(RecordedEvent e, String field) {
        try {
            RecordedClass c = e.getClass(field);
            return c == null ? null : HeapHistogram.readable(c.getName());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String threadFrame(RecordedEvent e) {
        RecordedThread t = null;
        for (String f : new String[] {"sampledThread", "eventThread"}) {
            try {
                t = e.getThread(f);
            } catch (IllegalArgumentException ex) {
                // not this one
            }
            if (t != null) {
                break;
            }
        }
        String name = t == null ? "?" : t.getJavaName() != null ? t.getJavaName() : t.getOSName();
        return "[" + (name == null ? "?" : name) + "]";
    }

    private static void add(Profile p, RecordedEvent e, boolean perThread, String leaf, long weight) {
        if (p == null || weight <= 0) {
            return;
        }
        List<String> fr = frames(e.getStackTrace());
        if (fr.isEmpty() && leaf == null) {
            return;
        }
        if (perThread) {
            fr.add(0, threadFrame(e));
        }
        if (leaf != null) {
            fr.add(leaf + "_[i]"); // the allocated class (or lock class), as async-profiler shows it
        }
        p.add(fr, weight);
    }

    /** A stack, outermost frame first, named like async-profiler's collapsed output. */
    private static List<String> frames(RecordedStackTrace st) {
        List<String> out = new ArrayList<>();
        if (st == null) {
            return out;
        }
        List<RecordedFrame> fs = st.getFrames();
        for (int i = fs.size() - 1; i >= 0; i--) {
            RecordedFrame f = fs.get(i);
            if (f.getMethod() == null) {
                continue;
            }
            String kind = f.getType();
            String type = f.getMethod().getType() == null ? "" : f.getMethod().getType().getName();
            String method = f.getMethod().getName();
            switch (kind) {
                case "C++", "Native" -> out.add(method); // native code: the library isn't part of the name
                case "Kernel" -> out.add(method + "_[k]");
                case "Inlined" -> out.add(type + "." + method + "_[i]");
                case "Interpreted" -> out.add(type + "." + method + "_[0]");
                case "C1 compiled" -> out.add(type + "." + method + "_[1]");
                default -> out.add(type + "." + method + "_[j]");
            }
        }
        return out;
    }

    /** Reads a saved profile: a .jfr recording (every kind it has) or collapsed text. */
    public static List<Profile> open(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jfr")) {
            Map<Mode, Profile> all = fromJfr(file, EnumSet.of(Mode.CPU, Mode.WALL, Mode.ALLOC, Mode.LIVE, Mode.LOCK,
                    Mode.NATIVE_LEAKS, Mode.NATIVE_MEMORY, Mode.NATIVE_LOCKS), false, m -> m.label + " in " + name);
            List<Profile> v = views(all, Mode.CPU);
            if (v.stream().allMatch(p -> p.total() == 0)) {
                throw new IOException(name + " has no samples to show");
            }
            return v.stream().filter(p -> p.total() > 0).toList();
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Profile p = Profile.fromCollapsed(text, name, "samples");
        if (p.total() == 0) {
            throw new IOException(name + " is not a profile (collapsed stacks or a .jfr recording)");
        }
        return List.of(p);
    }
}
