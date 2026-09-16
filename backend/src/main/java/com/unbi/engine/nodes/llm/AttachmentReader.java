package com.unbi.engine.nodes.llm;

import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.nodes.files.model.FileRef;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Reading a file into the kind of attachment a gateway will accept.
 *
 * <p>Shared by Attach Files and LLM Batch, because "what is this file, and how does it go into a
 * request" is one decision and two answers to it would drift the first time a format was added.
 */
final class AttachmentReader {

    private static final Map<String, String> IMAGE_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "webp", "image/webp",
            "gif", "image/gif",
            "bmp", "image/bmp");

    private static final Map<String, String> DOCUMENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private AttachmentReader() {}

    /**
     * @param textAsText send a readable file as prompt text rather than as an opaque file part —
     *     cheaper, and understood by every model rather than only by those declaring file input
     */
    static Attachment read(FileRef file, boolean textAsText) throws IOException {
        var path = file.toPath();
        if (!Files.isReadable(path)) {
            throw new IllegalStateException("Cannot read " + file.path());
        }
        var extension = file.extension().toLowerCase(Locale.ROOT);

        var image = IMAGE_TYPES.get(extension);
        if (image != null) {
            return Attachment.image(file.name(), image, Files.readAllBytes(path));
        }
        var document = DOCUMENT_TYPES.get(extension);
        if (document != null) {
            return Attachment.document(file.name(), document, Files.readAllBytes(path));
        }
        if (textAsText) {
            var text = readTextOrNull(path);
            if (text != null) {
                return Attachment.text(file.name(), textMediaType(extension), text);
            }
        }
        return Attachment.document(file.name(), "application/octet-stream", Files.readAllBytes(path));
    }

    /**
     * @return the file as UTF-8 text, or null when it is not text at all
     *
     * <p>Strict decoding rather than a lenient read: a binary file decoded leniently becomes a page
     * of replacement characters that costs tokens, carries nothing, and looks to the reader like the
     * model failing to understand a document it was never really sent.
     */
    static String readTextOrNull(Path path) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString();
        } catch (CharacterCodingException notText) {
            return null;
        }
    }

    static String textMediaType(String extension) {
        return switch (extension) {
            case "md", "markdown" -> "text/markdown";
            case "json" -> "application/json";
            case "csv" -> "text/csv";
            case "html", "htm" -> "text/html";
            case "xml" -> "application/xml";
            case "yaml", "yml" -> "application/yaml";
            default -> "text/plain";
        };
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return "%.1f KB".formatted(bytes / 1024d);
        }
        return "%.1f MB".formatted(bytes / (1024d * 1024d));
    }
}
