package com.unbi.engine.nodes.files;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reading files as text, without pretending every file is text.
 *
 * <p>Shared by the search and replace nodes. A batch run over a real folder will meet PNGs, jars and
 * half-written files; treating those as UTF-8 produces either mojibake matches or an exception that
 * kills a run over thousands of good files. Both are worse than skipping the file.
 */
final class TextFiles {

    /** Files above this are skipped: they are not what this pack is for, and they stall a run. */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    private static final int SNIFF_BYTES = 8192;

    private TextFiles() {}

    /**
     * @return the decoded content, or empty when the file is binary, too large, or unreadable
     */
    static Optional<String> read(Path file) {
        try {
            if (Files.size(file) > MAX_BYTES) {
                return Optional.empty();
            }
            var bytes = Files.readAllBytes(file);
            if (looksBinary(bytes)) {
                return Optional.empty();
            }
            return Optional.of(decodeStrictUtf8(bytes));
        } catch (IOException notText) {
            // Covers CharacterCodingException from the strict decoder too: a file that is not
            // valid UTF-8 is, for this pack, simply not a text file.
            return Optional.empty();
        }
    }

    static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * A NUL byte in the first few KB is the same heuristic git uses, and it is right often enough
     * to be worth its two lines.
     */
    private static boolean looksBinary(byte[] bytes) {
        var limit = Math.min(bytes.length, SNIFF_BYTES);
        for (int index = 0; index < limit; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Strict rather than lenient decoding: replacement characters would turn a binary file into a
     * string full of U+FFFD that then gets happily searched and, worse, rewritten.
     */
    private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer decoded = decoder.decode(java.nio.ByteBuffer.wrap(bytes));
        return decoded.toString();
    }
}
