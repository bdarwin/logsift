package logsift.heap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How {@link HeapServer} and {@link RemoteHeap} talk: framed messages over one connection.
 * A request is {@code [int id][String method][arguments]}; an answer is {@code [int id][byte kind]
 * [int length][bytes]}, kind {@link #RESULT}, {@link #ERROR} (a message) or {@link #PROGRESS}
 * (a stage and a fraction, before the result).
 */
final class Wire {
    static final int VERSION = 1;
    static final byte RESULT = 0;
    static final byte ERROR = 1;
    static final byte PROGRESS = 2;

    private Wire() {
    }

    static void str(DataOutputStream o, String s) throws IOException {
        if (s == null) {
            o.writeInt(-1);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.writeInt(b.length);
        o.write(b);
    }

    static String str(DataInputStream i) throws IOException {
        int n = i.readInt();
        if (n < 0) {
            return null;
        }
        byte[] b = new byte[n];
        i.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    static void ints(DataOutputStream o, int[] a) throws IOException {
        o.writeInt(a.length);
        for (int x : a) {
            o.writeInt(x);
        }
    }

    static int[] ints(DataInputStream i) throws IOException {
        int[] a = new int[i.readInt()];
        for (int k = 0; k < a.length; k++) {
            a[k] = i.readInt();
        }
        return a;
    }

    static DataInputStream in(byte[] b) {
        return new DataInputStream(new ByteArrayInputStream(b));
    }

    /** Writes into a buffer, for one answer. */
    static final class Out {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream o = new DataOutputStream(bytes);
    }

    // ---------------------------------------------------------------- what an object is

    /** What the app shows about an object, sent along with every object an answer names. */
    static final class Info {
        final int node;
        final String className;
        final String text;
        final long retained;
        final long shallow;
        final int heldCount;
        final int holder;

        Info(int node, String className, String text, long retained, long shallow, int heldCount, int holder) {
            this.node = node;
            this.className = className;
            this.text = text;
            this.retained = retained;
            this.shallow = shallow;
            this.heldCount = heldCount;
            this.holder = holder;
        }
    }

    static void info(DataOutputStream o, HeapDump d, int v) throws IOException {
        o.writeInt(v);
        str(o, d.className(v));
        str(o, v == d.root() ? "" : d.describe(v));
        o.writeLong(d.retained(v));
        o.writeLong(d.shallow(v));
        o.writeInt(d.heldCount(v));
        o.writeInt(d.holder(v));
    }

    static Info info(DataInputStream i) throws IOException {
        return new Info(i.readInt(), str(i), str(i), i.readLong(), i.readLong(), i.readInt(), i.readInt());
    }

    static void infos(DataOutputStream o, HeapDump d, int[] nodes) throws IOException {
        o.writeInt(nodes.length);
        for (int v : nodes) {
            info(o, d, v);
        }
    }

    static List<Info> infos(DataInputStream i) throws IOException {
        int n = i.readInt();
        List<Info> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            out.add(info(i));
        }
        return out;
    }

    // ---------------------------------------------------------------- results

    static void classes(DataOutputStream o, List<HeapDump.ClassStat> list) throws IOException {
        o.writeInt(list.size());
        for (HeapDump.ClassStat c : list) {
            str(o, c.name());
            o.writeLong(c.count());
            o.writeLong(c.shallow());
            o.writeLong(c.retained());
        }
    }

    static List<HeapDump.ClassStat> classes(DataInputStream i) throws IOException {
        int n = i.readInt();
        List<HeapDump.ClassStat> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            out.add(new HeapDump.ClassStat(str(i), i.readLong(), i.readLong(), i.readLong()));
        }
        return out;
    }

    static void findings(DataOutputStream o, List<HeapFindings.Finding> list) throws IOException {
        o.writeInt(list.size());
        for (HeapFindings.Finding f : list) {
            o.writeInt(f.kind().ordinal());
            str(o, f.title());
            str(o, f.detail());
            o.writeInt(f.node());
            o.writeLong(f.bytes());
        }
    }

    static List<HeapFindings.Finding> findings(DataInputStream i) throws IOException {
        int n = i.readInt();
        List<HeapFindings.Finding> out = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            out.add(new HeapFindings.Finding(HeapFindings.Kind.values()[i.readInt()], str(i), str(i), i.readInt(),
                    i.readLong()));
        }
        return out;
    }

    static void waste(DataOutputStream o, HeapWaste.Report r) throws IOException {
        o.writeLong(r.stringBytes());
        o.writeLong(r.duplicatedTexts());
        o.writeInt(r.strings().size());
        for (HeapWaste.Duplicate x : r.strings()) {
            str(o, x.text());
            o.writeLong(x.copies());
            o.writeLong(x.wasted());
            o.writeInt(x.sample());
            str(o, x.where());
        }
        o.writeLong(r.collectionBytes());
        o.writeInt(r.collections().size());
        for (HeapWaste.Collections x : r.collections()) {
            str(o, x.what());
            str(o, x.where());
            o.writeLong(x.count());
            o.writeLong(x.wasted());
            o.writeInt(x.sample());
        }
    }

    static HeapWaste.Report waste(DataInputStream i) throws IOException {
        long stringBytes = i.readLong();
        long texts = i.readLong();
        int n = i.readInt();
        List<HeapWaste.Duplicate> strings = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            strings.add(new HeapWaste.Duplicate(str(i), i.readLong(), i.readLong(), i.readInt(), str(i)));
        }
        long collectionBytes = i.readLong();
        int m = i.readInt();
        List<HeapWaste.Collections> cols = new ArrayList<>(m);
        for (int k = 0; k < m; k++) {
            cols.add(new HeapWaste.Collections(str(i), str(i), i.readLong(), i.readLong(), i.readInt()));
        }
        return new HeapWaste.Report(stringBytes, texts, strings, collectionBytes, cols);
    }

    static void box(DataOutputStream o, HeapMap.Box b) throws IOException {
        o.writeInt(b.node());
        o.writeLong(b.bytes());
        str(o, b.label());
        str(o, b.tip());
        o.writeLong(b.count());
        o.writeInt(b.inside().size());
        for (HeapMap.Box k : b.inside()) {
            box(o, k);
        }
    }

    static HeapMap.Box box(DataInputStream i) throws IOException {
        int node = i.readInt();
        long bytes = i.readLong();
        String label = str(i);
        String tip = str(i);
        long count = i.readLong();
        int n = i.readInt();
        List<HeapMap.Box> inside = n == 0 ? Collections.<HeapMap.Box>emptyList() : new ArrayList<HeapMap.Box>(n);
        for (int k = 0; k < n; k++) {
            inside.add(box(i));
        }
        return new HeapMap.Box(node, bytes, label, tip, count, inside);
    }

    /** Every object a map names (to send what they are along). */
    static void nodesOf(HeapMap.Box b, List<Integer> out) {
        if (b.node() >= 0) {
            out.add(b.node());
        }
        for (HeapMap.Box k : b.inside()) {
            nodesOf(k, out);
        }
    }
}
