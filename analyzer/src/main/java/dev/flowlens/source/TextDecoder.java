package dev.flowlens.source;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes source bytes as UTF-8, falling back to Windows-1252 (or ISO-8859-1) for legacy
 * files instead of skipping them. Strips a leading BOM.
 */
public final class TextDecoder {

    private static final Charset LEGACY = Charset.isSupported("windows-1252")
            ? Charset.forName("windows-1252") : StandardCharsets.ISO_8859_1;

    public record Decoded(String text, Charset charset) {
        public boolean isFallback() { return !StandardCharsets.UTF_8.equals(charset); }
    }

    private TextDecoder() {}

    public static Decoded decode(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        try {
            String s = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(buf).toString();
            return new Decoded(s, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return new Decoded(new String(bytes, offset, bytes.length - offset, LEGACY), LEGACY);
        }
    }
}
