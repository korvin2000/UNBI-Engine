package com.unbi.engine.llm.spec;

import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * One non-prose thing sent along with a prompt: an image, a document, or a block of text read from
 * a file.
 *
 * <p>A class rather than a record, and that is the point. Records are reflected over by the preview
 * and report nodes, so a record here would put a base64 payload in a table cell the moment anyone
 * wired an attachment into a preview. This exposes the four things worth showing and renders itself
 * as one readable line.
 */
public final class Attachment {

    private final Kind kind;
    private final String name;
    private final String mediaType;
    private final String text;
    private final byte[] bytes;

    private Attachment(Kind kind, String name, String mediaType, String text, byte[] bytes) {
        this.kind = kind;
        this.name = name;
        this.mediaType = mediaType;
        this.text = text;
        this.bytes = bytes;
    }

    public static Attachment text(String name, String mediaType, String content) {
        return new Attachment(
                Kind.TEXT,
                requireName(name),
                blankTo(mediaType, "text/plain"),
                Objects.requireNonNull(content, "content"),
                null);
    }

    public static Attachment image(String name, String mediaType, byte[] content) {
        return new Attachment(
                Kind.IMAGE, requireName(name), blankTo(mediaType, "image/png"), null, content.clone());
    }

    public static Attachment document(String name, String mediaType, byte[] content) {
        return new Attachment(
                Kind.DOCUMENT,
                requireName(name),
                blankTo(mediaType, "application/octet-stream"),
                null,
                content.clone());
    }

    public Kind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public String mediaType() {
        return mediaType;
    }

    /** Text content; empty for binary kinds. */
    public String text() {
        return text == null ? "" : text;
    }

    /** Size in bytes, whichever kind this is. */
    public long size() {
        return bytes != null ? bytes.length : text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /** The {@code data:} URL form every OpenAI-compatible gateway accepts for inline binary parts. */
    public String dataUrl() {
        if (bytes == null) {
            throw new IllegalStateException("A text attachment has no binary payload: " + name);
        }
        return "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    public String base64() {
        if (bytes == null) {
            throw new IllegalStateException("A text attachment has no binary payload: " + name);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Which capability a model must declare before this may be sent. */
    public Capability requiredCapability() {
        return switch (kind) {
            case IMAGE -> Capability.VISION;
            case DOCUMENT -> Capability.FILES;
            case TEXT -> null;
        };
    }

    @Override
    public String toString() {
        return "%s (%s, %s)".formatted(name, mediaType, humanSize(size()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Attachment that
                && kind == that.kind
                && name.equals(that.name)
                && mediaType.equals(that.mediaType)
                && Objects.equals(text, that.text)
                && java.util.Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, name, mediaType, text, java.util.Arrays.hashCode(bytes));
    }

    public enum Kind {
        TEXT,
        IMAGE,
        DOCUMENT;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An attachment needs a name");
        }
        return name;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
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
