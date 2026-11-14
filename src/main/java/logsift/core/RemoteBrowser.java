package logsift.core;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Vector;
import java.util.function.Predicate;

/**
 * Lists folders on another machine over SFTP, to pick a log to open. Every call on the channel
 * runs on one thread of its own: JSch's channel reads through a pipe that breaks ("Pipe closed")
 * when the thread that read last has ended and a reply comes before the next one reads, and
 * {@code realpath} doesn't say which thread reads.
 */
public final class RemoteBrowser implements AutoCloseable {

    /** A file or folder. {@code modified} is in epoch milliseconds. */
    public record Entry(String name, boolean dir, long size, long modified) {}

    private final Session session;
    private final ChannelSftp sftp;
    private final java.util.concurrent.ExecutorService one;

    private RemoteBrowser(Session session, ChannelSftp sftp, java.util.concurrent.ExecutorService one) {
        this.session = session;
        this.sftp = sftp;
        this.one = one;
    }

    private interface SftpCall<T> {
        T call() throws SftpException;
    }

    /** Runs {@code c} on the channel's own thread; its SftpException comes back as is. */
    private <T> T on(SftpCall<T> c) throws SftpException, IOException {
        try {
            return one.submit(() -> {
                try {
                    return c.call();
                } catch (SftpException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("stopped");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable t = e.getCause() instanceof java.util.concurrent.CompletionException ce ? ce.getCause() : e.getCause();
            if (t instanceof SftpException se) {
                throw se;
            }
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(t);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new IOException("closed");
        }
    }

    /** Connects (the target's path is not used). */
    public static RemoteBrowser connect(RemoteMirror.Target target, Path knownHosts,
                                        Predicate<String> trustNewHost) throws IOException {
        Session s = null;
        java.util.concurrent.ExecutorService one = java.util.concurrent.Executors.newSingleThreadExecutor(
                LogDocument.daemon("logsift-sftp"));
        try {
            s = RemoteMirror.openSession(target, knownHosts, trustNewHost);
            ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
            try {
                // connected on the thread that then does every call
                one.submit(() -> {
                    c.connect(15_000);
                    return null;
                }).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JSchException("stopped");
            } catch (java.util.concurrent.ExecutionException e) {
                throw e.getCause() instanceof JSchException je ? je : new JSchException(String.valueOf(e.getCause()), e.getCause());
            }
            return new RemoteBrowser(s, c, one);
        } catch (JSchException e) {
            one.shutdownNow();
            if (s != null) {
                s.disconnect();
            }
            throw new IOException(RemoteMirror.describe(e), e);
        }
    }

    /** The user's home folder on the server. */
    public synchronized String home() throws IOException {
        try {
            return on(sftp::getHome);
        } catch (SftpException e) {
            throw new IOException(RemoteMirror.describe(e), e);
        }
    }

    /**
     * The absolute form of a path typed by the user: "~" and "~/x" mean the home folder, a
     * relative path is taken from the home folder, and "..", "." and links are resolved.
     */
    public synchronized String resolve(String typed) throws IOException {
        String p = typed == null ? "" : typed.trim();
        if (p.isEmpty() || p.equals("~")) {
            return home();
        }
        if (p.startsWith("~/")) {
            p = home() + p.substring(1);
        } else if (!p.startsWith("/")) {
            p = home() + "/" + p;
        }
        try {
            String path = p;
            return on(() -> sftp.realpath(path));
        } catch (SftpException e) {
            throw new IOException(p + ": " + RemoteMirror.describe(e), e);
        }
    }

    public synchronized boolean isDirectory(String path) throws IOException {
        try {
            return on(() -> sftp.stat(path).isDir()); // follows links
        } catch (SftpException e) {
            throw new IOException(path + ": " + RemoteMirror.describe(e), e);
        }
    }

    /** The entries of a folder: folders first, then files, each by name (ignoring case). */
    public synchronized List<Entry> list(String dir) throws IOException {
        Vector<ChannelSftp.LsEntry> raw;
        try {
            raw = on(() -> sftp.ls(dir));
        } catch (SftpException e) {
            throw new IOException(dir + ": " + RemoteMirror.describe(e), e);
        }
        List<Entry> out = new ArrayList<>();
        for (ChannelSftp.LsEntry e : raw) {
            String name = e.getFilename();
            if (name.equals(".") || name.equals("..")) {
                continue;
            }
            SftpATTRS a = e.getAttrs();
            boolean isDir = a.isDir();
            if (a.isLink()) {
                try {
                    isDir = on(() -> sftp.stat(dir + "/" + name).isDir()); // where the link points
                } catch (SftpException | IOException ex) {
                    // a broken link: show it as a file
                }
            }
            out.add(new Entry(name, isDir, a.getSize(), a.getMTime() * 1000L));
        }
        out.sort(Comparator.comparing((Entry e) -> !e.dir())
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** The parent folder of an absolute path ("/" stays "/"). */
    public static String parent(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) {
            return "/";
        }
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }

    public static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    @Override
    public synchronized void close() {
        one.shutdownNow();
        sftp.disconnect();
        session.disconnect();
    }
}
