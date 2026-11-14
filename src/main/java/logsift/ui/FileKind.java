package logsift.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * What a file is, read from its name and its start: what "Analyze with Logsift" opens it as. A
 * file can't be told apart by its name alone (a GC log and a thread dump are both .log), so the
 * start of the text is read.
 */
enum FileKind {
    /** A folder: its files. */
    FOLDER("its files"),
    /** A Java heap dump (.hprof): the Heap view. */
    HEAP_DUMP("the heap dump view"),
    /** A Flight Recorder recording: the Profile view. */
    PROFILE("the profile"),
    /** A Windows crash dump or a Java hs_err file: the Crash view. */
    CRASH("the crash view"),
    /** A Java or Python thread dump: the Threads view. */
    THREAD_DUMP("the threads view"),
    /** A Java GC log: the GC view. */
    GC_LOG("the GC view"),
    /** Anything else: the log. */
    LOG("the log");

    /** Where it opens, for messages. */
    final String opensIn;

    FileKind(String opensIn) {
        this.opensIn = opensIn;
    }

    /** How much of a text file is read to tell. */
    static final int HEAD = 512 * 1024;

    static FileKind of(Path file) {
        if (Files.isDirectory(file)) {
            return FOLDER;
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".hprof")) {
            return HEAP_DUMP;
        }
        if (name.endsWith(".jfr")) {
            return PROFILE;
        }
        if (name.endsWith(".dmp") || name.endsWith(".mdmp")) {
            return CRASH; // the crash view says what's in it, even when it can't read it
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(HEAD);
        } catch (IOException e) {
            return LOG; // opening it says what's wrong
        }
        if (startsWith(head, "JAVA PROFILE 1.0")) {
            return HEAP_DUMP;
        }
        if (startsWith(head, "FLR\0")) {
            return PROFILE;
        }
        if (startsWith(head, "MDMP")) {
            return CRASH;
        }
        if (logsift.core.Archives.isZip(file) || logsift.core.Archives.isGzip(file)) {
            return LOG; // opened (unpacked) as a log
        }
        String text = new String(head, logsift.core.Encoding.detect(head, head.length));
        if (logsift.crash.HsErr.looksLike(text.substring(0, Math.min(text.length(), 4096)))) {
            return CRASH;
        }
        if (logsift.jvm.DumpParser.looksLikeDump(text)) {
            return THREAD_DUMP;
        }
        if (logsift.jvm.GcLog.looksLikeGcLog(text)) {
            return GC_LOG;
        }
        return LOG;
    }

    private static boolean startsWith(byte[] b, String magic) {
        if (b.length < magic.length()) {
            return false;
        }
        for (int i = 0; i < magic.length(); i++) {
            if (b[i] != (byte) magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
