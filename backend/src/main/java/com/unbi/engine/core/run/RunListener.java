package com.unbi.engine.core.run;

/**
 * Receives engine events for one run.
 *
 * <p>Called synchronously and in order from the run thread, so implementations must not block for
 * long. The engine isolates listener failures: a socket that dies mid-run must not take the run
 * down with it.
 */
@FunctionalInterface
public interface RunListener {

    void onEvent(EngineEvent event);

    RunListener IGNORE = event -> {};
}
