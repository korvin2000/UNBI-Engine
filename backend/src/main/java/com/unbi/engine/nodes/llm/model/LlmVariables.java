package com.unbi.engine.nodes.llm.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Named values on their way into a prompt template.
 *
 * <p>A wrapper rather than a bare {@code Map} so that the port type means something: a map wired
 * into a template input is a binding, and a map wired in from somewhere else is a coincidence. It
 * also gives the value a {@code toString} that shows what is bound, which is what a preview of a
 * misbehaving prompt needs.
 */
public record LlmVariables(SequencedMap<String, Object> values) {

    public static final LlmVariables EMPTY = new LlmVariables(new LinkedHashMap<>());

    public LlmVariables {
        var copy = new LinkedHashMap<String, Object>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    copy.put(key.trim(), value);
                }
            });
        }
        values = java.util.Collections.unmodifiableSequencedMap(copy);
    }

    /** This on top of {@code base}: later bindings win, which is how chaining reads. */
    public LlmVariables over(LlmVariables base) {
        if (base == null || base.values.isEmpty()) {
            return this;
        }
        var merged = new LinkedHashMap<String, Object>(base.values);
        merged.putAll(values);
        return new LlmVariables(merged);
    }

    public Map<String, Object> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    @Override
    public String toString() {
        return values.isEmpty() ? "(no variables)" : String.join(", ", values.keySet());
    }
}
