package logmaster.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * A UTF-8 copy of a UTF-16 log, kept up to date while the log grows. LogMaster's engine works on
 * bytes where a newline is the byte 0x0A, which is not true for UTF-16, so such logs are viewed
 * through this copy. Truncation or rotation of the original rewrites the copy.
 */
public final class Utf16Mirror implements AutoCloseable {
    private final Path source;
    private final Path copy;
    private final Charset charset;
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(LogDocument.daemon("lm-utf16"));
    private CharsetDecoder decoder;
    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private long consumed;       // bytes of the source converted so far
    private byte[] firstBytes = new byte[0];
    private volatile boolean closed;

    private Utf16Mirror(Path source, Path copy, Charset charset) {
        this.source = source;
        this.copy = copy;
        this.charset = charset;
    }

    /** Converts {@code source} into {@code copy}. */
    public static Utf16Mirror create(Path source, Path copy, Charset charset, DoubleConsumer progress,
                                     BooleanSupplier cancelled) throws IOException {
        Utf16Mirror m = new Utf16Mirror(source, copy, charset);
        m.rewrite(progress, cancelled);
        return m;
    }

    public Path copy() {
        return copy;
    }

    /** Keeps converting what is appended, checking every {@code millis} ms. */
    public void follow(long millis) {
        timer.scheduleWithFixedDelay(() -> {
            try {
                update();
            } catch (IOException | RuntimeException e) {
                // the source may be rotating; try again
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    /** Converts new data; rewrites the copy if the source was truncated or replaced. */
    synchronized void update() throws IOException {
        if (closed) {
            return;
        }
        long size = Files.size(source);
        if (size < consumed || !sameStart()) {
            rewrite(null, () -> closed);
            return;
        }
        if (size > consumed) {
            try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
                 OutputStream out = Files.newOutputStream(copy, StandardOpenOption.APPEND)) {
                convert(in, out, size, null, () -> closed);
            }
        }
    }

    private boolean sameStart() throws IOException {
        if (firstBytes.length == 0) {
            return true;
        }
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ)) {
            ByteBuffer b = ByteBuffer.allocate(firstBytes.length);
            in.read(b, 0);
            return java.util.Arrays.equals(b.array(), firstBytes);
        }
    }

    private synchronized void rewrite(DoubleConsumer progress, BooleanSupplier cancelled) throws IOException {
        consumed = 0;
        decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        long size = Files.size(source);
        try (FileChannel in = FileChannel.open(source, StandardOpenOption.READ);
             OutputStream out = Files.newOutputStream(copy)) {
            ByteBuffer head = ByteBuffer.allocate((int) Math.min(64, size));
            in.read(head, 0);
            firstBytes = java.util.Arrays.copyOf(head.array(), head.position());
            convert(in, out, size, progress, cancelled);
        }
    }

    /** Decodes source bytes [consumed, size) and appends them as UTF-8. */
    private void convert(FileChannel in, OutputStream out, long size, DoubleConsumer progress,
                         BooleanSupplier cancelled) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(1 << 20);
        CharBuffer chars = CharBuffer.allocate(1 << 20);
        ByteBuffer utf8 = ByteBuffer.allocate(4 << 20);
        long pos = consumed;
        while (pos < size && !cancelled.getAsBoolean()) {
            bytes.clear();
            bytes.limit((int) Math.min(bytes.capacity(), size - pos));
            int n = in.read(bytes, pos);
            if (n <= 0) {
                break;
            }
            bytes.flip();
            chars.clear();
            CoderResult r = decoder.decode(bytes, chars, false);
            if (r.isError()) {
                r.throwException();
            }
            // bytes left in the buffer (half a char) are read again next time
            pos += bytes.position();
            chars.flip();
            if (consumed == 0 && chars.hasRemaining() && chars.get(0) == '﻿') {
                chars.get(); // drop the byte order mark
            }
            utf8.clear();
            encoder.encode(chars, utf8, false);
            out.write(utf8.array(), 0, utf8.position());
            consumed = pos;
            if (progress != null) {
                progress.accept((double) pos / Math.max(1, size));
            }
            if (bytes.position() == 0) {
                break; // only an incomplete char is left: wait for more data
            }
        }
        out.flush();
    }

    @Override
    public void close() {
        closed = true;
        timer.shutdown();
        try {
            Files.deleteIfExists(copy);
        } catch (IOException e) {
            // a temp file; it goes away on exit too
        }
    }
}
