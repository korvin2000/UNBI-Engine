package com.unbi.engine.llm.spec;

/**
 * Which wire dialect a target speaks.
 *
 * <p>Two, not one: hosted web search and the Codex endpoint exist only on Responses, and the
 * Chat Completions shape is what every other gateway in this pack's world serves. They differ enough
 * in request and response that pretending they are one format costs more than carrying both.
 */
public enum ApiFormat {
    CHAT_COMPLETIONS("chat_completions", "/chat/completions"),
    RESPONSES("responses", "/responses");

    private final String wireName;
    private final String path;

    ApiFormat(String wireName, String path) {
        this.wireName = wireName;
        this.path = path;
    }

    public String wireName() {
        return wireName;
    }

    /** Appended to the endpoint's base URL. */
    public String path() {
        return path;
    }

    public static ApiFormat of(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("API format must name a format");
        }
        var trimmed = raw.trim();
        for (var value : values()) {
            if (value.wireName.equalsIgnoreCase(trimmed)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown API format: " + trimmed);
    }
}
