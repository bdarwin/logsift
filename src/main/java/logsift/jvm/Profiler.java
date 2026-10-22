package logsift.jvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * Profiles a running JVM for some seconds: with async-profiler (Linux and macOS JVMs; it sees
 * native and kernel frames too) or with the JVM's own Flight Recorder (any JVM with JFR,
 * Windows too). Either way the result is a {@link Profile} for the flame graph.
 */
public final class Profiler {
    private Profiler() {
    }

    /** What to measure. */
    public enum Mode {
        CPU("cpu", "CPU", "Where the threads use the CPU"),
        WALL("wall", "Wall clock", "Where the threads spend their time, running or waiting"),
        ALLOC("alloc", "Allocations", "Where memory is allocated"),
        LOCK("lock", "Locks", "Where threads wait to get locks");

        public final String event;
        public final String label;
        public final String description;

        Mode(String event, String label, String description) {
            this.event = event;
            this.label = label;
            this.description = description;
        }

        String unit() {
            return switch (this) {
                case ALLOC -> "bytes";
                case LOCK -> "ns";
                default -> "samples";
            };
        }
    }

    public enum Engine {
        ASYNC_PROFILER("async-profiler"), JFR("Flight Recorder");

        public final String label;

        Engine(String label) {
            this.label = label;
        }
    }

    /** A JVM that can be profiled. */
    public interface Target {
        String label();

        /** The engines that can work here, the better one first. */
        List<Engine> engines();

        Profile run(Engine engine, Mode mode, int seconds, DoubleConsumer progress, BooleanSupplier cancelled)
                throws IOException;
    }

    // ---------------------------------------------------------------- this computer

    /** A JVM on this computer (attached with {@code session}). */
    public static Target local(JvmProcess jvm, LocalJvms.Session session) {
        return new Target() {
            @Override
            public String label() {
                return jvm.name() + " (" + jvm.pid() + ")";
            }

            @Override
            public List<Engine> engines() {
                return Agents.has(Agents.localPlatform()) ? List.of(Engine.ASYNC_PROFILER, Engine.JFR)
                        : List.of(Engine.JFR);
            }

            @Override
            public Profile run(Engine engine, Mode mode, int seconds, DoubleConsumer progress,
                               BooleanSupplier cancelled) throws IOException {
                String title = mode.label + " of " + label() + " · " + seconds + " s";
                if (engine == Engine.ASYNC_PROFILER) {
                    Path asprof = Agents.localAsprof();
                    if (asprof == null) {
                        throw new IOException("async-profiler doesn't run on this computer; use Flight Recorder");
                    }
                    return localAsprof(asprof, jvm.pid(), mode, seconds, title, progress, cancelled);
                }
                Path file = Files.createTempFile("logsift-", ".jfr");
                try {
                    String name = "logsift-" + System.nanoTime();
                    session.diagnostic("jfrStart", "name=" + name, "settings=profile");
                    waitFor(seconds, progress, cancelled);
                    session.diagnostic("jfrStop", "name=" + name, "filename=" + file);
                    if (cancelled.getAsBoolean()) {
                        return null;
                    }
                    return fromJfr(file, mode, title);
                } finally {
                    Files.deleteIfExists(file);
                }
            }
        };
    }

    private static Profile localAsprof(Path asprof, long pid, Mode mode, int seconds, String title,
                                       DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
        Profile p = localAsprofOnce(asprof, pid, mode.event, mode, seconds, title, progress, cancelled);
        if (p != null && p.total() == 0 && mode == Mode.CPU && p.warnings().stream().anyMatch(Profiler::noPerf)) {
            p = localAsprofOnce(asprof, pid, "itimer", mode, seconds, title, progress, cancelled);
            if (p != null) {
                p.warn(ITIMER);
            }
        }
        return p;
    }

    private static Profile localAsprofOnce(Path asprof, long pid, String event, Mode mode, int seconds, String title,
                                           DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(asprof.toString(), "-d", String.valueOf(seconds), "-e", event,
                "-o", "collapsed", "-a"));
        if (mode == Mode.ALLOC || mode == Mode.LOCK) {
            cmd.add("--total");
        }
        cmd.add(String.valueOf(pid));
        Path out = Files.createTempFile("logsift-profile-", ".txt");
        Path err = Files.createTempFile("logsift-profile-", ".err");
        try {
            Process proc = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            long end = System.currentTimeMillis() + seconds * 1000L + 60_000;
            long start = System.currentTimeMillis();
            while (true) {
                try {
                    if (proc.waitFor(200, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (progress != null) {
                    progress.accept(Math.min(1, (System.currentTimeMillis() - start) / (seconds * 1000.0)));
                }
                if (cancelled.getAsBoolean() || System.currentTimeMillis() > end) {
                    // stop the profiler in the JVM too, not just the launcher
                    new ProcessBuilder(asprof.toString(), "stop", String.valueOf(pid)).start();
                    proc.destroy();
                    return null;
                }
            }
            String text = Files.readString(out, StandardCharsets.UTF_8);
            String errors = Files.readString(err, StandardCharsets.UTF_8);
            if (proc.exitValue() != 0 && text.isBlank()) {
                throw new IOException("async-profiler: " + LocalJvms.first(errors.isBlank() ? text : errors));
            }
            Profile p = Profile.fromCollapsed(text, title, mode.unit());
            warnings(errors, p);
            return p;
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    private static final String ITIMER = "The kernel doesn't allow perf events here, so CPU time was sampled"
            + " with a timer (no kernel frames). To allow them: sysctl kernel.perf_event_paranoid=1";

    private static boolean noPerf(String warning) {
        String w = warning.toLowerCase(Locale.ROOT);
        return w.contains("perf_event") || w.contains("perf events");
    }

    /** async-profiler's [WARN] and [ERROR] lines, as warnings. */
    private static void warnings(String errors, Profile p) {
        for (String l : errors.split("\n")) {
            String t = l.strip();
            if (t.startsWith("[WARN]") || t.startsWith("[ERROR]")) {
                p.warn(t.substring(t.indexOf(']') + 1).strip());
            }
        }
    }

    private static void waitFor(int seconds, DoubleConsumer progress, BooleanSupplier cancelled) {
        long start = System.currentTimeMillis();
        long end = start + seconds * 1000L;
        while (System.currentTimeMillis() < end && !cancelled.getAsBoolean()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (progress != null) {
                progress.accept(Math.min(1, (System.currentTimeMillis() - start) / (seconds * 1000.0)));
            }
        }
    }

    // ---------------------------------------------------------------- a server

    /** A JVM on a server, reached over {@code ssh}. */
    public static Target remote(JvmProcess jvm, SshJvms ssh, SshJvms.Options options) {
        return new Target() {
            @Override
            public String label() {
                return jvm.name() + " (" + jvm.pid() + ") on " + ssh.label().substring(ssh.label().indexOf('@') + 1);
            }

            @Override
            public List<Engine> engines() {
                try {
                    return Agents.has(ssh.platform()) ? List.of(Engine.ASYNC_PROFILER, Engine.JFR) : List.of(Engine.JFR);
                } catch (IOException e) {
                    return List.of(Engine.ASYNC_PROFILER, Engine.JFR);
                }
            }

            @Override
            public Profile run(Engine engine, Mode mode, int seconds, DoubleConsumer progress,
                               BooleanSupplier cancelled) throws IOException {
                String title = mode.label + " of " + label() + " · " + seconds + " s";
                Thread ticker = ticker(seconds, progress, cancelled, () -> stopRemote(engine));
                try {
                    if (engine == Engine.ASYNC_PROFILER) {
                        Profile p = remoteAsprof(mode.event, mode, seconds, title);
                        if (p.total() == 0 && mode == Mode.CPU && p.warnings().stream().anyMatch(Profiler::noPerf)) {
                            p = remoteAsprof("itimer", mode, seconds, title);
                            p.warn(ITIMER);
                        }
                        return cancelled.getAsBoolean() ? null : p;
                    }
                    String name = "logsift-" + System.nanoTime();
                    String file = "/tmp/logsift-" + name + ".jfr";
                    ssh.diagnostic(jvm, options, "JFR.start", "name=" + name, "settings=profile");
                    waitFor(seconds, null, cancelled);
                    ssh.diagnostic(jvm, options, "JFR.stop", "name=" + name, "filename=" + file);
                    if (cancelled.getAsBoolean()) {
                        ssh.run(jvm, options, true, "rm -f " + SshJvms.q(file), 30);
                        return null;
                    }
                    Path local = Files.createTempFile("logsift-", ".jfr");
                    try {
                        ssh.download(file, local);
                        return fromJfr(local, mode, title);
                    } finally {
                        Files.deleteIfExists(local);
                        ssh.run(jvm, options, true, "rm -f " + SshJvms.q(file), 30);
                    }
                } finally {
                    ticker.interrupt();
                }
            }

            private Profile remoteAsprof(String event, Mode mode, int seconds, String title) throws IOException {
                String asprof = ssh.asprof();
                if (asprof == null) {
                    throw new IOException("async-profiler doesn't run on this server (" + ssh.platform()
                            + "); use Flight Recorder");
                }
                String cmd = SshJvms.q(asprof) + " -d " + seconds + " -e " + event + " -o collapsed -a"
                        + (mode == Mode.ALLOC || mode == Mode.LOCK ? " --total" : "") + " " + jvm.pid();
                SshJvms.Result r = ssh.run(jvm, options, true, cmd, seconds + 120);
                if (r.exit() != 0 && r.out().isBlank()) {
                    throw new IOException("async-profiler: " + r.message());
                }
                Profile p = Profile.fromCollapsed(r.out(), title, mode.unit());
                warnings(r.err(), p);
                return p;
            }

            private void stopRemote(Engine engine) {
                if (engine != Engine.ASYNC_PROFILER) {
                    return;
                }
                try {
                    String asprof = ssh.asprof();
                    if (asprof != null) {
                        ssh.run(jvm, options, true, SshJvms.q(asprof) + " stop " + jvm.pid(), 30);
                    }
                } catch (IOException e) {
                    // the run ends by itself anyway
                }
            }
        };
    }

    /** Reports progress while a remote run waits; on cancel, runs {@code stop} once. */
    private static Thread ticker(int seconds, DoubleConsumer progress, BooleanSupplier cancelled, Runnable stop) {
        Thread t = new Thread(() -> {
            long start = System.currentTimeMillis();
            while (!Thread.currentThread().isInterrupted()) {
                if (cancelled.getAsBoolean()) {
                    stop.run();
                    return;
                }
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

    // ---------------------------------------------------------------- Flight Recorder files

    /** A profile from a Flight Recorder file (.jfr): the events of {@code mode}. */
    public static Profile fromJfr(Path file, Mode mode, String title) throws IOException {
        Profile p = fromJfr(file, mode, title, false);
        if (p.total() == 0 && mode == Mode.ALLOC) {
            p = fromJfr(file, mode, title, true); // before JDK 16: allocations in new TLABs and outside them
        }
        if (p.total() == 0) {
            p.warn("The recording has no " + mode.label.toLowerCase(Locale.ROOT) + " events"
                    + (mode == Mode.LOCK ? " (no thread waited 10 ms or more for a lock)" : "") + ".");
        }
        return p;
    }

    private static Profile fromJfr(Path file, Mode mode, String title, boolean tlab) throws IOException {
        Profile p = new Profile(title, mode.unit());
        List<String> frames = new ArrayList<>();
        try (RecordingFile rf = new RecordingFile(file)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent e = rf.readEvent();
                String type = e.getEventType().getName();
                long weight = switch (mode) {
                    case CPU -> type.equals("jdk.ExecutionSample") ? 1 : 0;
                    case WALL -> type.equals("jdk.ExecutionSample") || type.equals("jdk.NativeMethodSample") ? 1 : 0;
                    case ALLOC -> !tlab ? (type.equals("jdk.ObjectAllocationSample") ? e.getLong("weight") : 0)
                            : type.equals("jdk.ObjectAllocationInNewTLAB") ? e.getLong("tlabSize")
                            : type.equals("jdk.ObjectAllocationOutsideTLAB") ? e.getLong("allocationSize") : 0;
                    case LOCK -> type.equals("jdk.JavaMonitorEnter") || type.equals("jdk.ThreadPark")
                            ? Math.max(1, e.getDuration().toNanos()) : 0;
                };
                if (weight <= 0) {
                    continue;
                }
                RecordedStackTrace st = e.getStackTrace();
                if (st == null) {
                    continue;
                }
                frames.clear();
                List<RecordedFrame> fs = st.getFrames();
                for (int i = fs.size() - 1; i >= 0; i--) {
                    RecordedFrame f = fs.get(i);
                    if (f.getMethod() == null) {
                        continue;
                    }
                    String m = f.getMethod().getType().getName() + "." + f.getMethod().getName();
                    String kind = f.getType();
                    frames.add(m + ("Inlined".equals(kind) ? "_[i]" : "Interpreted".equals(kind) ? "_[0]"
                            : "C1".equals(kind) ? "_[1]" : "_[j]"));
                }
                if (mode == Mode.LOCK && type.equals("jdk.ThreadPark")) {
                    // parked waiting for work isn't lock contention
                    if (frames.stream().anyMatch(fr -> fr.contains("getTask") || fr.contains("awaitWork"))) {
                        continue;
                    }
                }
                p.add(frames, weight);
            }
        }
        return p;
    }

    /** Reads a saved profile: a .jfr file (CPU samples) or collapsed text. */
    public static Profile open(Path file) throws IOException {
        String name = file.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jfr")) {
            return fromJfr(file, Mode.CPU, "CPU samples in " + name);
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Profile p = Profile.fromCollapsed(text, name, "samples");
        if (p.total() == 0) {
            throw new IOException(name + " is not a profile (collapsed stacks or a .jfr recording)");
        }
        return p;
    }
}
