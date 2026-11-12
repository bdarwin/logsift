package logsift.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An office's proxy, for tests: passes plain HTTP on and tunnels HTTPS (CONNECT), wanting a name
 * and password first when given one, or refusing every tunnel with a status.
 */
final class TestProxy implements AutoCloseable {
    private final ServerSocket server;
    /** The request lines it was sent ("GET http://…", "CONNECT host:port"), and whether they were let through. */
    final List<String> seen = new CopyOnWriteArrayList<>();
    /** "name:password" it wants, or null. */
    volatile String wants;
    /** A status every CONNECT gets instead (403: "not allowed here"), or 0. */
    volatile int refuseTunnels;

    TestProxy() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(this::accept, "test-proxy");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return server.getLocalPort();
    }

    java.net.Proxy proxy() {
        return new java.net.Proxy(java.net.Proxy.Type.HTTP,
                new java.net.InetSocketAddress("127.0.0.1", port()));
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "test-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            while (true) {
                List<String> head = readHead(in);
                if (head == null) {
                    return;
                }
                String line = head.get(0);
                String[] parts = line.split(" ");
                String auth = header(head, "Proxy-Authorization");
                boolean signedIn = wants == null || ("Basic " + Base64.getEncoder()
                        .encodeToString(wants.getBytes(StandardCharsets.UTF_8))).equals(auth);
                seen.add(line + (signedIn ? "" : " (no sign-in)"));
                if (!signedIn) {
                    out.write(("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"office\"\r\n"
                            + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    skipBody(in, head);
                    continue; // the same connection, signed in this time
                }
                if (parts[0].equals("CONNECT")) {
                    if (refuseTunnels > 0) {
                        out.write(("HTTP/1.1 " + refuseTunnels + " Forbidden\r\nContent-Length: 0\r\n\r\n")
                                .getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        return;
                    }
                    String[] hp = parts[1].split(":");
                    try (Socket to = new Socket(hp[0], Integer.parseInt(hp[1]))) {
                        out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                        out.flush();
                        pipe(client, to);
                    }
                    return;
                }
                // plain HTTP: GET http://host:port/path, passed on with only the path
                java.net.URI u = java.net.URI.create(parts[1]);
                try (Socket to = new Socket(u.getHost(), u.getPort() < 0 ? 80 : u.getPort())) {
                    StringBuilder b = new StringBuilder(parts[0] + " " + u.getRawPath()
                            + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()) + " HTTP/1.1\r\n");
                    for (String h : head.subList(1, head.size())) {
                        String k = h.toLowerCase(java.util.Locale.ROOT);
                        if (!k.startsWith("proxy-") && !k.startsWith("connection:")) {
                            b.append(h).append("\r\n");
                        }
                    }
                    b.append("Connection: close\r\n\r\n");
                    to.getOutputStream().write(b.toString().getBytes(StandardCharsets.ISO_8859_1));
                    to.getOutputStream().flush();
                    to.getInputStream().transferTo(out);
                    out.flush();
                }
                return; // the origin closed: so does the proxy
            }
        } catch (IOException e) {
            // the client went away
        }
    }

    private static void pipe(Socket a, Socket b) throws IOException {
        Thread back = new Thread(() -> {
            try {
                b.getInputStream().transferTo(a.getOutputStream());
            } catch (IOException e) {
                // closed
            } finally {
                try {
                    a.shutdownOutput();
                } catch (IOException e) {
                    // closed
                }
            }
        }, "test-proxy-back");
        back.setDaemon(true);
        back.start();
        try {
            a.getInputStream().transferTo(b.getOutputStream());
        } catch (IOException e) {
            // closed
        }
        try {
            back.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> readHead(InputStream in) throws IOException {
        List<String> lines = new java.util.ArrayList<>();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                String l = line.toString(StandardCharsets.ISO_8859_1).replaceAll("\r$", "");
                line.reset();
                if (l.isEmpty()) {
                    return lines.isEmpty() ? null : lines;
                }
                lines.add(l);
            } else {
                line.write(c);
            }
        }
        return null;
    }

    private static String header(List<String> head, String name) {
        for (String h : head.subList(1, head.size())) {
            int c = h.indexOf(':');
            if (c > 0 && h.substring(0, c).strip().equalsIgnoreCase(name)) {
                return h.substring(c + 1).strip();
            }
        }
        return null;
    }

    private static void skipBody(InputStream in, List<String> head) throws IOException {
        String n = header(head, "Content-Length");
        if (n != null) {
            in.readNBytes(Integer.parseInt(n));
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
