package logsift.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Reads a stream in some encoding (for example UTF-16) as UTF-8 bytes. */
final class Utf8Stream extends InputStream {
    private final Reader reader;
    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final CharBuffer chars = CharBuffer.allocate(64 * 1024);
    private final ByteBuffer bytes = ByteBuffer.allocate(256 * 1024);
    private boolean eof;
    private boolean first = true;

    Utf8Stream(InputStream in, Charset cs) {
        this.reader = new InputStreamReader(in, cs);
        bytes.flip();
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        while (!bytes.hasRemaining()) {
            if (eof) {
                return -1;
            }
            chars.clear();
            int n = reader.read(chars);
            if (n < 0) {
                eof = true;
                return -1;
            }
            chars.flip();
            if (first && chars.hasRemaining() && chars.get(0) == '﻿') {
                chars.get(); // byte order mark
            }
            first = false;
            bytes.clear();
            encoder.encode(chars, bytes, false);
            bytes.flip();
        }
        int k = Math.min(len, bytes.remaining());
        bytes.get(b, off, k);
        return k;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
