package logsift.jvm;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import logsift.core.RemoteMirror;

/**
 * Copies a big file (a heap dump) from one server to another, so it can be read off a production
 * machine. Directly when the second machine can sign in to the first by itself (its own SSH key or
 * agent: it runs {@code ssh … cat}); else through this computer. Either way the reading on the
 * first machine runs at low priority.
 */
public final class RemoteCopy {
    private RemoteCopy() {
    }

    /** The second machine can't sign in to the first by itself. */
    public static final class NoDirectWay extends IOException {
        NoDirectWay(String message) {
            super(message);
        }
    }

    /** The command that writes the file, gently: low CPU and disk priority where there is ionice. */
    static String gentleCat(String path) {
        String cat = "nice -n 19 cat " + SshJvms.q(path);
        return "if command -v ionice >/dev/null 2>&1; then exec ionice -c2 -n7 " + cat + "; else exec " + cat + "; fi";
    }

    /**
     * Has {@code to} fetch the file itself from {@code from} (with {@code ssh} and no password
     * prompt). Throws {@link NoDirectWay} when it can't sign in.
     */
    public static void direct(SshJvms to, RemoteMirror.Target from, String path, String dest, long size,
                              DoubleConsumer progress, BooleanSupplier cancel) throws IOException {
        String fetch = "ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15 -p "
                + from.port() + " " + SshJvms.q(from.user() + "@" + from.host()) + " " + SshJvms.q(gentleCat(path))
                + " > " + SshJvms.q(dest + ".part") + " && mv " + SshJvms.q(dest + ".part") + " " + SshJvms.q(dest);
        SshJvms.Result r = to.exec("nohup sh -c " + SshJvms.q(fetch) + " > " + SshJvms.q(dest + ".copy.log")
                + " 2>&1 < /dev/null & echo $!", null, 30);
        long pid;
        try {
            pid = Long.parseLong(r.out().trim());
        } catch (NumberFormatException e) {
            throw new IOException("cannot start the copy on " + to.label() + ": " + r.message());
        }
        while (true) {
            if (cancel.getAsBoolean()) {
                to.exec("kill " + pid + " 2>/dev/null; pkill -P " + pid + " 2>/dev/null; rm -f "
                        + SshJvms.q(dest + ".part") + " " + SshJvms.q(dest + ".copy.log"), null, 30);
                throw new CancellationException();
            }
            SshJvms.Result s = to.exec("if kill -0 " + pid + " 2>/dev/null; then echo running $(wc -c < "
                    + SshJvms.q(dest + ".part") + " 2>/dev/null); elif [ -f " + SshJvms.q(dest)
                    + " ]; then echo done; else echo failed; cat " + SshJvms.q(dest + ".copy.log") + "; fi", null, 30);
            String out = s.out().trim();
            if (out.startsWith("done")) {
                to.exec("rm -f " + SshJvms.q(dest + ".copy.log"), null, 30);
                progress.accept(1);
                return;
            }
            if (out.startsWith("failed")) {
                String why = out.substring("failed".length()).trim();
                to.exec("rm -f " + SshJvms.q(dest + ".part") + " " + SshJvms.q(dest + ".copy.log"), null, 30);
                if (why.contains("Permission denied") || why.contains("Host key verification failed")
                        || why.contains("not found") || why.contains("Could not resolve") || why.contains("timed out")
                        || why.contains("Connection refused") || why.isEmpty()) {
                    throw new NoDirectWay(to.label() + " can't reach " + from.user() + "@" + from.host()
                            + " by itself" + (why.isEmpty() ? "" : " (" + why.lines().reduce((a, b) -> b).orElse(why)
                            + ")"));
                }
                throw new IOException("the copy on " + to.label() + " failed: " + why);
            }
            String[] w = out.split("\\s+");
            if (w.length > 1) {
                try {
                    progress.accept(Math.min(1, Long.parseLong(w[1]) / (double) Math.max(1, size)));
                } catch (NumberFormatException e) {
                    // not started yet
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
    }

    /** Copies the file through this computer: read from one server, written to the other. */
    public static void throughHere(SshJvms from, SshJvms to, String path, String dest, long size,
                                   DoubleConsumer progress, BooleanSupplier cancel) throws IOException {
        try (InputStream in = from.stream("sh -c " + SshJvms.q(gentleCat(path)))) {
            to.put(in, dest, n -> progress.accept(Math.min(1, n / (double) Math.max(1, size))), cancel);
        }
        SshJvms.Result r = to.exec("wc -c < " + SshJvms.q(dest), null, 30);
        if (!r.out().trim().equals(String.valueOf(size))) {
            to.exec("rm -f " + SshJvms.q(dest), null, 30);
            throw new IOException("the copy on " + to.label() + " is " + r.out().trim() + " bytes, not " + size);
        }
    }

    /** Free bytes on the disk of {@code folder} on the server (-1 if unknown). */
    public static long free(SshJvms ssh, String folder) throws IOException {
        SshJvms.Result r = ssh.exec("df -Pk " + SshJvms.q(folder) + " 2>/dev/null | awk 'NR==2 {print $4 * 1024}'",
                null, 30);
        try {
            return Long.parseLong(r.out().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
