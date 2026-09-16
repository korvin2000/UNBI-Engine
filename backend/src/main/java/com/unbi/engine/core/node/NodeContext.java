package com.unbi.engine.core.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything a node may do while it runs, and nothing else.
 *
 * <p>The narrow surface is the point. A node cannot reach the graph, the registry, its neighbours
 * or the transport. It reads inputs, writes outputs, reports progress, and checks whether it has
 * been asked to stop. That is what makes nodes trivially unit-testable: a test supplies a context,
 * not a running engine.
 *
 * <p>Only five methods are abstract. The typed accessors are defaults, so implementations stay tiny
 * while node code stays readable.
 */
public interface NodeContext {

    /** The resolved value of an input: from an edge when connected, otherwise the widget value. */
    Object rawInput(String key);

    void output(String key, Object value);

    /**
     * @param fraction 0..1, clamped by the engine
     * @param message  short status shown under the node; may be null
     */
    void progress(double fraction, String message);

    void log(String message);

    boolean isCancelled();

    /**
     * Partial output, while the node is still producing it.
     *
     * <p>A preview channel, not a second data path: the complete value always arrives through
     * {@link #output}, so a context that ignores this loses live text and never loses data. That is
     * exactly why the default does nothing — a test double has no obligation to implement it, and
     * the one thing it could get wrong is unavailable to it.
     *
     * @param key   the output port this text is heading for
     * @param chunk the text produced since the last call
     */
    default void stream(String key, String chunk) {
        // The engine overrides this. Everywhere else, streamed text is simply not shown.
    }

    /**
     * Cooperative cancellation. Long loops should call this each iteration: the engine can only
     * stop a node at the points where the node allows it.
     */
    default void checkCancelled() {
        if (isCancelled()) {
            throw new CancellationSignal();
        }
    }

    default <T> Optional<T> optional(String key, Class<T> type) {
        var value = rawInput(key);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Input %s should be %s but is %s"
                    .formatted(key, type.getSimpleName(), value.getClass().getSimpleName()));
        }
        return Optional.of(type.cast(value));
    }

    default <T> T require(String key, Class<T> type) {
        return optional(key, type)
                .orElseThrow(() -> new IllegalStateException("Required input has no value: " + key));
    }

    default String text(String key) {
        return optional(key, String.class).orElse("");
    }

    default double number(String key) {
        return optional(key, Number.class).map(Number::doubleValue).orElse(0d);
    }

    default int integer(String key) {
        return (int) number(key);
    }

    default boolean flag(String key) {
        return optional(key, Boolean.class).orElse(false);
    }

    /**
     * Reads a list input, checking every element.
     *
     * <p>Node packs pass their own record types along edges, so this is where a wrong upstream type
     * surfaces as a readable message instead of as a ClassCastException deep inside a loop.
     */
    default <T> List<T> listOf(String key, Class<T> element) {
        var value = rawInput(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalStateException("Input %s should be a list but is %s"
                    .formatted(key, value.getClass().getSimpleName()));
        }
        var result = new ArrayList<T>(raw.size());
        for (var item : raw) {
            if (!element.isInstance(item)) {
                throw new IllegalStateException("Input %s should hold %s but contains %s".formatted(
                        key, element.getSimpleName(),
                        item == null ? "null" : item.getClass().getSimpleName()));
            }
            result.add(element.cast(item));
        }
        return List.copyOf(result);
    }

    /**
     * Unwinds a cancelled node. Not an error: the engine reports the run as cancelled rather than
     * failed. Stackless, because it is control flow rather than a diagnostic.
     */
    final class CancellationSignal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public CancellationSignal() {
            super("Run cancelled", null, false, false);
        }
    }
}
