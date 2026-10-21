package logsift.core;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;

/**
 * A local copy of a log on another machine, kept up to date over SFTP (SSH). Logsift opens the
 * copy like any local file, so every feature works on remote logs. New bytes are fetched with
 * offset reads; if the remote file gets smaller (truncated or rotated) the copy starts over.
 * Connection problems are retried with growing pauses.
 */
public final class RemoteMirror implements AutoCloseable {

    /** Where and how to connect. Either a password or a private key (with optional passphrase). */
    public record Target(String host, int port, String user, String path, String password,
                         Path privateKey, String passphrase) {
        public String label() {
            return user + "@" + host + ":" + path;
        }
    }

    private final Target target;
    private final Path copy;
    private final Path knownHosts;
    private final Predicate<String> trustNewHost;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(LogDocument.daemon("logsift-remote"));
    private Session session;
    private ChannelSftp sftp;
    private long remoteOffset;   // bytes of the remote file the copy holds (up to a line end)
    private long start;          // where the copy started in the remote file
    private volatile String status = "connected";
    private volatile boolean closed;
    private int failures;
    private Consumer<String> statusListener = s -> { };

    private RemoteMirror(Target target, Path copy, Path knownHosts, Predicate<String> trustNewHost) {
        this.target = target;
        this.copy = copy;
        this.knownHosts = knownHosts;
        this.trustNewHost = trustNewHost;
    }

    /**
     * Connects and downloads the remote log (or only its last {@code lastBytes}, starting at a
     * line; 0 = all) into {@code copy}.
     *
     * @param knownHosts the known_hosts file (null: don't remember host keys)
     * @param trustNewHost asked with the key's fingerprint for a host not in known_hosts
     */
    public static RemoteMirror open(Target target, Path copy, long lastBytes, Path knownHosts,
                                    Predicate<String> trustNewHost, DoubleConsumer progress,
                                    BooleanSupplier cancelled) throws IOException {
        RemoteMirror m = new RemoteMirror(target, copy, knownHosts, trustNewHost);
        try {
            m.connect();
            long size = m.sftp.stat(target.path()).getSize();
            m.start = lastBytes > 0 ? Math.max(0, size - lastBytes) : 0;
            Files.write(copy, new byte[0]);
            m.remoteOffset = m.start;
            m.fetch(size, true, progress, cancelled);
            return m;
        } catch (JSchException | SftpException e) {
            m.close();
            throw new IOException(describe(e), e);
        } catch (IOException | RuntimeException e) {
            m.close();
            throw e;
        }
    }

    public Path copy() {
        return copy;
    }

    public Target target() {
        return target;
    }

    /** "connected", "reconnecting…" and similar, for the UI. */
    public String status() {
        return status;
    }

    public void onStatus(Consumer<String> listener) {
        this.statusListener = listener;
    }

    /** True when the copy starts at the beginning of the remote file. */
    public boolean isComplete() {
        return start == 0;
    }

    /** Keeps fetching what the remote log appends, checking every {@code millis} ms. */
    public void follow(long millis) {
        timer.scheduleWithFixedDelay(() -> {
            if (closed) {
                return;
            }
            try {
                update();
                if (failures > 0) {
                    failures = 0;
                    setStatus("connected");
                }
            } catch (IOException | JSchException | SftpException | RuntimeException e) {
                failures++;
                disconnect();
                setStatus("reconnecting… (" + describe(e) + ")");
                // wait longer after repeated failures (up to ~30 s)
                try {
                    Thread.sleep(Math.min(30_000, 1000L << Math.min(failures, 5)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    private void setStatus(String s) {
        status = s;
        statusListener.accept(s);
    }

    /** Fetches new bytes; starts over if the remote file got smaller. */
    synchronized void update() throws IOException, JSchException, SftpException {
        if (closed) {
            return;
        }
        if (sftp == null || !sftp.isConnected()) {
            connect();
        }
        SftpATTRS a = sftp.stat(target.path());
        long size = a.getSize();
        if (size < remoteOffset) {
            // truncated or rotated: start over from the beginning of the new file
            start = 0;
            remoteOffset = 0;
            Files.write(copy, new byte[0]);
        }
        if (size > remoteOffset) {
            fetch(size, false, null, () -> closed);
        }
    }

    /**
     * Appends remote bytes [remoteOffset, size) to the copy, up to the last complete line; an
     * incomplete last line is fetched again next time. On the first fetch of a tail ("last N
     * MB"), the cut first line is dropped.
     */
    private void fetch(long size, boolean first, DoubleConsumer progress, BooleanSupplier cancelled)
            throws IOException, SftpException {
        long want = size - remoteOffset;
        long done = 0;       // bytes read from the remote file
        long held = 0;       // bytes of an incomplete last line, not written
        boolean skipPartial = first && start > 0;
        try (InputStream in = sftp.get(target.path(), null, remoteOffset);
             OutputStream out = Files.newOutputStream(copy, StandardOpenOption.APPEND)) {
            byte[] buf = new byte[256 * 1024];
            java.io.ByteArrayOutputStream partial = new java.io.ByteArrayOutputStream();
            while (done < want && !cancelled.getAsBoolean()) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, want - done));
                if (n < 0) {
                    break;
                }
                done += n;
                int from = 0;
                if (skipPartial) {
                    int nl = ByteScan.indexOf(buf, 0, n, (byte) '\n');
                    if (nl < 0) {
                        continue; // still inside the cut first line
                    }
                    from = nl + 1;
                    skipPartial = false;
                }
                int last = ByteScan.lastIndexOf(buf, from, n, (byte) '\n');
                if (last >= 0) {
                    partial.writeTo(out);
                    partial.reset();
                    out.write(buf, from, last + 1 - from);
                    partial.write(buf, last + 1, n - last - 1);
                } else {
                    partial.write(buf, from, n - from);
                }
                if (progress != null) {
                    progress.accept((double) done / Math.max(1, want));
                }
            }
            held = partial.size();
        }
        // the copy ends at a line end; the held bytes are read again next time
        remoteOffset += done - held;
    }

    private void connect() throws JSchException, IOException {
        JSch jsch = new JSch();
        if (knownHosts != null) {
            if (!Files.exists(knownHosts)) {
                Files.createDirectories(knownHosts.getParent());
                Files.createFile(knownHosts);
            }
            jsch.setKnownHosts(knownHosts.toString());
        }
        if (target.privateKey() != null) {
            jsch.addIdentity(target.privateKey().toString(), target.passphrase());
        }
        Session s = jsch.getSession(target.user(), target.host(), target.port());
        if (target.password() != null && !target.password().isEmpty()) {
            s.setPassword(target.password());
        }
        s.setConfig("StrictHostKeyChecking", knownHosts == null ? "no" : "ask");
        s.setUserInfo(new UserInfo() {
            @Override
            public String getPassphrase() {
                return target.passphrase();
            }

            @Override
            public String getPassword() {
                return target.password();
            }

            @Override
            public boolean promptPassword(String message) {
                return target.password() != null;
            }

            @Override
            public boolean promptPassphrase(String message) {
                return target.passphrase() != null;
            }

            @Override
            public boolean promptYesNo(String message) {
                // JSch asks this for an unknown host key (message has the fingerprint)
                return trustNewHost.test(message);
            }

            @Override
            public void showMessage(String message) {
            }
        });
        s.setServerAliveInterval(15_000);
        s.connect(15_000);
        ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
        c.connect(15_000);
        session = s;
        sftp = c;
    }

    private void disconnect() {
        if (sftp != null) {
            sftp.disconnect();
        }
        if (session != null) {
            session.disconnect();
        }
        sftp = null;
        session = null;
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (e instanceof SftpException se && se.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
            return "no such file";
        }
        if (m == null) {
            return e.getClass().getSimpleName();
        }
        if (m.contains("Auth fail") || m.contains("Auth cancel")) {
            return "login failed (check user, password or key)";
        }
        return m;
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdown();
        disconnect();
    }
}
