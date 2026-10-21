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
        Thread t = new Thread(() -> si.serve(secret, onOpen), "logsift-single-instance");
        t.setDaemon(true);
        t.start();
        return si;
    }

    private void serve(String secret, Consumer<List<Path>> onOpen) {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(3000);
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                if (!MAGIC.equals(r.readLine()) || !secret.equals(r.readLine())) {
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
