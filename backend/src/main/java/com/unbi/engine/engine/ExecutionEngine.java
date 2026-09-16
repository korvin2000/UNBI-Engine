package com.unbi.engine.engine;

import com.unbi.engine.core.graph.ExecutionPlan;
import com.unbi.engine.core.graph.GraphValidator;
import com.unbi.engine.core.graph.InputResolver;
import com.unbi.engine.core.graph.WorkflowGraph;
import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.run.EngineEvent;
import com.unbi.engine.core.run.NodeState;
import com.unbi.engine.core.run.RunListener;
import com.unbi.engine.core.run.RunOutcome;
import com.unbi.engine.core.type.TypeSystem;
import com.unbi.engine.registry.NodeRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs a workflow and narrates it.
 *
 * <p>Validate, order, then execute in topological sequence on a virtual thread. Sequential
 * execution is a deliberate v1 choice: it makes the value store trivially safe, makes failure
 * propagation obvious, and makes two runs of the same graph produce the same event order. Running
 * independent branches in parallel is the natural next step and the seam for it is
 * {@link ExecutionPlan} — it would return levels instead of a flat list, and nothing else here
 * would need to change shape.
 */
@Service
public class ExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final NodeRegistry registry;
    private final TypeSystem types;
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();

    public ExecutionEngine(NodeRegistry registry, TypeSystem types) {
        this.registry = registry;
        this.types = types;
    }

    private record ActiveRun(AtomicBoolean cancelled, Thread thread) {}

    /**
     * Starts a run and returns immediately with its id.
     *
     * <p>The listener is invoked from the run thread, in order. A rejected graph still produces a
     * run id and a {@code RunRejected} event, so the client has one code path for "it did not work"
     * rather than two.
     */
    public String submit(WorkflowGraph graph, RunListener listener) {
        var runId = UUID.randomUUID().toString();
        var cancelled = new AtomicBoolean(false);
        var thread = Thread.ofVirtual()
                .name("unbi-run-" + runId)
                .unstarted(() -> {
                    try {
                        execute(runId, graph, listener, cancelled);
                    } finally {
                        active.remove(runId);
                    }
                });
        active.put(runId, new ActiveRun(cancelled, thread));
        thread.start();
        return runId;
    }

    /** @return false if the run had already finished or was never known */
    public boolean cancel(String runId) {
        var run = active.get(runId);
        if (run == null) {
            return false;
        }
        run.cancelled().set(true);
        return true;
    }

    private void execute(String runId, WorkflowGraph graph, RunListener listener, AtomicBoolean cancelled) {
        var emit = new SafeEmitter(listener, runId);
        var startedAt = Instant.now();

        var report = new GraphValidator(registry, types).validate(graph);
        if (!report.isRunnable()) {
            emit.send(new EngineEvent.RunRejected(
                    runId, report.issues().stream().map(GraphValidator.Issue::message).toList(), Instant.now()));
            emit.send(new EngineEvent.RunFinished(
                    runId, RunOutcome.REJECTED, report.summary(), elapsed(startedAt), Instant.now()));
            return;
        }

        var plan = ExecutionPlan.order(graph).plan();
        emit.send(new EngineEvent.RunStarted(runId, plan.order(), Instant.now()));
        for (var nodeId : plan.order()) {
            emit.send(nodeState(runId, nodeId, NodeState.QUEUED, null, null));
        }

        var values = new ValueStore();
        var failed = new HashSet<String>();
        var outcome = RunOutcome.COMPLETED;
        String failureMessage = null;

        for (var nodeId : plan.order()) {
            if (cancelled.get()) {
                emit.send(nodeState(runId, nodeId, NodeState.CANCELLED, null, null));
                outcome = RunOutcome.CANCELLED;
                continue;
            }
            if (dependsOnFailure(graph, nodeId, failed)) {
                failed.add(nodeId);
                emit.send(nodeState(runId, nodeId, NodeState.SKIPPED, "An upstream node failed", null));
                continue;
            }

            var node = graph.node(nodeId).orElseThrow();
            var definition = registry.require(node.typeId());
            var descriptor = definition.descriptor();
            var nodeStartedAt = Instant.now();
            emit.send(nodeState(runId, nodeId, NodeState.RUNNING, null, null));

            try {
                var inputs = InputResolver.resolve(descriptor, node, graph.incoming(nodeId), values::get);
                var context = new RunNodeContext(runId, nodeId, inputs, descriptor, values, cancelled, emit);
                definition.execute(context);
                context.assertAllOutputsProduced();
                emit.send(nodeState(runId, nodeId, NodeState.COMPLETED, null, elapsed(nodeStartedAt)));
            } catch (NodeContext.CancellationSignal signal) {
                emit.send(nodeState(runId, nodeId, NodeState.CANCELLED, null, elapsed(nodeStartedAt)));
                outcome = RunOutcome.CANCELLED;
            } catch (Exception failure) {
                failed.add(nodeId);
                outcome = RunOutcome.FAILED;
                var message = describe(failure);
                failureMessage = failureMessage == null ? descriptor.label() + ": " + message : failureMessage;
                log.warn("Node {} ({}) failed during run {}", nodeId, node.typeId(), runId, failure);
                emit.send(nodeState(runId, nodeId, NodeState.FAILED, message, elapsed(nodeStartedAt)));
            }
        }

        if (cancelled.get()) {
            outcome = RunOutcome.CANCELLED;
        }
        emit.send(new EngineEvent.RunFinished(
                runId,
                outcome,
                switch (outcome) {
                    case COMPLETED -> "Finished";
                    case CANCELLED -> "Cancelled";
                    case FAILED -> failureMessage == null ? "Failed" : failureMessage;
                    case REJECTED -> "Rejected";
                },
                elapsed(startedAt),
                Instant.now()));
    }

    private boolean dependsOnFailure(WorkflowGraph graph, String nodeId, Set<String> failed) {
        return graph.incoming(nodeId).stream()
                .anyMatch(edge -> failed.contains(edge.sourceNode()));
    }

    private static EngineEvent nodeState(
            String runId, String nodeId, NodeState state, String message, Long durationMillis) {
        return new EngineEvent.NodeStateChanged(runId, nodeId, state, message, durationMillis, Instant.now());
    }

    private static long elapsed(Instant from) {
        return Duration.between(from, Instant.now()).toMillis();
    }

    /**
     * Exception messages are what a user reads when a node goes red, and some exceptions have none.
     */
    private static String describe(Exception failure) {
        var message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /** Values produced by completed nodes, keyed by node and output port. */
    static final class ValueStore {

        /**
         * A record key rather than a concatenated string. Node ids are user-supplied, so any
         * separator character could appear inside one and make two different ports share a key.
         */
        private record Slot(String nodeId, String portKey) {}

        private final Map<Slot, Object> values = new ConcurrentHashMap<>();

        Object get(String nodeId, String portKey) {
            return values.get(new Slot(nodeId, portKey));
        }

        void put(String nodeId, String portKey, Object value) {
            if (value == null) {
                values.remove(new Slot(nodeId, portKey));
            } else {
                values.put(new Slot(nodeId, portKey), value);
            }
        }
    }

    /**
     * Keeps a broken listener from breaking the run.
     *
     * <p>The listener is usually a WebSocket; a client that closes its tab mid-run must not turn
     * into a failed workflow. Delivery failures are logged once per run and then swallowed.
     */
    private static final class SafeEmitter {

        private final RunListener listener;
        private final String runId;
        private boolean reportedFailure;

        SafeEmitter(RunListener listener, String runId) {
            this.listener = listener;
            this.runId = runId;
        }

        void send(EngineEvent event) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException delivery) {
                if (!reportedFailure) {
                    reportedFailure = true;
                    log.info("Dropping events for run {}: listener is not accepting them ({})",
                            runId, delivery.toString());
                }
            }
        }
    }

    /** The engine-side implementation of what a node is allowed to do. */
    private record RunNodeContext(
            String runId,
            String nodeId,
            Map<String, Object> inputs,
            com.unbi.engine.core.node.NodeDescriptor descriptor,
            ValueStore values,
            AtomicBoolean cancelled,
            SafeEmitter emit)
            implements NodeContext {

        @Override
        public Object rawInput(String key) {
            if (descriptor.inputs().stream().noneMatch(input -> input.key().equals(key))) {
                throw new IllegalArgumentException(
                        "Node %s read undeclared input %s".formatted(descriptor.id(), key));
            }
            return inputs.get(key);
        }

        @Override
        public void output(String key, Object value) {
            descriptor.output(key); // throws if the node writes something it never declared
            values.put(nodeId, key, value);
        }

        @Override
        public void progress(double fraction, String message) {
            var clamped = Math.max(0, Math.min(1, fraction));
            emit.send(new EngineEvent.NodeProgress(runId, nodeId, clamped, message, Instant.now()));
        }

        @Override
        public void log(String message) {
            emit.send(new EngineEvent.NodeLog(runId, nodeId, message, Instant.now()));
        }

        @Override
        public void stream(String key, String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            descriptor.output(key); // throws if the node streams towards a port it never declared
            emit.send(new EngineEvent.NodeStream(runId, nodeId, key, chunk, Instant.now()));
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        /**
         * A node that returns without writing a declared output would hand null to whatever it
         * feeds, and the failure would surface one node later with a confusing message. Catch it
         * where it happened.
         */
        void assertAllOutputsProduced() {
            for (var output : descriptor.outputs()) {
                if (values.get(nodeId, output.key()) == null) {
                    throw new IllegalStateException(
                            "Node finished without producing its %s output".formatted(output.label()));
                }
            }
        }
    }
}
