package logsift.core;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Zip and gzip files: what is in a zip (zips inside it too), and reading one entry. An entry
 * of a zip inside a zip is named by the path through each level, separated by {@link #NESTED}
 * ({@code old/2026-09.zip!/app.log}).
 */
public final class Archives {
    private Archives() {}

    /** Separates the levels of a zip inside a zip in an entry's name. */
    public static final String NESTED = "!/";
    /** How deep zips inside zips are followed. */
    public static final int MAX_DEPTH = 5;

    /**
     * One file in a zip.
     *
     * @param name the path through each level ({@code a.zip!/logs/x.log})
     * @param size its size unpacked, or -1 if the zip doesn't say
     */
    public record Entry(String name, long size) {
        /** The file's own name (without folders or the zips it is in). */
        public String fileName() {
            return baseName(name);
        }

        /** Its name for people: the levels separated by › . */
        public String label() {
            return name.replace(NESTED, " › ");
        }
    }

    public static boolean isZip(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    public static boolean isGzip(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    public static boolean isZip(Path p) {
        return p.getFileName() != null && isZip(p.getFileName().toString());
    }

    public static boolean isGzip(Path p) {
        return p.getFileName() != null && isGzip(p.getFileName().toString());
    }

    /** The last part of a path inside a zip. */
    public static String baseName(String entry) {
        String last = entry.substring(entry.lastIndexOf(NESTED) < 0 ? 0 : entry.lastIndexOf(NESTED) + NESTED.length());
        return last.substring(last.lastIndexOf('/') + 1);
    }

    /** A .gz file's name without the .gz. */
    public static String gunzippedName(String name) {
        return isGzip(name) ? name.substring(0, name.length() - 3) : name;
    }

    /**
     * The files in a zip, in the zip's order; the files of zips inside it follow each such zip
     * (which is listed too). Folders are left out.
     */
    public static List<Entry> list(Path zip, BooleanSupplier cancelled) throws IOException {
        List<Entry> out = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements() && !cancelled.getAsBoolean()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                out.add(new Entry(e.getName(), e.getSize()));
                if (isZip(baseName(e.getName()))) {
                    try (InputStream in = new BufferedInputStream(z.getInputStream(e), 1 << 16)) {
                        listNested(in, e.getName() + NESTED, 2, out, cancelled);
                    } catch (IOException ex) {
                        // a damaged zip inside: its own entry is still listed
                    }
                }
            }
        }
        return out;
    }

    private static void listNested(InputStream in, String prefix, int depth, List<Entry> out,
                                   BooleanSupplier cancelled) throws IOException {
        ZipInputStream zin = new ZipInputStream(in);
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null && !cancelled.getAsBoolean()) {
            if (e.isDirectory()) {
                continue;
            }
            String name = prefix + e.getName();
            boolean zip = isZip(baseName(e.getName())) && depth < MAX_DEPTH;
            long size = e.getSize();
            if (size < 0 && !zip) {
                size = zin.transferTo(OutputStream.nullOutputStream()); // read it to know
            }
            out.add(new Entry(name, size));
            if (zip) {
                listNested(new BufferedInputStream(notClosing(zin), 1 << 16), name + NESTED, depth + 1, out,
                        cancelled);
            }
        }
    }

    /**
     * The content of an entry: {@code entry} may name zips inside the zip (separated by
     * {@link #NESTED}), and a .gz entry is unpacked.
     */
    public static InputStream open(Path zip, String entry) throws IOException {
        return openEntry(Files.newInputStream(zip), zip, entry);
    }

    private static InputStream openEntry(InputStream file, Path zip, String entry) throws IOException {
        String[] parts = entry.split(java.util.regex.Pattern.quote(NESTED), -1);
        InputStream in = new BufferedInputStream(file, 1 << 16);
        try {
            for (String part : parts) {
                ZipInputStream zin = new ZipInputStream(in);
                in = zin;
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null && !e.getName().equals(part)) {
                    // skip to it
                }
                if (e == null) {
                    throw new IOException("No " + part + " in " + zip.getFileName());
                }
                in = new BufferedInputStream(zin, 1 << 16);
            }
            return isGzip(baseName(entry)) ? new GZIPInputStream(in, 1 << 16) : in;
        } catch (IOException | RuntimeException ex) {
            in.close();
            throw ex;
        }
    }

    /**
     * Copies an entry of a zip (or, with {@code entry} null, the content of a .gz file) to
     * {@code out}. {@code size} (the entry's, or -1) is for the progress.
     *
     * @return false if cancelled (the copy is then deleted)
     */
    public static boolean extract(Path archive, String entry, long size, Path out, DoubleConsumer progress,
                                  BooleanSupplier cancelled) throws IOException {
        long[] read = new long[1]; // compressed bytes read (a .gz's progress)
        InputStream file = new FilterInputStream(Files.newInputStream(archive)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int n = super.read(b, off, len);
                if (n > 0) {
                    read[0] += n;
                }
                return n;
            }
        };
        long gzSize = entry == null ? Files.size(archive) : -1;
        boolean done = false;
        try (InputStream in = entry != null ? openEntry(file, archive, entry)
                : new GZIPInputStream(new BufferedInputStream(file, 1 << 16), 1 << 16);
             OutputStream o = Files.newOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            long written = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                o.write(buf, 0, n);
                written += n;
                if (progress != null) {
                    if (gzSize > 0) {
                        progress.accept(Math.min(1, (double) read[0] / gzSize));
                    } else if (size > 0) {
                        progress.accept(Math.min(1, (double) written / size));
                    }
                }
            }
            done = true;
        } finally {
            file.close();
            if (!done) {
                Files.deleteIfExists(out);
            }
        }
        return true;
    }

    /** A stream whose close leaves the stream below open (for the entries of a zip stream). */
    static InputStream notClosing(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public void close() {}
        };
    }
}
