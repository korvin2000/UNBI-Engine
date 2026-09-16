package com.unbi.engine.llm.provider;

/**
 * The one line of server-sent-event parsing this pack needs.
 *
 * <p>Gateways send `data:` frames with optional `event:` and comment lines between them, and the
 * whole of what we want is the payload of a data frame. Written as a pure function so the streaming
 * path can be tested without a socket — which is what turns "does it handle a comment line?" from a
 * question into an assertion.
 */
final class SseReader {

    private SseReader() {}

    /**
     * @return the payload of a {@code data:} line, or null for anything else — a keep-alive comment,
     *     an {@code event:} name, or the blank line that separates frames
     */
    static String dataPayload(String line) {
        if (line == null || !line.startsWith("data:")) {
            return null;
        }
        // One optional space after the colon is part of the format; further whitespace may be
        // meaningful inside a payload, so only that one is removed.
        var payload = line.substring("data:".length());
        return payload.startsWith(" ") ? payload.substring(1) : payload;
    }
}
