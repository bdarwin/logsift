package logsift.jvm;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import logsift.core.RemoteMirror;

/**
 * JVMs on a server, over SSH: lists them ({@code ps}, and {@code /tmp/hsperfdata_*}) and takes
 * thread dumps with {@code jcmd} (from the JDK folder given, the PATH, or the JVM's own JDK),
 * or, where no JDK tools are installed, with async-profiler's {@code asprof}, copied to the
 * server's {@code /tmp} first. A JVM running as another user is reached with {@code sudo}.
 */
public final class SshJvms implements AutoCloseable {

    /**
     * How to reach the JVMs.
     *
     * @param jdkHome      a JDK folder on the server ({@code /opt/jdk-21}), or "" to look for one
     * @param sudo         use sudo for JVMs of other users
     * @param sudoPassword the password sudo asks for, or null (then sudo must not ask)
     */
    public record Options(String jdkHome, boolean sudo, String sudoPassword) {
        public Options {
            jdkHome = jdkHome == null ? "" : jdkHome.trim();
        }
    }

    /** What a command printed and how it ended. */
    public record Result(int exit, String out, String err) {
        public String message() {
            String e = err.strip();
            return LocalJvms.first(e.isEmpty() ? out : e);
        }
    }

    /** sudo wants a password (none was given, or it was wrong). */
    public static final class SudoPasswordNeeded extends IOException {
        private static final long serialVersionUID = 1L;

        public SudoPasswordNeeded(String message) {
            super(message);
        }
    }

    private final Session session;
    private final String user;
    private final String host;
    private String platform;
    private String jcmdFound;

    private SshJvms(Session session, String user, String host) {
        this.session = session;
        this.user = user;
        this.host = host;
    }

    public static SshJvms connect(RemoteMirror.Target target, Path knownHosts, Predicate<String> trustNewHost)
            throws IOException {
        try {
            return new SshJvms(RemoteMirror.openSession(target, knownHosts, trustNewHost), target.user(),
                    target.host());
        } catch (JSchException e) {
            throw new IOException(RemoteMirror.describe(e), e);
        }
    }

    /** "ops@server" */
    public String label() {
        return user + "@" + host;
    }

    public String user() {
        return user;
    }

    public boolean isConnected() {
        return session.isConnected();
    }

    @Override
    public void close() {
        session.disconnect();
    }

    // ---------------------------------------------------------------- running commands

    /** Runs a shell command; {@code stdin} (may be null) is written to it first. */
    public Result exec(String command, String stdin, int timeoutSeconds) throws IOException {
        ChannelExec ch;
        try {
            ch = (ChannelExec) session.openChannel("exec");
        } catch (JSchException e) {
            throw new IOException(RemoteMirror.describe(e), e);
        }
        ch.setCommand(command);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ch.setErrStream(err, true);
        Thread watchdog = null;
        try {
            InputStream out = ch.getInputStream();
            OutputStream in = ch.getOutputStream();
            ch.connect(15_000);
            if (stdin != null) {
                in.write(stdin.getBytes(StandardCharsets.UTF_8));
                in.flush();
            }
            in.close();
            ChannelExec c = ch;
            watchdog = new Thread(() -> {
                try {
                    Thread.sleep(timeoutSeconds * 1000L);
                    c.disconnect();
                } catch (InterruptedException e) {
                    // finished in time
                }
            }, "logsift-ssh-timeout");
            watchdog.setDaemon(true);
            watchdog.start();
            byte[] bytes = out.readAllBytes();
            for (int i = 0; i < 100 && !ch.isClosed(); i++) {
                Thread.sleep(10); // the exit status arrives just after the output ends
            }
            if (!ch.isClosed()) {
                throw new IOException("the server did not answer in " + timeoutSeconds + " s");
            }
            return new Result(ch.getExitStatus(), new String(bytes, StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } catch (JSchException e) {
            throw new IOException(RemoteMirror.describe(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } finally {
            if (watchdog != null) {
                watchdog.interrupt();
            }
            ch.disconnect();
        }
    }

    /** A word for the shell, in single quotes. */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ---------------------------------------------------------------- listing

    /** The JVMs on the server (of every user), newest first. */
    public List<JvmProcess> list() throws IOException {
        Result r = exec("ps -eo pid=,user=,etime=,args= 2>/dev/null || ps -eo pid,user,etime,args; "
                + "echo @@HS@@; ls -1 /tmp/hsperfdata_* 2>/dev/null", null, 30);
        String[] parts = r.out().split("@@HS@@", 2);
        Set<Long> perf = new HashSet<>();
        if (parts.length > 1) {
            for (String l : parts[1].split("\n")) {
                try {
                    perf.add(Long.parseLong(l.trim()));
                } catch (NumberFormatException e) {
                    // a folder name
                }
            }
        }
        Map<Long, String[]> found = new LinkedHashMap<>();
        for (String l : parts[0].split("\n")) {
            String[] f = l.trim().split("\\s+", 4);
            if (f.length < 4) {
                continue;
            }
            long pid;
            try {
                pid = Long.parseLong(f[0]);
            } catch (NumberFormatException e) {
                continue; // the header line
            }
            if (perf.contains(pid) || JvmProcess.isJava(f[3])) {
                found.put(pid, f);
            }
        }
        Set<Long> inContainer = new HashSet<>();
        if (!found.isEmpty()) {
            StringBuilder sb = new StringBuilder("for p in");
            found.keySet().forEach(p -> sb.append(' ').append(p));
            sb.append("; do grep -qsE 'docker|kubepods|containerd|libpod|lxc' /proc/$p/cgroup && echo $p; done; true");
            for (String l : exec(sb.toString(), null, 30).out().split("\n")) {
                try {
                    inContainer.add(Long.parseLong(l.trim()));
                } catch (NumberFormatException e) {
                    // nothing
                }
            }
        }
        List<JvmProcess> out = new ArrayList<>();
        found.forEach((pid, f) -> out.add(new JvmProcess(pid, JvmProcess.describe(f[3]), f[3], f[1],
                uptime(f[2]), inContainer.contains(pid))));
        out.sort((a, b) -> Long.compare(b.pid(), a.pid()));
        return out;
    }

    /** {@code ps}'s elapsed time ({@code 2-03:04:05}, {@code 04:05}) in words. */
    static String uptime(String etime) {
        try {
            long days = 0;
            String t = etime;
            int dash = t.indexOf('-');
            if (dash > 0) {
                days = Long.parseLong(t.substring(0, dash));
                t = t.substring(dash + 1);
            }
            String[] p = t.split(":");
            long s = 0;
            for (String x : p) {
                s = s * 60 + Long.parseLong(x);
            }
            return ThreadAnalysis.duration((days * 86400 + s) * 1000);
        } catch (NumberFormatException e) {
            return etime;
        }
    }

    // ---------------------------------------------------------------- dumps

    /** A thread dump of the JVM. */
    public ThreadDump dump(JvmProcess p, Options o) throws IOException {
        String text = diagnostic(p, o, "Thread.print", "-l");
        List<ThreadDump> d = DumpParser.parse(text);
        if (d.isEmpty()) {
            throw new IOException("the answer was not a thread dump: " + LocalJvms.first(text));
        }
        return new ThreadDump(System.currentTimeMillis(), d.get(0).vm(), d.get(0).threads(), -1);
    }

    /**
     * Runs a diagnostic command in the JVM ({@code Thread.print -l}, {@code JFR.start …}) and
     * returns its text: with jcmd if there is one, else with async-profiler.
     */
    public String diagnostic(JvmProcess p, Options o, String... command) throws IOException {
        List<String> problems = new ArrayList<>();
        String jcmd = jcmd(p, o);
        if (jcmd != null) {
            Result r = run(p, o, false, q(jcmd) + " " + p.pid() + " " + words(command), 120);
            if (r.exit() == 0 && !r.out().isBlank()) {
                return r.out();
            }
            problems.add("jcmd: " + r.message());
        }
        String asprof = asprof();
        if (asprof != null) {
            Result r = run(p, o, true, q(asprof) + " jcmd " + p.pid() + " " + words(command), 120);
            if (r.exit() == 0 && !r.out().isBlank()) {
                return r.out();
            }
            problems.add("async-profiler: " + r.message());
        } else {
            problems.add("no async-profiler for this server (" + platform() + ")");
        }
        if (jcmd == null) {
            problems.add(0, o.jdkHome().isEmpty() ? "no jcmd found (set the JDK folder)"
                    : "no jcmd in " + o.jdkHome());
        }
        throw new IOException(String.join("; ", problems));
    }

    private static String words(String... command) {
        StringBuilder sb = new StringBuilder();
        for (String c : command) {
            sb.append(sb.length() == 0 ? "" : " ").append(q(c));
        }
        return sb.toString();
    }

    /**
     * Runs a command for a JVM: as the login user, or with sudo for another user's JVM. With
     * {@code asRoot}, sudo runs it as root (async-profiler then takes the JVM's user itself).
     */
    Result run(JvmProcess p, Options o, boolean asRoot, String command, int timeoutSeconds) throws IOException {
        boolean other = !p.user().isEmpty() && !p.user().equals(user);
        if (!other && !p.container() || !o.sudo()) {
            return exec(command, null, timeoutSeconds);
        }
        String as = asRoot || p.container() ? "" : "-u " + q(p.user()) + " ";
        Result r;
        if (o.sudoPassword() == null || o.sudoPassword().isEmpty()) {
            r = exec("sudo -n " + as + command, null, timeoutSeconds);
        } else {
            r = exec("sudo -S -p '' " + as + command, o.sudoPassword() + "\n", timeoutSeconds);
        }
        String err = r.err().toLowerCase(java.util.Locale.ROOT);
        if (r.exit() != 0 && (err.contains("password is required") || err.contains("incorrect password")
                || err.contains("a terminal is required") || err.contains("sorry, try again"))) {
            throw new SudoPasswordNeeded("sudo needs a password to reach the JVMs of " + p.user());
        }
        return r;
    }

    /** The jcmd to use: in the JDK folder given, on the PATH, or next to the JVM's own java. */
    String jcmd(JvmProcess p, Options o) throws IOException {
        if (o.jdkHome().isEmpty() && jcmdFound != null) {
            return jcmdFound;
        }
        StringBuilder sb = new StringBuilder("J=; ");
        if (!o.jdkHome().isEmpty()) {
            String h = o.jdkHome();
            sb.append("for c in ").append(q(h + "/bin/jcmd")).append(' ').append(q(h + "/jcmd")).append(' ')
                    .append(q(h)).append("; do if [ -f \"$c\" ] && [ -x \"$c\" ]; then J=\"$c\"; break; fi; done; ");
            sb.append("echo \"$J\"");
            String found = exec(sb.toString(), null, 30).out().trim();
            return found.isEmpty() ? null : found;
        }
        sb.append("J=$(command -v jcmd 2>/dev/null); ");
        sb.append("if [ -z \"$J\" ]; then E=$(readlink -f /proc/").append(p.pid())
                .append("/exe 2>/dev/null); D=$(dirname \"$E\" 2>/dev/null); ")
                .append("if [ -n \"$E\" ] && [ -x \"$D/jcmd\" ]; then J=\"$D/jcmd\"; fi; fi; ");
        sb.append("echo \"$J\"");
        String found = exec(sb.toString(), null, 30).out().trim();
        if (found.isEmpty()) {
            return null;
        }
        if (!found.contains("/proc/")) {
            jcmdFound = found; // on the PATH: the same for every JVM
        }
        return found;
    }

    /** "linux-x64", "linux-arm64", "macos", or "Linux s390x"-like text for others. */
    public String platform() throws IOException {
        if (platform == null) {
            String[] u = exec("uname -s; uname -m", null, 30).out().trim().split("\\s+");
            String p = u.length >= 2 ? Agents.platform(u[0], u[1]) : null;
            platform = p != null ? p : String.join(" ", u);
        }
        return platform;
    }

    /** async-profiler's launcher on the server (copied there first), or null if it can't run there. */
    public synchronized String asprof() throws IOException {
        String p = platform();
        if (!Agents.has(p)) {
            return null;
        }
        String dir = "/tmp/logsift-" + user.replaceAll("[^A-Za-z0-9._-]", "_") + "-async-profiler-" + Agents.VERSION
                + "/" + p;
        StringBuilder check = new StringBuilder();
        for (String f : Agents.files(p)) {
            check.append("wc -c < ").append(q(dir + "/" + f)).append(" 2>/dev/null || echo 0; ");
        }
        String[] sizes = exec(check.toString(), null, 30).out().trim().split("\\s+");
        boolean present = sizes.length == Agents.files(p).size();
        for (int i = 0; present && i < sizes.length; i++) {
            present = sizes[i].trim().equals(String.valueOf(Agents.size(p, Agents.files(p).get(i))));
        }
        if (!present) {
            upload(p, dir);
        }
        return dir + "/bin/asprof";
    }

    /** Copies a file from the server. */
    public void download(String remote, Path local, java.util.function.DoubleConsumer progress) throws IOException {
        download(remote, local, progress, null);
    }

    /**
     * Copies a file from the server; {@code cancel} stops the copy (the part copied is removed,
     * and a CancellationException is thrown).
     */
    public void download(String remote, Path local, java.util.function.DoubleConsumer progress,
                         java.util.function.BooleanSupplier cancel) throws IOException {
        boolean stopped = false;
        try {
            stopped = copy(remote, local, progress, cancel);
        } finally {
            if (stopped) {
                java.nio.file.Files.deleteIfExists(local);
            }
        }
        if (stopped) {
            throw new java.util.concurrent.CancellationException("the copy of " + remote + " was stopped");
        }
    }

    /** True when it was stopped. */
    private boolean copy(String remote, Path local, java.util.function.DoubleConsumer progress,
                         java.util.function.BooleanSupplier cancel) throws IOException {
        boolean[] stopped = {false};
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15_000);
            long size = Math.max(1, sftp.stat(remote).getSize());
            try (OutputStream out = java.nio.file.Files.newOutputStream(local)) {
                sftp.get(remote, out, progress == null && cancel == null ? null : new com.jcraft.jsch.SftpProgressMonitor() {
                    private long done;

                    @Override
                    public void init(int op, String src, String dest, long max) {
                    }

                    @Override
                    public boolean count(long n) {
                        done += n;
                        if (progress != null) {
                            progress.accept(Math.min(1, done / (double) size));
                        }
                        if (cancel != null && cancel.getAsBoolean()) {
                            stopped[0] = true;
                            return false; // stops the transfer
                        }
                        return true;
                    }

                    @Override
                    public void end() {
                    }
                });
            }
        } catch (JSchException | SftpException e) {
            if (stopped[0]) {
                return true;
            }
            throw new IOException("cannot copy " + remote + " from the server: " + RemoteMirror.describe(e), e);
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
        }
        return stopped[0] || (cancel != null && cancel.getAsBoolean());
    }

    /** Copies a local file to the server (made readable by its owner only). */
    public void upload(Path local, String remote) throws IOException {
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15_000);
            try (InputStream in = java.nio.file.Files.newInputStream(local)) {
                sftp.put(in, remote + ".part", ChannelSftp.OVERWRITE);
            }
            sftp.chmod(0600, remote + ".part");
            sftp.rename(remote + ".part", remote);
        } catch (JSchException | SftpException e) {
            throw new IOException("cannot copy " + local.getFileName() + " to the server: " + RemoteMirror.describe(e),
                    e);
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
        }
    }

    /** A connection to a port on the server's own 127.0.0.1, through SSH. */
    public logsift.heap.RemoteHeap.Link tunnel(int port) throws IOException {
        try {
            com.jcraft.jsch.ChannelDirectTCPIP ch = (com.jcraft.jsch.ChannelDirectTCPIP) session.openChannel("direct-tcpip");
            ch.setHost("127.0.0.1");
            ch.setPort(port);
            InputStream in = ch.getInputStream();
            OutputStream out = ch.getOutputStream();
            ch.connect(15_000);
            return new logsift.heap.RemoteHeap.Link() {
                @Override
                public InputStream in() {
                    return in;
                }

                @Override
                public OutputStream out() {
                    return out;
                }

                @Override
                public void close() {
                    ch.disconnect();
                }
            };
        } catch (JSchException e) {
            throw new IOException("cannot reach port " + port + " on the server through SSH: "
                    + RemoteMirror.describe(e), e);
        }
    }

    private void upload(String p, String dir) throws IOException {
        Result mk = exec("mkdir -p " + q(dir + "/bin") + " " + q(dir + "/lib") + " && chmod 755 "
                + q(dir.substring(0, dir.lastIndexOf('/'))) + " " + q(dir) + " " + q(dir + "/bin") + " "
                + q(dir + "/lib"), null, 30);
        if (mk.exit() != 0) {
            throw new IOException("cannot make " + dir + " on the server: " + mk.message());
        }
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15_000);
            for (String f : Agents.files(p)) {
                try (InputStream in = Agents.open(p, f)) {
                    sftp.put(in, dir + "/" + f, ChannelSftp.OVERWRITE);
                }
                sftp.chmod(0755, dir + "/" + f);
            }
        } catch (JSchException | SftpException e) {
            throw new IOException("cannot copy async-profiler to the server: " + RemoteMirror.describe(e), e);
        } finally {
            if (sftp != null) {
                sftp.disconnect();
            }
        }
    }
}
