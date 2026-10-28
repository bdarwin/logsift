package logsift.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps one Logsift window per user: a second launch (double-clicking a log, "Open with",
 * the command line) hands its files to the running one and exits. The running instance
 * listens on a local-only port; the port and a random secret are in a file only the user can
 * read, so other users or programs can't send it files.
 */
public final class SingleInstance implements AutoCloseable {
    private static final String MAGIC = "logsift-open/1";
    private static final String COMMAND = "logsift-command/1";

    /**
     * A request from the command line that waits for its answer: {@code diff a b} or
     * {@code merge local remote base merged}. The instance calls {@code reply} with the exit code
     * when it is done (the comparison closed, the merge saved or given up).
     */
    public interface Commands {
        void run(List<String> command, java.util.function.IntConsumer reply);
    }

    private final ServerSocket server;
    private final Path file;

    private SingleInstance(ServerSocket server, Path file) {
        this.server = server;
        this.file = file;
    }

    /**
     * Sends {@code paths} to a running instance. Returns true if one took them (this process
     * should then exit), false if none is running.
     */
    public static boolean forward(Path home, List<Path> paths) {
        Path f = home.resolve("instance");
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                return false;
            }
            int port = Integer.parseInt(lines.get(0).trim());
            String secret = lines.get(1).trim();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
                s.setSoTimeout(3000);
                Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                w.write(MAGIC + "\n" + secret + "\n");
                for (Path p : paths) {
                    w.write(p.toAbsolutePath().toString() + "\n");
                }
                w.write("\n");
                w.flush();
                String reply = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                        .readLine();
                return "ok".equals(reply);
            }
        } catch (IOException | RuntimeException e) {
            return false; // no instance (or a stale file): start normally
        }
    }

    /**
     * Becomes the running instance: files sent by later launches go to {@code onOpen} (called
     * on a background thread; an empty list means "just show the window").
     */
    public static SingleInstance listen(Path home, Consumer<List<Path>> onOpen) throws IOException {
        return listen(home, onOpen, null);
    }

    /**
     * Sends a command to a running instance and waits (as long as it takes) for its exit code.
     * Returns -1 if no instance is running.
     */
    public static int command(Path home, List<String> command) {
        Path f = home.resolve("instance");
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.size() < 2) {
                return -1;
            }
            int port = Integer.parseInt(lines.get(0).trim());
            String secret = lines.get(1).trim();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1000);
                Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                w.write(COMMAND + "\n" + secret + "\n");
                for (String c : command) {
                    w.write(c.replace("\n", " ") + "\n");
                }
                w.write("\n");
                w.flush();
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String accepted = r.readLine();
                if (!"ok".equals(accepted)) {
                    return -1;
                }
                String done = r.readLine(); // no timeout: a merge takes as long as it takes
                return done != null && done.startsWith("done ") ? Integer.parseInt(done.substring(5).trim()) : 1;
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Like {@link #listen(Path, Consumer)}, also taking commands that wait for an answer. */
    public static SingleInstance listen(Path home, Consumer<List<Path>> onOpen, Commands commands) throws IOException {
        Files.createDirectories(home);
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 10);
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String secret = HexFormat.of().formatHex(b);
        Path f = home.resolve("instance");
        Files.deleteIfExists(f);
        try {
            Files.createFile(f, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(f); // Windows: the user's profile folder is already private
        }
        Files.writeString(f, server.getLocalPort() + "\n" + secret + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        SingleInstance si = new SingleInstance(server, f);
        Thread t = new Thread(() -> si.serve(secret, onOpen, commands), "logsift-single-instance");
        t.setDaemon(true);
        t.start();
        return si;
    }

    private void serve(String secret, Consumer<List<Path>> onOpen, Commands commands) {
        while (!server.isClosed()) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                continue; // closed, or a broken connection
            }
            boolean keep = false;
            try {
                s.setSoTimeout(3000);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String magic = r.readLine();
                if (!secret.equals(r.readLine())) {
                    continue;
                }
                if (COMMAND.equals(magic) && commands != null) {
                    List<String> cmd = new ArrayList<>();
                    String line;
                    while ((line = r.readLine()) != null && !line.isEmpty() && cmd.size() < 20) {
                        cmd.add(line);
                    }
                    Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                    w.write("ok\n");
                    w.flush();
                    s.setSoTimeout(0);
                    keep = true; // the answer comes later: the connection stays open until then
                    java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
                    commands.run(cmd, code -> {
                        if (answered.compareAndSet(false, true)) {
                            try (s) {
                                w.write("done " + code + "\n");
                                w.flush();
                            } catch (IOException e) {
                                // the command line went away
                            }
                        }
                    });
                    continue;
                }
                if (!MAGIC.equals(magic)) {
                    continue;
                }
                List<Path> paths = new ArrayList<>();
                String line;
                while ((line = r.readLine()) != null && !line.isEmpty() && paths.size() < 1000) {
                    paths.add(Path.of(line));
                }
                Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                w.write("ok\n");
                w.flush();
                onOpen.accept(paths);
            } catch (IOException | RuntimeException e) {
                // a broken or foreign connection: ignore it and keep listening
            } finally {
                if (!keep) {
                    try {
                        s.close();
                    } catch (IOException e) {
                        // already closed
                    }
                }
            }
        }
    }

    @Override
    public void close() {
        try {
            server.close();
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // best effort
        }
    }
}
