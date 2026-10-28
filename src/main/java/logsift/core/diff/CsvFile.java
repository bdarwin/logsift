package logsift.core.diff;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import logsift.core.Csv;
import logsift.core.Encoding;
import logsift.core.Formats;
import logsift.core.LogFormat;

/**
 * A CSV (or TSV) file read for comparing: its separator and header found as Logsift finds them
 * for viewing, then its rows streamed one by one. A row is a record: a quoted field may go on
 * over several lines. Only where each row is (its byte offset, length and first line) is kept,
 * in a {@link RowIndex}; a row's fields are read again from the file when needed.
 *
 * <p>UTF-8, Windows-1252 and other one-byte encodings are read as they are; a UTF-16 file is
 * converted to a temporary UTF-8 copy first (its lines stay the same).
 */
public final class CsvFile implements Closeable {
    /** The file isn't something that can be compared as a table (with why, for the user). */
    public static final class NotCsvException extends IOException {
        private static final long serialVersionUID = 1L;

        public NotCsvException(String message) {
            super(message);
        }
    }

    /** A quoted field over more lines than this is taken as a quote left open by mistake. */
    static final int MAX_RECORD_LINES = 10_000;
    private static final int BLOCK = 1 << 20;

    private final Path path;
    private final Path read;      // the file read (a UTF-8 copy of a UTF-16 file)
    private final boolean copy;
    private final String name;
    private final Charset charset;
    private final char delimiter;
    private final List<String> header;
    private final long headerEnd; // where the first row can start
    private final long headerLine;
    private final FileChannel channel;

    private CsvFile(Path path, Path read, boolean copy, String name, Charset charset, char delimiter,
                    List<String> header, long headerEnd, long headerLine) throws IOException {
        this.path = path;
        this.read = read;
        this.copy = copy;
        this.name = name;
        this.charset = charset;
        this.delimiter = delimiter;
        this.header = List.copyOf(header);
        this.headerEnd = headerEnd;
        this.headerLine = headerLine;
        this.channel = FileChannel.open(read, StandardOpenOption.READ);
    }

    /** Opens a file to compare; {@code name} is how to call it (null: its file name). */
    public static CsvFile open(Path path, String name) throws IOException {
        String label = name != null ? name : path.getFileName() == null ? path.toString() : path.getFileName().toString();
        if (!Files.isRegularFile(path)) {
            throw new java.io.FileNotFoundException("Not a file (or not reachable): " + path);
        }
        Charset cs = Encoding.detect(path);
        Path read = path;
        boolean copy = false;
        if (Encoding.isUtf16(cs)) {
            read = utf8Copy(path, cs);
            copy = true;
            cs = StandardCharsets.UTF_8;
        }
        try {
            List<String> sample = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(Files.newInputStream(read),
                    cs.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)))) {
                String line;
                long chars = 0;
                while (sample.size() < 2000 && chars < 4_000_000 && (line = r.readLine()) != null) {
                    sample.add(line);
                    chars += line.length();
                }
            }
            if (sample.stream().allMatch(String::isBlank)) {
                throw new NotCsvException(label + " is empty");
            }
            boolean named = Formats.isCsvName(label) || Formats.isCsvName(path.getFileName() == null ? ""
                    : path.getFileName().toString());
            LogFormat f = LogFormat.detectCsv(sample, named);
            if (f == null || !f.isCsv()) {
                throw new NotCsvException(label + " doesn't look like a CSV file: no header row with columns"
                        + " separated by commas, semicolons, tabs or bars");
            }
            char d = f.delimiter();
            // the header: the first record that isn't blank (after a byte order mark)
            long[] where = new long[2]; // where the header ends: offset, line
            List<String> names = readHeader(read, cs, d, where);
            if (names == null) {
                throw new NotCsvException(label + " is empty");
            }
            List<String> hdr = f.headerNames();
            if (hdr == null || hdr.size() != names.size()) {
                hdr = names.stream().map(String::strip).toList();
            }
            return new CsvFile(path, read, copy, label, cs, d, hdr, where[0], where[1]);
        } catch (IOException | RuntimeException e) {
            if (copy) {
                Files.deleteIfExists(read);
            }
            throw e;
        }
    }

    private static Path utf8Copy(Path path, Charset cs) throws IOException {
        Path out = Files.createTempFile("logsift-compare-", ".csv");
        out.toFile().deleteOnExit();
        try (Reader r = new BufferedReader(new InputStreamReader(Files.newInputStream(path), cs), 1 << 16);
             Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            char[] buf = new char[1 << 16];
            int n;
            boolean first = true;
            while ((n = r.read(buf)) > 0) {
                int from = first && buf[0] == '﻿' ? 1 : 0;
                first = false;
                w.write(buf, from, n - from);
            }
        }
        return out;
    }

    /** The header's fields, and where it ends (offset, line) in {@code where}; null if no record. */
    private static List<String> readHeader(Path read, Charset cs, char d, long[] where) throws IOException {
        java.util.concurrent.atomic.AtomicReference<List<String>> got = new java.util.concurrent.atomic.AtomicReference<>();
        try (FileChannel ch = FileChannel.open(read, StandardOpenOption.READ)) {
            Scanner s = new Scanner(ch, d, 0, 0, null, null) {
                @Override
                boolean record(long offset, int length, long line, byte[] buf, int from) {
                    String text = new String(buf, from, length, cs);
                    if (text.isBlank()) {
                        return true;
                    }
                    got.set(Csv.split(text, d, null));
                    where[0] = offset + length;
                    where[1] = this.line; // its last line (a quoted name may go over several)
                    return false;
                }
            };
            s.run(startOf(ch));
        }
        return got.get();
    }

    /** Where text starts: after a UTF-8 byte order mark. */
    private static long startOf(FileChannel ch) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(3);
        ch.read(b, 0);
        return b.position() == 3 && (b.get(0) & 0xFF) == 0xEF && (b.get(1) & 0xFF) == 0xBB && (b.get(2) & 0xFF) == 0xBF
                ? 3 : 0;
    }

    public Path path() {
        return path;
    }

    public String name() {
        return name;
    }

    public Charset charset() {
        return charset;
    }

    public char delimiter() {
        return delimiter;
    }

    /** The header's names, as written (spaces around taken off). */
    public List<String> header() {
        return header;
    }

    public long size() throws IOException {
        return channel.size();
    }

    /** Gets each row's fields, in order; return false to stop. */
    public interface RowVisitor {
        boolean row(int index, String[] fields);
    }

    /**
     * Reads every row (the header and blank lines left out) and returns where each is.
     * {@code visitor} (or null) gets each row's fields, as many as the header has: missing
     * ones are null, extra ones are joined to the last.
     *
     * @throws CancellationException when {@code cancelled} says so
     */
    public RowIndex scan(RowVisitor visitor, DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
        RowIndex idx = new RowIndex();
        int width = header.size();
        long total = Math.max(1, channel.size());
        Scanner s = new Scanner(channel, delimiter, headerEnd, headerLine, progress, cancelled) {
            @Override
            boolean record(long offset, int length, long line, byte[] buf, int from) {
                if (length == 0 || isBlank(buf, from, length)) {
                    return true;
                }
                int row = idx.add(offset, length, line);
                if (visitor != null) {
                    return visitor.row(row, fields(new String(buf, from, length, charset), width));
                }
                return true;
            }
        };
        s.total = total;
        s.run(headerEnd);
        idx.trim();
        return idx;
    }

    private static boolean isBlank(byte[] b, int from, int len) {
        for (int i = from; i < from + len; i++) {
            if (b[i] != ' ' && b[i] != '\t' && b[i] != '\r') {
                return false;
            }
        }
        return true;
    }

    /** A record's fields, as many as the header has. */
    private String[] fields(String record, int width) {
        List<String> v = Csv.split(record, delimiter, null);
        String[] out = new String[width];
        for (int i = 0; i < width && i < v.size(); i++) {
            out[i] = v.get(i);
        }
        if (v.size() > width && width > 0) {
            out[width - 1] = String.join(String.valueOf(delimiter), v.subList(width - 1, v.size()));
        }
        return out;
    }

    /** Row {@code row}'s fields, read again from the file. */
    public String[] read(RowIndex idx, int row) throws IOException {
        int len = idx.length[row];
        ByteBuffer b = ByteBuffer.allocate(len);
        long at = idx.offset[row];
        while (b.hasRemaining()) {
            int n = channel.read(b, at + b.position());
            if (n < 0) {
                throw new IOException(name + " changed since it was compared");
            }
        }
        return fields(new String(b.array(), 0, len, charset), header.size());
    }

    /** The header's line in the file (0-based). */
    public long headerLine() {
        return headerLine;
    }

    @Override
    public void close() {
        try {
            channel.close();
            if (copy) {
                Files.deleteIfExists(read);
            }
        } catch (IOException e) {
            // ignore
        }
    }

    @Override
    public String toString() {
        return name;
    }

    /** Where each row is in the file: its byte offset, length (without the line break) and line. */
    public static final class RowIndex {
        long[] offset = new long[1024];
        int[] length = new int[1024];
        long[] line = new long[1024];
        int size;

        int add(long off, int len, long ln) {
            if (size == offset.length) {
                int n = size + (size >> 1);
                offset = Arrays.copyOf(offset, n);
                length = Arrays.copyOf(length, n);
                line = Arrays.copyOf(line, n);
            }
            offset[size] = off;
            length[size] = len;
            line[size] = ln;
            return size++;
        }

        void trim() {
            offset = Arrays.copyOf(offset, size);
            length = Arrays.copyOf(length, size);
            line = Arrays.copyOf(line, size);
        }

        public int size() {
            return size;
        }

        /** The line (0-based) row {@code row} starts on. */
        public long line(int row) {
            return line[row];
        }
    }

    /**
     * Splits bytes into records: at a line break outside quotes. A quote opens a quoted field
     * only at the start of a field (after spaces), as {@link Csv#split} reads it; {@code ""}
     * inside is a quote.
     */
    abstract static class Scanner {
        private final FileChannel ch;
        private final byte delim;
        long line; // the line being read
        private final DoubleConsumer progress;
        private final BooleanSupplier cancelled;
        long total;

        Scanner(FileChannel ch, char delimiter, long start, long line, DoubleConsumer progress,
                BooleanSupplier cancelled) {
            this.ch = ch;
            this.delim = (byte) delimiter;
            this.line = line;
            this.progress = progress;
            this.cancelled = cancelled;
        }

        /** A record: {@code length} bytes at {@code offset} of the file, {@code buf[from..]}; false: stop. */
        abstract boolean record(long offset, int length, long line, byte[] buf, int from);

        void run(long start) throws IOException {
            byte[] buf = new byte[BLOCK];
            int have = 0;          // bytes in buf
            int recStart = 0;      // where the current record starts in buf
            long bufOffset = start; // file offset of buf[0]
            long recLine = line;
            int state = 0;         // 0 field start, 1 unquoted, 2 quoted, 3 quote in quoted
            int recLines = 0;
            int pos = 0;
            long count = 0;
            long lastReport = 0;
            boolean eof = false;
            while (true) {
                if (pos == have) {
                    if (eof) {
                        break;
                    }
                    // keep the record so far, read more after it
                    int keep = have - recStart;
                    if (recStart > 0) {
                        System.arraycopy(buf, recStart, buf, 0, keep);
                        bufOffset += recStart;
                        pos -= recStart;
                        recStart = 0;
                    }
                    if (keep == buf.length) {
                        buf = Arrays.copyOf(buf, buf.length * 2); // a very long row
                    }
                    ByteBuffer bb = ByteBuffer.wrap(buf, keep, buf.length - keep);
                    int n = ch.read(bb, bufOffset + keep);
                    if (n <= 0) {
                        eof = true;
                        have = keep;
                        if (pos == have) {
                            break;
                        }
                        continue;
                    }
                    have = keep + n;
                    if (progress != null && total > 0 && bufOffset + have - lastReport > total / 100) {
                        lastReport = bufOffset + have;
                        progress.accept(Math.min(1.0, (double) lastReport / total));
                    }
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new CancellationException();
                    }
                }
                byte c = buf[pos];
                if (c == '\n') {
                    if (state == 2 && recLines < MAX_RECORD_LINES) {
                        recLines++;
                        line++;
                        pos++;
                        continue;
                    }
                    int end = pos > recStart && buf[pos - 1] == '\r' ? pos - 1 : pos;
                    if (!record(bufOffset + recStart, end - recStart, recLine, buf, recStart)) {
                        return;
                    }
                    if ((++count & 4095) == 0 && cancelled != null && cancelled.getAsBoolean()) {
                        throw new CancellationException();
                    }
                    line++;
                    pos++;
                    recStart = pos;
                    recLine = line;
                    recLines = 0;
                    state = 0;
                    continue;
                }
                switch (state) {
                    case 0 -> {
                        if (c == '"') {
                            state = 2;
                        } else if (c != delim && c != ' ') {
                            state = 1;
                        }
                    }
                    case 1 -> {
                        if (c == delim) {
                            state = 0;
                        }
                    }
                    case 2 -> {
                        if (c == '"') {
                            state = 3;
                        }
                    }
                    default -> state = c == '"' ? 2 : c == delim ? 0 : 1;
                }
                pos++;
            }
            if (have > recStart) {
                int end = buf[have - 1] == '\r' ? have - 1 : have;
                record(bufOffset + recStart, end - recStart, recLine, buf, recStart);
            }
        }
    }
}
