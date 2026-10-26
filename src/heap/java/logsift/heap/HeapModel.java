package logsift.heap;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A heap dump as the app sees it: read on this computer ({@link HeapDump}), or on a server and
 * asked over SSH ({@link RemoteHeap}). Objects are numbered (nodes); {@link #root()} is a made-up
 * node that holds the GC roots.
 */
public interface HeapModel extends AutoCloseable {
    /** The dump's file name (without folders). */
    String fileName();

    /** Where the dump is: a path, or "host:path" for one on a server. */
    String location();

    long fileBytes();

    /** When the dump was taken (ms since 1970), or 0. */
    long timestamp();

    boolean compressedPointers();

    boolean isOnDisk();

    int objectCount();

    int reachableCount();

    long totalBytes();

    long reachableBytes();

    int root();

    long retained(int node);

    long shallow(int node);

    String describe(int node);

    String className(int node);

    /** The object that holds this one (its immediate dominator), or -1. */
    int holder(int node);

    int heldCount(int node);

    /** What an object holds directly, biggest first, at most {@code max}. */
    int[] held(int node, int max);

    /** The chain of references from a GC root to the object. */
    List<HeapDump.Step> pathFromRoot(int node) throws IOException;

    /** The biggest objects of a class, at most {@code max}. */
    int[] objectsOf(String className, int max);

    List<HeapDump.ClassStat> classes();

    int threadCount();

    /** The threads at the moment of the dump, as a jstack-style thread dump. */
    String threadDumpText() throws IOException;

    /** What fills the heap, in sentences. */
    List<HeapFindings.Finding> findings() throws IOException;

    /** Memory spent for nothing (takes a while: copies of strings, empty collections). */
    HeapWaste.Report waste(HeapDump.Listener listener, BooleanSupplier cancel) throws IOException;

    /** What an object holds, as boxes for the map. */
    HeapMap.Box map(int focus) throws IOException;

    @Override
    void close();
}
