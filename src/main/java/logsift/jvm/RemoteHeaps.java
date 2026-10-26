package logsift.jvm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.BooleanSupplier;
import logsift.heap.HeapDump;
import logsift.heap.HeapServer;
import logsift.heap.RemoteHeap;

/**
 * Reads a heap dump on a server: finds a Java (8 or later) there, checks there is room, copies
 * Logsift's heap reader over, starts it at low priority (it keeps running if the connection
 * drops, and ends after 30 minutes with no one connected), and connects to it through SSH.
 */
public final class RemoteHeaps {
    /** Minutes the reader waits with no one connected before it ends (removing its index). */
    public static final int IDLE_MINUTES = 30;

    private RemoteHeaps() {
    }

    /** A reader already running for a dump. */
    public record Running(int port, String token, long pid) {}

    /** What reading a dump on a server takes, found out before anything is started. */
    public record Plan(String host, String dump, long dumpBytes, String stateDir, String workDir, String java,
                       int javaVersion, long freeBytes, long memoryBytes, boolean ionice, Running running) {
        /** Disk the index may take, at the most. */
        public long diskNeeded() {
            return HeapDump.diskNeeded(dumpBytes);
        }

        /** Null when it can go ahead; else why not, in a sentence. */
        public String problem() {
            if (running != null) {
                return null;
            }
            if (java == null) {
                return "There is no Java on " + host + " (Logsift's heap reader needs Java 8 or later; it looked in"
                        + " the PATH, JAVA_HOME, /usr/lib/jvm, /usr/java, /opt and the running JVMs).";
            }
            if (freeBytes >= 0 && freeBytes < diskNeeded() + (1L << 30)) {
                return "The index needs up to " + logsift.heap.HeapFindings.bytes(diskNeeded()) + " of disk in " + workDir
                        + " on " + host + ", and there is only " + logsift.heap.HeapFindings.bytes(freeBytes)
                        + " free (Logsift leaves at least 1 GB). Choose a folder on a bigger disk.";
            }
            return null;
        }
    }

    /** The key of a dump's reader on its server: the same path, the same reader. */
    static String key(String dump) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(dump.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Finds out what reading {@code dump} on the server takes; {@code workFolder}: where its index
     * goes (null: in ~/.logsift/heap on the server).
     */
    public static Plan plan(SshJvms ssh, String dump, String workFolder) throws IOException {
        String key = key(dump);
        String script = """
                f=%s
                if [ ! -r "$f" ]; then echo "error=cannot read $f"; exit 0; fi
                echo "size=$(wc -c < "$f" | tr -d ' ')"
                state="$HOME/.logsift/heap/%s"
                echo "state=$state"
                work=%s
                [ -z "$work" ] && work="$state/index"
                echo "work=$work"
                p="$work"; while [ ! -d "$p" ]; do p=$(dirname "$p"); done
                echo "free=$(df -Pk "$p" 2>/dev/null | awk 'NR==2 {print $4 * 1024}')"
                echo "memory=$(awk '/MemAvailable/ {print $2 * 1024}' /proc/meminfo 2>/dev/null)"
                command -v ionice >/dev/null 2>&1 && echo "ionice=1"
                if [ -f "$state/server" ]; then
                  read port token pid < "$state/server"
                  if kill -0 "$pid" 2>/dev/null && [ "$(sed -n 2p "$state/server")" = "$f" ]; then
                    echo "running=$port $token $pid"
                  fi
                fi
                best=; bestv=0
                for j in "$(command -v java 2>/dev/null)" "${JAVA_HOME:+$JAVA_HOME/bin/java}" \\
                    $(ls -d /usr/lib/jvm/*/bin/java /usr/java/*/bin/java /opt/*/bin/java /opt/java/*/bin/java \\
                      /opt/*/jre/bin/java 2>/dev/null) \\
                    $(for p in /proc/[0-9]*; do readlink "$p/exe" 2>/dev/null; done | grep '/bin/java$' | sort -u); do
                  [ -n "$j" ] && [ -x "$j" ] || continue
                  v=$("$j" -version 2>&1 | head -1 | sed -E 's/.*version "([^"]*)".*/\\1/')
                  case "$v" in 1.*) n=$(echo "$v" | cut -d. -f2);; *) n=$(echo "$v" | cut -d. -f1 | cut -d- -f1);; esac
                  case "$n" in ''|*[!0-9]*) continue;; esac
                  if [ "$n" -ge 8 ] && [ "$n" -gt "$bestv" ]; then best=$j; bestv=$n; fi
                done
                echo "java=$best"
                echo "javaVersion=$bestv"
                """.formatted(SshJvms.q(dump), key, SshJvms.q(workFolder == null ? ""
                : workFolder.replaceAll("/+$", "") + "/logsift-heap-" + key));
        SshJvms.Result r = ssh.exec("sh -s", script, 60);
        Map<String, String> v = new HashMap<>();
        for (String line : r.out().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                v.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        if (v.containsKey("error")) {
            throw new IOException(v.get("error") + " on " + ssh.label());
        }
        if (!v.containsKey("size")) {
            throw new IOException("could not look at " + dump + " on " + ssh.label() + ": " + r.message());
        }
        Running running = null;
        if (v.get("running") != null) {
            String[] w = v.get("running").split(" ");
            running = new Running(Integer.parseInt(w[0]), w[1], Long.parseLong(w[2]));
        }
        String java = v.getOrDefault("java", "");
        return new Plan(ssh.label(), dump, number(v.get("size")), v.get("state"), v.get("work"),
                java.isEmpty() ? null : java, (int) number(v.get("javaVersion")), number(v.get("free")),
                number(v.get("memory")), v.containsKey("ionice"), running);
    }

    private static long number(String s) {
        try {
            return s == null || s.isEmpty() ? -1 : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Logsift's heap reader, as a jar (the one the app runs from). */
    static Path readerJar() throws IOException {
        try {
            Path p = Path.of(HeapServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(p)) {
                throw new IOException("the heap reader isn't in a jar here (" + p + "): build the app first");
            }
            return p;
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
    }

    /**
     * Starts the reader on the server (or finds the one running) and connects to it. The dump is
     * then read there; see {@link RemoteHeap#awaitReady}.
     */
    public static RemoteHeap open(SshJvms ssh, Plan plan, BooleanSupplier cancel) throws IOException {
        Running running = plan.running();
        if (running == null) {
            String problem = plan.problem();
            if (problem != null) {
                throw new IOException(problem);
            }
            running = start(ssh, plan, cancel);
        }
        RemoteHeap.Link link = ssh.tunnel(running.port());
        return RemoteHeap.connect(link, running.token(), ssh.label());
    }

    private static Running start(SshJvms ssh, Plan plan, BooleanSupplier cancel) throws IOException {
        Path jar = readerJar();
        String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)), 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        String state = plan.stateDir();
        String heapDir = state.substring(0, state.lastIndexOf('/'));
        String remoteJar = heapDir + "/logsift-heap-" + sha + ".jar";
        SshJvms.Result mk = ssh.exec("mkdir -p " + SshJvms.q(state) + " && chmod 700 " + SshJvms.q(heapDir) + " "
                + SshJvms.q(state) + " && rm -f " + SshJvms.q(state + "/server") + " && test -f "
                + SshJvms.q(remoteJar) + " && echo have", null, 30);
        if (!mk.out().contains("have")) {
            if (mk.exit() != 0 && !mk.err().isBlank()) {
                throw new IOException("cannot make " + state + " on " + ssh.label() + ": " + mk.message());
            }
            ssh.upload(jar, remoteJar);
        }
        String command = "cd " + SshJvms.q(state) + " && nohup " + (plan.ionice() ? "ionice -c2 -n7 " : "")
                + "nice -n 19 " + SshJvms.q(plan.java()) + " -Xmx2g -cp " + SshJvms.q(remoteJar) + " "
                + HeapServer.class.getName() + " --dump " + SshJvms.q(plan.dump()) + " --work "
                + SshJvms.q(plan.workDir()) + " --state " + SshJvms.q(state) + " --idle " + IDLE_MINUTES
                + " > log 2>&1 < /dev/null & echo $!";
        SshJvms.Result r = ssh.exec("sh -c " + SshJvms.q(command), null, 30);
        long pid = number(r.out().trim());
        if (pid <= 0) {
            throw new IOException("cannot start the heap reader on " + ssh.label() + ": " + r.message());
        }
        // it writes its port and token when it listens
        long until = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < until) {
            if (cancel != null && cancel.getAsBoolean()) {
                ssh.exec("kill " + pid, null, 10);
                throw new java.util.concurrent.CancellationException();
            }
            SshJvms.Result s = ssh.exec("cat " + SshJvms.q(state + "/server") + " 2>/dev/null || (kill -0 " + pid
                    + " 2>/dev/null && echo waiting || (echo ended; tail -5 " + SshJvms.q(state + "/log") + "))",
                    null, 30);
            String out = s.out().trim();
            if (out.startsWith("ended")) {
                throw new IOException("the heap reader on " + ssh.label() + " ended: "
                        + out.substring("ended".length()).trim());
            }
            if (!out.equals("waiting") && !out.isEmpty()) {
                String[] w = out.split("\n")[0].trim().split(" ");
                if (w.length >= 3) {
                    return new Running(Integer.parseInt(w[0]), w[1], Long.parseLong(w[2]));
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
        throw new IOException("the heap reader on " + ssh.label() + " didn't start in a minute");
    }
}
