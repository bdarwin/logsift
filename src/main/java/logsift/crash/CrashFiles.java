package logsift.crash;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Opens a crash file of any kind Logsift knows and explains it. */
public final class CrashFiles {
    private CrashFiles() {}

    /**
     * How to find names in Windows dumps.
     *
     * @param settings   the symbol folders and whether Microsoft's server may be used
     * @param cache      Logsift's symbol cache (downloads go there); null: no cache, no downloads
     * @param download   downloads allowed for this call (the setting, or a click)
     */
    public record Options(CrashSettings settings, Path cache, boolean download) {
        public static Options of(Path logsiftHome) {
            CrashSettings s = CrashSettings.load(logsiftHome);
            return new Options(s, CrashSettings.cache(logsiftHome), s.downloadMicrosoft());
        }
    }

    /** The kind of crash file, from its first bytes and name; null if it isn't one. */
    public static CrashReport.Kind kindOf(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(16 * 1024);
            if (Minidump.looksLike(head)) {
                return CrashReport.Kind.WINDOWS_DUMP;
            }
            if (HsErr.looksLike(new String(head, StandardCharsets.UTF_8))) {
                return CrashReport.Kind.JAVA_CRASH;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * Why a file isn't a crash file Logsift reads, from what it starts with: empty, unreadable,
     * a memory dump of Windows itself rather than of a program, packed, or something else.
     */
    public static String whyNot(Path file) {
        String name = String.valueOf(file.getFileName());
        String reads = " Logsift reads Windows dumps of a program (.dmp, .mdmp, starting with MDMP) and Java "
                + "hs_err_pid files.";
        byte[] head;
        long size;
        try (InputStream in = Files.newInputStream(file)) {
            size = Files.size(file);
            head = in.readNBytes(16);
        } catch (IOException | RuntimeException e) {
            return name + " can't be read: " + e.getMessage()
                    + ". If a program is still writing it, or another program has it open, try again once it's done.";
        }
        if (size == 0) {
            return name + " is empty: the dump wasn't written (the program may have been stopped while writing it,"
                    + " or the disk was full).";
        }
        String start = new String(head, 0, Math.min(8, head.length), StandardCharsets.ISO_8859_1);
        if (start.startsWith("PAGEDU64") || start.startsWith("PAGEDUMP")) {
            return name + " is a memory dump of Windows itself (written at a blue screen, or of the whole"
                    + " machine), not of one program. Windows' own debugger reads it." + reads;
        }
        if (start.startsWith("PK")) {
            return name + " is a zip file: open it to see what's inside.";
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
            return name + " is packed with gzip: unpack it first, or open it as text.";
        }
        if (start.startsWith("MDMP")) {
            return name + " starts as a Windows dump but is cut short or damaged (" + size + " bytes).";
        }
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(8, head.length); i++) {
            hex.append(String.format("%02X ", head[i] & 0xFF));
        }
        return name + " isn't a dump Logsift knows: it starts with " + hex.toString().strip()
                + ", where a Windows dump starts with MDMP." + reads;
    }

    /** Whether a name says crash file (.dmp, .mdmp, hs_err_pid…): worth looking inside on open. */
    public static boolean likelyByName(Path file) {
        String n = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".dmp") || n.endsWith(".mdmp") || n.startsWith("hs_err") || n.contains("hs_err_pid");
    }

    /** Explains a crash file. */
    public static CrashReport analyze(Path file, Options o, BooleanSupplier stop) throws IOException {
        CrashReport.Kind k = kindOf(file);
        if (k == null) {
            throw new IOException(whyNot(file));
        }
        if (k == CrashReport.Kind.JAVA_CRASH) {
            return HsErr.read(file).report();
        }
        try (Minidump d = Minidump.open(file)) {
            SymbolFinder finder = new SymbolFinder(o == null ? null : o.cache(), o != null && o.download(),
                    System.getenv("_NT_SYMBOL_PATH"), stop);
            Symbols s = new Symbols(d, o == null ? java.util.List.of() : o.settings().symbolFolders(), finder);
            return WindowsCrash.analyze(d, s, stop);
        }
    }
}
