package logsift.heap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads a heap dump on a server and answers the app's questions about it, so a big dump is read
 * where it is (or on a machine with room) and only answers cross the network.
 *
 * <pre>java -cp logsift-heap.jar logsift.heap.HeapServer --dump DUMP --work INDEX_DIR --state STATE_DIR [--idle MINUTES]</pre>
 *
 * It listens on 127.0.0.1 only (the app reaches it through SSH) on a free port, and writes the port
 * and a secret token to {@code STATE_DIR/server} (readable by its user only): a client must send
 * the token first. It keeps running when the app disconnects, so a dropped connection doesn't
 * lose a long reading, and ends (removing its index) when told to or after {@code MINUTES}
 * (default 30) with no one connected.
 */
public final class HeapServer {
    private final Path dumpFile;
    private final Path work;
    private final Path state;
    private final long idleMillis;
    private final String token;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicInteger clients = new AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "logsift-heap-request");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile String stage = "Starting";
    private volatile double fraction;
    private volatile HeapDump dump;
    private volatile String failure;
    private ServerSocket server;

    HeapServer(Path dumpFile, Path work, Path state, long idleMillis) {
        this.dumpFile = dumpFile;
        this.work = work;
        this.state = state;
        this.idleMillis = idleMillis;
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        token = sb.toString();
    }

    public static void main(String[] args) throws Exception {
        String dump = null;
        String work = null;
        String state = null;
        long idle = 30;
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--dump":
                    dump = args[i + 1];
                    break;
                case "--work":
                    work = args[i + 1];
                    break;
                case "--state":
                    state = args[i + 1];
                    break;
                case "--idle":
                    idle = Long.parseLong(args[i + 1]);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (dump == null || work == null || state == null) {
            System.err.println("usage: HeapServer --dump DUMP --work INDEX_DIR --state STATE_DIR [--idle MINUTES]");
            System.exit(2);
        }
        new HeapServer(Paths.get(dump), Paths.get(work), Paths.get(state), idle * 60_000L).run();
    }

    void run() throws IOException {
        Files.createDirectories(state);
        ownerOnly(state, "rwx------");
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        server.setSoTimeout(15_000);
        writeState();
        Thread loader = new Thread(this::load, "logsift-heap-load");
        loader.setDaemon(true);
        loader.start();
        try {
            while (!stopping.get()) {
                try {
                    Socket s;
                    try {
                        s = server.accept();
                    } catch (java.net.SocketException e) {
                        if (stopping.get()) {
                            break; // told to stop: the socket was closed
                        }
                        throw e;
                    }
                    lastActivity = System.currentTimeMillis();
                    Thread t = new Thread(() -> serve(s), "logsift-heap-client");
                    t.setDaemon(true);
                    t.start();
                } catch (SocketTimeoutException e) {
                    if (clients.get() == 0 && System.currentTimeMillis() - lastActivity > idleMillis) {
                        log("no one connected for " + idleMillis / 60_000 + " minutes: ending");
                        break;
                    }
                }
            }
        } finally {
            shutDown();
        }
    }

    /** Makes the main loop end (and clean up). */
    private void requestStop() {
        stopping.set(true);
        try {
            server.close();
        } catch (IOException e) {
            // closing
        }
    }

    /**
     * Ends. Nothing is removed by name: the index files are temporary files the reader opened
     * itself (gone when it ends, like swap), and the state file stays, saying the reader stopped.
     */
    private void shutDown() {
        requestStop();
        // the dump isn't closed here: closing would also tidy the index folder by name; on exit the
        // JVM drops the temporary index files it opened (and nothing else)
        try {
            Path tmp = state.resolve("server.tmp");
            Files.write(tmp, "stopped\n".getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, state.resolve("server"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // the next start writes it anew
        }
        pool.shutdownNow();
        System.exit(0);
    }

    private void writeState() throws IOException {
        String pid = ManagementFactory.getRuntimeMXBean().getName();
        int at = pid.indexOf('@');
        String text = server.getLocalPort() + " " + token + " " + (at > 0 ? pid.substring(0, at) : pid) + "\n"
                + dumpFile.toAbsolutePath() + "\n";
        Path tmp = state.resolve("server.tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        ownerOnly(tmp, "rw-------");
        Files.move(tmp, state.resolve("server"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void ownerOnly(Path p, String perms) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        } catch (UnsupportedOperationException | IOException e) {
            // not a POSIX file system (Windows)
        }
    }

    private void load() {
        try {
            dump = HeapDump.open(dumpFile, work, (s, f) -> {
                stage = s;
                fraction = f;
            }, stopping::get);
            stage = "Done";
            fraction = 1;
        } catch (Throwable e) {
            failure = e.getMessage() == null ? e.toString() : e.getMessage();
            stage = "Failed";
            log("could not read " + dumpFile + ": " + failure);
        }
    }

    private static void log(String s) {
        System.err.println("logsift-heap: " + s);
    }

    // ---------------------------------------------------------------- a client

    private void serve(Socket s) {
        clients.incrementAndGet();
        Map<Integer, AtomicBoolean> cancels = new ConcurrentHashMap<>();
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
            if (!token.equals(Wire.str(in))) {
                log("a connection without the token: closed");
                return;
            }
            out.writeInt(Wire.VERSION);
            out.flush();
            while (true) {
                int id = in.readInt();
                String method = Wire.str(in);
                byte[] args = new byte[in.readInt()];
                in.readFully(args);
                lastActivity = System.currentTimeMillis();
                if ("cancel".equals(method)) {
                    AtomicBoolean c = cancels.get(Wire.in(args).readInt());
                    if (c != null) {
                        c.set(true);
                    }
                    continue;
                }
                if ("stop".equals(method)) {
                    answer(out, id, Wire.RESULT, new byte[0]);
                    log("told to stop");
                    requestStop();
                    return;
                }
                AtomicBoolean cancel = new AtomicBoolean();
                cancels.put(id, cancel);
                pool.execute(() -> {
                    try {
                        Wire.Out o = new Wire.Out();
                        handle(method, Wire.in(args), o.o, (st, f) -> progress(out, id, st, f), cancel);
                        o.o.flush();
                        answer(out, id, Wire.RESULT, o.bytes.toByteArray());
                    } catch (Throwable e) {
                        try {
                            Wire.Out o = new Wire.Out();
                            Wire.str(o.o, e.getMessage() == null ? e.toString() : e.getMessage());
                            o.o.flush();
                            answer(out, id, Wire.ERROR, o.bytes.toByteArray());
                        } catch (IOException e2) {
                            // the client went away
                        }
                    } finally {
                        cancels.remove(id);
                        lastActivity = System.currentTimeMillis();
                    }
                });
            }
        } catch (EOFException e) {
            // the client went away
        } catch (IOException e) {
            log("connection: " + e.getMessage());
        } finally {
            for (AtomicBoolean c : cancels.values()) {
                c.set(true);
            }
            clients.decrementAndGet();
            lastActivity = System.currentTimeMillis();
        }
    }

    private static void answer(DataOutputStream out, int id, byte kind, byte[] payload) throws IOException {
        synchronized (out) {
            out.writeInt(id);
            out.writeByte(kind);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        }
    }

    private static void progress(DataOutputStream out, int id, String stage, double f) {
        try {
            Wire.Out o = new Wire.Out();
            Wire.str(o.o, stage);
            o.o.writeDouble(f);
            o.o.flush();
            answer(out, id, Wire.PROGRESS, o.bytes.toByteArray());
        } catch (IOException e) {
            // the client went away
        }
    }

    private HeapDump ready() throws IOException {
        HeapDump d = dump;
        if (d == null) {
            throw new IOException(failure != null ? failure : "the dump is still being read");
        }
        return d;
    }

    private void handle(String method, DataInputStream a, DataOutputStream o, HeapDump.Listener progress,
                        AtomicBoolean cancel) throws IOException {
        switch (method) {
            case "status": {
                o.writeByte(dump != null ? 1 : failure != null ? 2 : 0);
                Wire.str(o, stage);
                o.writeDouble(fraction);
                Wire.str(o, failure);
                break;
            }
            case "header": {
                HeapDump d = ready();
                Wire.str(o, d.fileName());
                Wire.str(o, d.location());
                o.writeLong(d.fileBytes());
                o.writeLong(d.timestamp());
                o.writeBoolean(d.compressedPointers());
                o.writeBoolean(d.isOnDisk());
                o.writeInt(d.objectCount());
                o.writeInt(d.reachableCount());
                o.writeLong(d.totalBytes());
                o.writeLong(d.reachableBytes());
                o.writeInt(d.root());
                o.writeInt(d.threadCount());
                Wire.info(o, d, d.root());
                break;
            }
            case "info": {
                HeapDump d = ready();
                Wire.infos(o, d, Wire.ints(a));
                break;
            }
            case "held": {
                HeapDump d = ready();
                int[] kids = d.held(a.readInt(), a.readInt());
                Wire.ints(o, kids);
                Wire.infos(o, d, kids);
                break;
            }
            case "path": {
                HeapDump d = ready();
                List<HeapDump.Step> steps = d.pathFromRoot(a.readInt());
                o.writeInt(steps.size());
                int[] nodes = new int[steps.size()];
                for (int i = 0; i < steps.size(); i++) {
                    o.writeInt(steps.get(i).node());
                    Wire.str(o, steps.get(i).via());
                    nodes[i] = steps.get(i).node();
                }
                Wire.infos(o, d, nodes);
                break;
            }
            case "objectsOf": {
                HeapDump d = ready();
                int[] top = d.objectsOf(Wire.str(a), a.readInt());
                Wire.ints(o, top);
                Wire.infos(o, d, top);
                break;
            }
            case "classes":
                Wire.classes(o, ready().classes());
                break;
            case "threads":
                Wire.str(o, ready().threadDumpText());
                break;
            case "findings": {
                HeapDump d = ready();
                List<HeapFindings.Finding> f = d.findings();
                Wire.findings(o, f);
                List<Integer> nodes = new ArrayList<>();
                for (HeapFindings.Finding x : f) {
                    if (x.node() >= 0) {
                        nodes.add(x.node());
                    }
                }
                Wire.infos(o, d, toArray(nodes));
                break;
            }
            case "waste":
                Wire.waste(o, ready().waste(progress, cancel::get));
                break;
            case "map": {
                HeapDump d = ready();
                HeapMap.Box b = d.map(a.readInt());
                Wire.box(o, b);
                List<Integer> nodes = new ArrayList<>();
                Wire.nodesOf(b, nodes);
                Wire.infos(o, d, toArray(nodes));
                break;
            }
            default:
                throw new IOException("unknown request " + method);
        }
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
