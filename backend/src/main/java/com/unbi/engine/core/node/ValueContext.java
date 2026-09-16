package com.unbi.engine.core.node;

import java.util.List;
import java.util.Map;

/**
 * A {@link NodeContext} over a plain map of widget values, with nowhere for output to go.
 *
 * <p>This is what lets a probe and a run share one implementation. "Which base URL does this node
 * mean?" has exactly one right answer, and the way to guarantee a test button and a run agree about
 * it is for both to call the same function — so the spec builders take a {@code NodeContext}, and
 * this is the one a probe hands them.
 *
 * <p>Outputs are discarded and progress is ignored: a probe produces no values for the graph. Logs
 * are collected, because the sentences a spec builder writes while resolving a profile are exactly
 * the evidence a user wants back from a test.
 */
public final class ValueContext implements NodeContext {

    private final Map<String, Object> values;
    private final List<String> logs = new java.util.ArrayList<>();

    public ValueContext(Map<String, Object> values) {
        this.values = Map.copyOf(values == null ? Map.of() : values);
    }

    @Override
    public Object rawInput(String key) {
        var value = values.get(key);
        // A blank string is how an untouched text field arrives, and every caller here means
        // "nothing was said" by it. Collapsing it once is what keeps the spec builders linear.
        return value instanceof String text && text.isBlank() ? null : value;
    }

    @Override
    public void output(String key, Object value) {
        // A probe answers a question; it does not feed the graph.
    }

    @Override
    public void progress(double fraction, String message) {
        // Nothing is running, so nothing has progress.
    }

    @Override
    public void log(String message) {
        if (message != null && !message.isBlank()) {
            logs.add(message);
        }
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    /** What the spec builders said while resolving this configuration. */
    public List<String> logs() {
        return List.copyOf(logs);
    }
}
