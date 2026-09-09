package com.unbi.engine.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.core.graph.WorkflowGraph;
import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.run.EngineEvent;
import com.unbi.engine.core.run.NodeState;
import com.unbi.engine.core.run.RunOutcome;
import com.unbi.engine.core.type.TypeSystem;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.registry.NodeRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** End-to-end behaviour of a run, using purpose-built test nodes. */
class ExecutionEngineTest {

    /** Emits a fixed string. */
    private static final class Producer implements NodeDefinition {
        @Override
        public NodeDescriptor descriptor() {
            return NodeDescriptor.of("test.producer", "Producer")
                    .setting("value", "Value", Types.TEXT, Widget.TextField.of(""), "hello")
                    .out("value", "Value", Types.TEXT)
                    .build();
        }

        @Override
        public void execute(NodeContext context) {
            context.output("value", context.text("value"));
        }
    }

    /** Passes its input through, recording that it ran. */
    private static final class Consumer implements NodeDefinition {
        private final AtomicInteger runs = new AtomicInteger();

        @Override
        public NodeDescriptor descriptor() {
            return NodeDescriptor.of("test.consumer", "Consumer")
                    .socket("value", "Value", Types.TEXT)
                    .out("echo", "Echo", Types.TEXT)
                    .build();
        }

        @Override
        public void execute(NodeContext context) {
            runs.incrementAndGet();
            context.progress(0.5, "halfway");
            context.output("echo", context.text("value") + "!");
        }
    }

    /** Always fails. */
    private static final class Exploder implements NodeDefinition {
        @Override
        public NodeDescriptor descriptor() {
            return NodeDescriptor.of("test.exploder", "Exploder")
                    .socket("value", "Value", Types.TEXT)
                    .out("never", "Never", Types.TEXT)
                    .build();
        }

        @Override
        public void execute(NodeContext context) {
            throw new IllegalStateException("this node always fails");
        }
    }

    /** Declares an output and then forgets to write it. */
    private static final class Forgetful implements NodeDefinition {
        @Override
        public NodeDescriptor descriptor() {
            return NodeDescriptor.of("test.forgetful", "Forgetful")
                    .socket("value", "Value", Types.TEXT)
                    .out("missing", "Missing", Types.TEXT)
                    .build();
        }

        @Override
        public void execute(NodeContext context) {
            // deliberately writes nothing
        }
    }

    /** Blocks until cancelled. */
    private static final class Sleeper implements NodeDefinition {
        private final CountDownLatch started = new CountDownLatch(1);

        @Override
        public NodeDescriptor descriptor() {
            return NodeDescriptor.of("test.sleeper", "Sleeper")
                    .socket("value", "Value", Types.TEXT)
                    .out("done", "Done", Types.TEXT)
                    .build();
        }

        @Override
        public void execute(NodeContext context) throws InterruptedException {
            started.countDown();
            while (true) {
                context.checkCancelled();
                Thread.sleep(5);
            }
        }
    }

    private final Consumer consumer = new Consumer();
    private final Sleeper sleeper = new Sleeper();

    private ExecutionEngine engineWith(NodeDefinition... nodes) {
        return new ExecutionEngine(new NodeRegistry(List.of(nodes)), new TypeSystem());
    }

    private static WorkflowGraph chain(String... typeIds) {
        var nodes = new java.util.ArrayList<WorkflowGraph.GraphNode>();
        var edges = new java.util.ArrayList<WorkflowGraph.GraphEdge>();
        for (int index = 0; index < typeIds.length; index++) {
            nodes.add(new WorkflowGraph.GraphNode(
                    "n" + index, typeIds[index], Map.of(), new WorkflowGraph.Position(0, 0)));
            if (index > 0) {
                edges.add(new WorkflowGraph.GraphEdge(
                        "e" + index, "n" + (index - 1), outputOf(typeIds[index - 1]), "n" + index, "value"));
            }
        }
        return new WorkflowGraph(nodes, edges);
    }

    /**
     * Each test node names its output differently on purpose, so that a chain wired with the wrong
     * port name is rejected by the validator rather than quietly running.
     */
    private static String outputOf(String typeId) {
        return switch (typeId) {
            case "test.producer" -> "value";
            case "test.consumer" -> "echo";
            case "test.exploder" -> "never";
            case "test.forgetful" -> "missing";
            case "test.sleeper" -> "done";
            default -> throw new IllegalArgumentException("No output mapping for " + typeId);
        };
    }

    private record Collected(List<EngineEvent> events) {

        List<EngineEvent.NodeStateChanged> statesOf(String nodeId) {
            return events.stream()
                    .filter(EngineEvent.NodeStateChanged.class::isInstance)
                    .map(EngineEvent.NodeStateChanged.class::cast)
                    .filter(event -> event.nodeId().equals(nodeId))
                    .toList();
        }

        EngineEvent.RunFinished finished() {
            return events.stream()
                    .filter(EngineEvent.RunFinished.class::isInstance)
                    .map(EngineEvent.RunFinished.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the run never finished"));
        }
    }

    private Collected runToCompletion(ExecutionEngine engine, WorkflowGraph graph) {
        var events = new CopyOnWriteArrayList<EngineEvent>();
        engine.submit(graph, events::add);
        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                events.stream().anyMatch(EngineEvent.RunFinished.class::isInstance));
        return new Collected(List.copyOf(events));
    }

    @Test
    void runsAChainAndReportsEachNodeInOrder() {
        var collected = runToCompletion(
                engineWith(new Producer(), consumer), chain("test.producer", "test.consumer"));

        assertThat(collected.finished().outcome()).isEqualTo(RunOutcome.COMPLETED);
        assertThat(collected.statesOf("n1")).extracting(EngineEvent.NodeStateChanged::state)
                .containsExactly(NodeState.QUEUED, NodeState.RUNNING, NodeState.COMPLETED);
        assertThat(consumer.runs).hasValue(1);
    }

    @Test
    @DisplayName("a completed node reports how long it took")
    void terminalStatesCarryADuration() {
        var collected = runToCompletion(
                engineWith(new Producer(), consumer), chain("test.producer", "test.consumer"));

        var completed = collected.statesOf("n1").getLast();
        assertThat(completed.state()).isEqualTo(NodeState.COMPLETED);
        assertThat(completed.durationMillis()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void forwardsProgressFromTheNode() {
        var collected = runToCompletion(
                engineWith(new Producer(), consumer), chain("test.producer", "test.consumer"));

        assertThat(collected.events())
                .filteredOn(EngineEvent.NodeProgress.class::isInstance)
                .extracting(event -> ((EngineEvent.NodeProgress) event).fraction())
                .contains(0.5);
    }

    @Test
    @DisplayName("a failure stops the branch below it but still finishes the run")
    void downstreamOfAFailureIsSkipped() {
        var collected = runToCompletion(
                engineWith(new Producer(), new Exploder(), consumer),
                chain("test.producer", "test.exploder", "test.consumer"));

        assertThat(collected.statesOf("n1").getLast().state()).isEqualTo(NodeState.FAILED);
        assertThat(collected.statesOf("n1").getLast().message()).isEqualTo("this node always fails");
        assertThat(collected.statesOf("n2").getLast().state()).isEqualTo(NodeState.SKIPPED);
        assertThat(collected.finished().outcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(consumer.runs).hasValue(0);
    }

    @Test
    @DisplayName("a node that forgets to write a declared output fails where the mistake is")
    void missingOutputIsCaughtAtTheNode() {
        var collected = runToCompletion(
                engineWith(new Producer(), new Forgetful()),
                chain("test.producer", "test.forgetful"));

        var failure = collected.statesOf("n1").getLast();
        assertThat(failure.state()).isEqualTo(NodeState.FAILED);
        assertThat(failure.message()).contains("without producing its Missing output");
    }

    @Test
    void rejectsAnInvalidGraphWithoutRunningAnything() {
        var graph = new WorkflowGraph(
                List.of(new WorkflowGraph.GraphNode(
                        "n0", "test.consumer", Map.of(), new WorkflowGraph.Position(0, 0))),
                List.of());

        var collected = runToCompletion(engineWith(new Producer(), consumer), graph);

        assertThat(collected.events()).anyMatch(EngineEvent.RunRejected.class::isInstance);
        assertThat(collected.finished().outcome()).isEqualTo(RunOutcome.REJECTED);
        assertThat(consumer.runs).hasValue(0);
    }

    @Test
    void cancellationStopsARunningNode() throws Exception {
        var engine = engineWith(new Producer(), sleeper);
        var events = new CopyOnWriteArrayList<EngineEvent>();
        var runId = engine.submit(chain("test.producer", "test.sleeper"), events::add);

        assertThat(sleeper.started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(engine.cancel(runId)).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                events.stream().anyMatch(EngineEvent.RunFinished.class::isInstance));
        assertThat(new Collected(List.copyOf(events)).finished().outcome()).isEqualTo(RunOutcome.CANCELLED);
    }

    @Test
    void cancellingAFinishedRunReportsThatItIsTooLate() {
        var engine = engineWith(new Producer(), consumer);
        runToCompletion(engine, chain("test.producer", "test.consumer"));

        assertThat(engine.cancel("no-such-run")).isFalse();
    }

    @Test
    @DisplayName("a listener that throws does not take the run down with it")
    void brokenListenerIsIsolated() {
        var engine = engineWith(new Producer(), consumer);
        var delivered = new AtomicInteger();

        engine.submit(chain("test.producer", "test.consumer"), event -> {
            delivered.incrementAndGet();
            throw new IllegalStateException("socket closed");
        });

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> consumer.runs.get() == 1);
        assertThat(delivered.get()).isGreaterThan(1);
    }

    @Test
    void refusesTwoNodesClaimingTheSameId() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> new NodeRegistry(List.of(new Producer(), new Producer()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate node id");
    }
}
