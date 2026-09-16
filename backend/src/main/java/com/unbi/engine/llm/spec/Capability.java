package com.unbi.engine.llm.spec;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Something a model is declared to be able to do.
 *
 * <p>Declaration is a claim, not proof — the point of writing it down is that a request asking for
 * something the model never claimed can be refused with a sentence instead of silently losing the
 * field on the wire. A gateway that ignores an unknown parameter hides the mistake; one that rejects
 * it fails every call for a reason nobody can see from the graph.
 */
public enum Capability {

    /** Accepts {@code response_format: json_object}. */
    JSON_OBJECT("json_object", "JSON mode"),
    /** Accepts a named JSON schema and honours it. */
    JSON_SCHEMA("json_schema", "JSON schema"),
    /** Accepts tool definitions. Declaring it does not implement a tool-calling loop here. */
    TOOLS("tools", "Tools"),
    /** Reasons before answering, and accepts being told how hard. */
    REASONING("reasoning", "Reasoning"),
    /** Reports and reuses a cached prompt prefix. */
    PROMPT_CACHE("prompt_cache", "Prompt cache"),
    /** Accepts images as input. */
    VISION("vision", "Vision"),
    /** Accepts documents (PDF and similar) as input. */
    FILES("files", "File input"),
    /** Can search the web while answering — needs a {@link WebSearchMode} to say how. */
    WEB_SEARCH("web_search", "Web search");

    private final String wireName;
    private final String label;

    Capability(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    public String wireName() {
        return wireName;
    }

    public String label() {
        return label;
    }

    public static Optional<Capability> byWireName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        var trimmed = name.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(value -> value.wireName.equals(trimmed)).findFirst();
    }

    /**
     * Parses a comma- or space-separated list, ignoring anything unrecognised.
     *
     * <p>Lenient because this reads a widget value: a capability list typed with a stray comma
     * should not fail a run, and an unknown name is caught later by the capability check when
     * something actually asks for it.
     */
    public static Set<Capability> parseList(String raw) {
        var found = new LinkedHashSet<Capability>();
        if (raw == null || raw.isBlank()) {
            return found;
        }
        for (var token : raw.split("[,\s]+")) {
            byWireName(token).ifPresent(found::add);
        }
        return found;
    }
}
