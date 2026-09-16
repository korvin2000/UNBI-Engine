package com.unbi.engine.llm.provider;

/**
 * Where partial output goes while a call is still in flight.
 *
 * <p>Advisory by design: the complete answer always arrives through the provider's return value, so
 * a sink that drops everything loses live text and never loses data. That is what lets streaming be
 * a display concern rather than a second data path to keep correct.
 *
 * <p>{@link #cancelled()} is checked between chunks, which is the only place a long generation can
 * be stopped without waiting for the model to finish.
 */
@FunctionalInterface
public interface StreamSink {

    /** Ignores everything and never cancels. */
    StreamSink DISCARD = chunk -> {};

    void chunk(String text);

    default boolean cancelled() {
        return false;
    }

    /** Reports how far along the answer is, when the provider can tell. */
    default void progress(long charactersSoFar) {
        // Most sinks only want the text.
    }
}
