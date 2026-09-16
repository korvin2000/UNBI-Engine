package com.unbi.engine.core.run;

import java.time.Instant;
import java.util.List;

/**
 * Everything the engine tells the outside world while a run is in flight.
 *
 * <p>Sealed, so the transport encoder is exhaustive by construction: a new event kind cannot be
 * added without the compiler pointing at the encoder that has to carry it to the browser.
 *
 * <p>Events for one run are emitted in order from a single thread. The UI relies on that — a
 * {@code COMPLETED} arriving before the {@code RUNNING} it supersedes would leave a node spinning
 * forever.
 */
public sealed interface EngineEvent {

    String runId();

    Instant at();

    record RunStarted(String runId, List<String> order, Instant at) implements EngineEvent {
        public RunStarted {
            order = List.copyOf(order);
        }
    }

    /**
     * @param durationMillis wall time for the node; null unless the state is terminal
     */
    record NodeStateChanged(
            String runId, String nodeId, NodeState state, String message, Long durationMillis, Instant at)
            implements EngineEvent {}

    record NodeProgress(String runId, String nodeId, double fraction, String message, Instant at)
            implements EngineEvent {}

    record NodeLog(String runId, String nodeId, String message, Instant at) implements EngineEvent {}

    /**
     * Text a node has produced so far, before it finishes.
     *
     * <p>Separate from {@link NodeLog} because the two are folded differently: logs are lines to
     * keep, a stream is one growing value to append to. Sending tokens as log lines would fill the
     * node's log with fragments of a sentence and still not let the editor show the sentence.
     */
    record NodeStream(String runId, String nodeId, String portKey, String chunk, Instant at)
            implements EngineEvent {}

    record RunFinished(String runId, RunOutcome outcome, String message, long durationMillis, Instant at)
            implements EngineEvent {}

    /** Graph rejected before anything ran. Carries the validator issues, already rendered. */
    record RunRejected(String runId, List<String> problems, Instant at) implements EngineEvent {
        public RunRejected {
            problems = List.copyOf(problems);
        }
    }
}
