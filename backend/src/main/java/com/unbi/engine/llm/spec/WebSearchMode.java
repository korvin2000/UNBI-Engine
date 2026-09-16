package com.unbi.engine.llm.spec;

import java.util.Locale;

/**
 * How a target obtains web results.
 *
 * <p>Four genuinely different mechanisms, and choosing the wrong one is silent: a model without
 * search answers the same question fluently, with a citation, from memory. Only
 * {@link #RESPONSES_TOOL} and {@link #HOSTED} return provider-side evidence that a search actually
 * happened, which is why the request node can be told to require it.
 */
public enum WebSearchMode {

    /** No search. The default, and the reason a model has to opt in. */
    NONE("none", "Off"),
    /** Responses API {@code tools: [{ type: web_search }]}. Verifiable: the call is in the output. */
    RESPONSES_TOOL("responses_tool", "Responses tool"),
    /** A model that searches by nature, such as a hosted search model. */
    HOSTED("hosted", "Hosted search model"),
    /** The {@code :online} model-name suffix, which bills a search fee on top of tokens. */
    ONLINE("online", "Online suffix"),
    /** A gateway web plugin, configured through the model's extra body. */
    PLUGIN("plugin", "Web plugin");

    private final String wireName;
    private final String label;

    WebSearchMode(String wireName, String label) {
        this.wireName = wireName;
        this.label = label;
    }

    public String wireName() {
        return wireName;
    }

    public String label() {
        return label;
    }

    public boolean isActive() {
        return this != NONE;
    }

    public static WebSearchMode of(String raw) {
        if (raw == null) {
            return NONE;
        }
        var trimmed = raw.trim().toLowerCase(Locale.ROOT);
        for (var value : values()) {
            if (value.wireName.equals(trimmed)) {
                return value;
            }
        }
        return NONE;
    }
}
