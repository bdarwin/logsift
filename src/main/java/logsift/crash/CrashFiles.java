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

    /** Whether a name says crash file (.dmp, .mdmp, hs_err_pid…): worth looking inside on open. */
    public static boolean likelyByName(Path file) {
        String n = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".dmp") || n.endsWith(".mdmp") || n.startsWith("hs_err") || n.contains("hs_err_pid");
    }

    /** Explains a crash file. */
    public static CrashReport analyze(Path file, Options o, BooleanSupplier stop) throws IOException {
        CrashReport.Kind k = kindOf(file);
        if (k == null) {
            throw new IOException("Not a crash file Logsift knows: " + file.getFileName()
                    + " (it reads Windows dumps, .dmp and .mdmp, and Java hs_err_pid files)");
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
