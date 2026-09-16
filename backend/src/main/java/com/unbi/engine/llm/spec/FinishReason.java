package com.unbi.engine.llm.spec;

import java.util.Locale;

/** Why the model stopped. {@link #LENGTH} is the one a caller must not treat as success. */
public enum FinishReason {
    STOP,
    LENGTH,
    CONTENT_FILTER,
    TOOL_CALLS,
    UNKNOWN;

    public static FinishReason of(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "stop", "end_turn", "completed" -> STOP;
            case "length", "max_tokens", "max_output_tokens" -> LENGTH;
            case "content_filter", "safety" -> CONTENT_FILTER;
            case "tool_calls", "function_call" -> TOOL_CALLS;
            default -> UNKNOWN;
        };
    }
}
