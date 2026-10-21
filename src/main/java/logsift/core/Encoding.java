package logsift.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Guesses a log's text encoding from its first bytes: a byte order mark (UTF-8, UTF-16),
 * UTF-16 without one (every other byte is zero), otherwise UTF-8 if the bytes are valid UTF-8,
 * and Windows-1252 (Western European, a superset of Latin-1) if they are not.
 */
public final class Encoding {
    private Encoding() {}

    public static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final int SAMPLE = 64 * 1024;

    /** The encoding of a file (from its first 64 KB). */
    public static Charset detect(Path file) throws IOException {
        byte[] head = new byte[SAMPLE];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, head.length);
        }
        return detect(head, n);
    }

    public static Charset detect(byte[] b, int n) {
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (n >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        // UTF-16 without BOM: mostly ASCII text has a zero in every other byte
        int even = 0;
        int odd = 0;
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) {
                if ((i & 1) == 0) {
                    even++;
                } else {
                    odd++;
                }
            }
        }
        int pairs = n / 2;
        if (pairs >= 8) {
            if (odd > pairs * 0.4 && even < pairs * 0.05) {
                return StandardCharsets.UTF_16LE;
            }
            if (even > pairs * 0.4 && odd < pairs * 0.05) {
                return StandardCharsets.UTF_16BE;
            }
        }
        return isUtf8(b, n) ? StandardCharsets.UTF_8 : WINDOWS_1252;
    }

    /** Whether the bytes are valid UTF-8 (a sequence cut at the end of the sample is fine). */
    static boolean isUtf8(byte[] b, int n) {
        CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // cut at the last newline so a truncated multi-byte char at the end doesn't count
        int end = n;
        for (int i = n - 1; i >= Math.max(0, n - 8); i--) {
            if (b[i] == '\n') {
                end = i + 1;
                break;
            }
        }
        if (end == n && n == SAMPLE) {
            end = Math.max(0, n - 4);
        }
        try {
            d.decode(ByteBuffer.wrap(b, 0, end));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    /** True for encodings where a newline is not the single byte 0x0A. */
    public static boolean isUtf16(Charset cs) {
        return cs.name().startsWith("UTF-16") || cs.name().startsWith("UTF-32");
    }

    /** A short name for the status bar. */
    public static String label(Charset cs) {
        if (cs.equals(WINDOWS_1252)) {
            return "Windows-1252";
        }
        return cs.name();
    }
}
