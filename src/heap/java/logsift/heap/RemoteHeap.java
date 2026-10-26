package logsift.heap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * A heap dump read by a {@link HeapServer} on a server, asked over a connection (through SSH).
 * Every answer that names objects brings what the app shows about them, kept here, so showing a
 * list of objects is one question, not one per object.
 */
public final class RemoteHeap implements HeapModel {
    /** Where the connection comes from, and how to close it. */
    public interface Link extends Closeable {
        InputStream in() throws IOException;

        OutputStream out() throws IOException;
    }

    private final Link link;
    private final DataOutputStream out;
    private final DataInputStream in;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, Call> calls = new ConcurrentHashMap<>();
    private final Map<Integer, Wire.Info> infos = new ConcurrentHashMap<>();
    private final String host;
    private volatile IOException broken;
    private final Thread reader;

    // the header
    private String fileName;
    private String location;
    private long fileBytes;
    private long timestamp;
    private boolean compressed;
    private boolean onDisk;
    private int objectCount;
    private int reachableCount;
    private long totalBytes;
    private long reachableBytes;
    private int root;
    private int threadCount;
    private volatile List<HeapDump.ClassStat> classes;

    /** One question waiting for its answer. */
    private static final class Call {
        final HeapDump.Listener progress;
        byte kind = -1;
        byte[] payload;

        Call(HeapDump.Listener progress) {
            this.progress = progress;
        }
    }

    private RemoteHeap(Link link, String host) throws IOException {
        this.link = link;
        this.host = host;
        out = new DataOutputStream(new BufferedOutputStream(link.out()));
        in = new DataInputStream(new BufferedInputStream(link.in()));
        reader = new Thread(this::read, "logsift-heap-answers");
        reader.setDaemon(true);
    }

    /** Connects with the server's token; the dump may still be being read (see {@link #awaitReady}). */
    public static RemoteHeap connect(Link link, String token, String host) throws IOException {
        RemoteHeap r = new RemoteHeap(link, host);
        try {
            Wire.str(r.out, token);
            r.out.flush();
            int version = r.in.readInt();
            if (version != Wire.VERSION) {
                throw new IOException("the server speaks version " + version + ", this app " + Wire.VERSION);
            }
        } catch (IOException e) {
            r.close();
            throw new IOException("the heap server didn't answer: " + e.getMessage(), e);
        }
        r.reader.start();
        return r;
    }

    private void read() {
        try {
            while (true) {
                int id = in.readInt();
                byte kind = in.readByte();
                byte[] payload = new byte[in.readInt()];
                in.readFully(payload);
                Call c = calls.get(id);
                if (c == null) {
                    continue;
                }
                if (kind == Wire.PROGRESS) {
                    if (c.progress != null) {
                        DataInputStream p = Wire.in(payload);
                        c.progress.progress(Wire.str(p), p.readDouble());
                    }
                    continue;
                }
                synchronized (c) {
                    c.kind = kind;
                    c.payload = payload;
                    c.notifyAll();
                }
            }
        } catch (IOException e) {
            broken = e;
            for (Call c : calls.values()) {
                synchronized (c) {
                    c.notifyAll();
                }
            }
        }
    }

    /** Asks and waits for the answer. */
    private DataInputStream ask(String method, ArgWriter args, HeapDump.Listener progress, BooleanSupplier cancel)
            throws IOException {
        if (broken != null) {
            throw new IOException("the connection to " + host + " was lost: " + broken.getMessage(), broken);
        }
        int id = ids.incrementAndGet();
        Call c = new Call(progress);
        calls.put(id, c);
        try {
            Wire.Out a = new Wire.Out();
            if (args != null) {
                args.write(a.o);
            }
            a.o.flush();
            send(id, method, a.bytes.toByteArray());
            boolean cancelled = false;
            synchronized (c) {
                while (c.kind < 0 && broken == null) {
                    try {
                        c.wait(cancel == null ? 0 : 200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                    }
                    if (cancel != null && !cancelled && cancel.getAsBoolean()) {
                        cancelled = true;
                        final int which = id;
                        send(ids.incrementAndGet(), "cancel", intBytes(which));
                    }
                }
            }
            if (c.kind < 0) {
                throw new IOException("the connection to " + host + " was lost: " + broken.getMessage(), broken);
            }
            if (c.kind == Wire.ERROR) {
                String msg = Wire.str(Wire.in(c.payload));
                if (cancelled) {
                    throw new CancellationException();
                }
                throw new IOException(msg);
            }
            return Wire.in(c.payload);
        } finally {
            calls.remove(id);
        }
    }

    private interface ArgWriter {
        void write(DataOutputStream o) throws IOException;
    }

    private static byte[] intBytes(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private void send(int id, String method, byte[] args) throws IOException {
        synchronized (out) {
            out.writeInt(id);
            Wire.str(out, method);
            out.writeInt(args.length);
            out.write(args);
            out.flush();
        }
    }

    private RuntimeException unchecked(IOException e) {
        return new java.io.UncheckedIOException(e);
    }

    // ---------------------------------------------------------------- reading

    /** Waits until the server has read the dump, passing on how far it is; then reads the header. */
    public void awaitReady(HeapDump.Listener progress, BooleanSupplier cancel) throws IOException {
        while (true) {
            DataInputStream a = ask("status", null, null, null);
            int state = a.readByte();
            String stage = Wire.str(a);
            double f = a.readDouble();
            String failure = Wire.str(a);
            if (state == 2) {
                throw new IOException(failure);
            }
            if (state == 1) {
                break;
            }
            if (progress != null) {
                progress.progress(stage, f);
            }
            if (cancel != null && cancel.getAsBoolean()) {
                throw new CancellationException();
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }
        DataInputStream h = ask("header", null, null, null);
        fileName = Wire.str(h);
        location = host + ":" + Wire.str(h);
        fileBytes = h.readLong();
        timestamp = h.readLong();
        compressed = h.readBoolean();
        onDisk = h.readBoolean();
        objectCount = h.readInt();
        reachableCount = h.readInt();
        totalBytes = h.readLong();
        reachableBytes = h.readLong();
        root = h.readInt();
        threadCount = h.readInt();
        keep(Wire.info(h));
    }

    /** Ends the server (and its index). */
    public void stopServer() {
        try {
            ask("stop", null, null, null);
        } catch (IOException e) {
            // gone already
        }
    }

    private void keep(Wire.Info i) {
        infos.put(i.node, i);
    }

    private void keepAll(DataInputStream a) throws IOException {
        for (Wire.Info i : Wire.infos(a)) {
            keep(i);
        }
    }

    private Wire.Info info(int v) {
        Wire.Info i = infos.get(v);
        if (i != null) {
            return i;
        }
        try {
            DataInputStream a = ask("info", o -> Wire.ints(o, new int[] {v}), null, null);
            keepAll(a);
        } catch (IOException e) {
            throw unchecked(e);
        }
        return infos.get(v);
    }

    @Override
    public String fileName() {
        return fileName;
    }

    @Override
    public String location() {
        return location;
    }

    @Override
    public long fileBytes() {
        return fileBytes;
    }

    @Override
    public long timestamp() {
        return timestamp;
    }

    @Override
    public boolean compressedPointers() {
        return compressed;
    }

    @Override
    public boolean isOnDisk() {
        return onDisk;
    }

    @Override
    public int objectCount() {
        return objectCount;
    }

    @Override
    public int reachableCount() {
        return reachableCount;
    }

    @Override
    public long totalBytes() {
        return totalBytes;
    }

    @Override
    public long reachableBytes() {
        return reachableBytes;
    }

    @Override
    public int root() {
        return root;
    }

    @Override
    public long retained(int node) {
        return info(node).retained;
    }

    @Override
    public long shallow(int node) {
        return info(node).shallow;
    }

    @Override
    public String describe(int node) {
        return info(node).text;
    }

    @Override
    public String className(int node) {
        return info(node).className;
    }

    @Override
    public int holder(int node) {
        return info(node).holder;
    }

    @Override
    public int heldCount(int node) {
        return info(node).heldCount;
    }

    @Override
    public int[] held(int node, int max) {
        try {
            DataInputStream a = ask("held", o -> {
                o.writeInt(node);
                o.writeInt(max);
            }, null, null);
            int[] kids = Wire.ints(a);
            keepAll(a);
            return kids;
        } catch (IOException e) {
            throw unchecked(e);
        }
    }

    @Override
    public List<HeapDump.Step> pathFromRoot(int node) throws IOException {
        DataInputStream a = ask("path", o -> o.writeInt(node), null, null);
        int n = a.readInt();
        List<HeapDump.Step> steps = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            steps.add(new HeapDump.Step(a.readInt(), Wire.str(a)));
        }
        keepAll(a);
        return steps;
    }

    @Override
    public int[] objectsOf(String className, int max) {
        try {
            DataInputStream a = ask("objectsOf", o -> {
                Wire.str(o, className);
                o.writeInt(max);
            }, null, null);
            int[] top = Wire.ints(a);
            keepAll(a);
            return top;
        } catch (IOException e) {
            throw unchecked(e);
        }
    }

    @Override
    public List<HeapDump.ClassStat> classes() {
        List<HeapDump.ClassStat> c = classes;
        if (c == null) {
            try {
                c = Wire.classes(ask("classes", null, null, null));
            } catch (IOException e) {
                throw unchecked(e);
            }
            classes = c;
        }
        return c;
    }

    @Override
    public int threadCount() {
        return threadCount;
    }

    @Override
    public String threadDumpText() throws IOException {
        return Wire.str(ask("threads", null, null, null));
    }

    @Override
    public List<HeapFindings.Finding> findings() throws IOException {
        DataInputStream a = ask("findings", null, null, null);
        List<HeapFindings.Finding> f = Wire.findings(a);
        keepAll(a);
        return f;
    }

    @Override
    public HeapWaste.Report waste(HeapDump.Listener listener, BooleanSupplier cancel) throws IOException {
        return Wire.waste(ask("waste", null, listener, cancel));
    }

    @Override
    public HeapMap.Box map(int focus) throws IOException {
        DataInputStream a = ask("map", o -> o.writeInt(focus), null, null);
        HeapMap.Box b = Wire.box(a);
        keepAll(a);
        return b;
    }

    /** Disconnects; the server keeps running (see {@link #stopServer}). */
    @Override
    public void close() {
        try {
            link.close();
        } catch (IOException e) {
            // closing
        }
    }
}
