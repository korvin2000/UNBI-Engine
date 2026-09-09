package com.unbi.engine.core.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.core.node.NodeCatalog;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.PortType;
import com.unbi.engine.core.type.TypeSystem;
import com.unbi.engine.core.type.Types;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Ordering and validation, against a two-node fake catalog rather than the real registry. */
class GraphTest {

    private static final PortType FILES = PortType.list(Types.TEXT);

    private static final NodeDescriptor SOURCE = NodeDescriptor.of("test.source", "Source")
            .setting("path", "Path", Types.TEXT, Widget.TextField.of(""), "somewhere")
            .out("files", "Files", FILES)
            .build();

    private static final NodeDescriptor SINK = NodeDescriptor.of("test.sink", "Sink")
            .socket("files", "Files", FILES)
            .socket("label", "Label", Types.TEXT)
            .setting("fixed", "Fixed", Types.BOOLEAN, new Widget.Toggle(), false)
            .out("done", "Done", Types.BOOLEAN)
            .build();

    private static final NodeCatalog CATALOG = new NodeCatalog() {
        private final Map<String, NodeDescriptor> known =
                Map.of(SOURCE.id(), SOURCE, SINK.id(), SINK);

        @Override
        public Optional<NodeDescriptor> find(String typeId) {
            return Optional.ofNullable(known.get(typeId));
        }

        @Override
        public Collection<NodeDescriptor> all() {
            return known.values();
        }
    };

    private final GraphValidator validator = new GraphValidator(CATALOG, new TypeSystem());

    private static WorkflowGraph.GraphNode node(String id, String typeId) {
        return new WorkflowGraph.GraphNode(id, typeId, Map.of(), new WorkflowGraph.Position(0, 0));
    }

    private static WorkflowGraph.GraphEdge edge(String id, String from, String fromPort, String to, String toPort) {
        return new WorkflowGraph.GraphEdge(id, from, fromPort, to, toPort);
    }

    @Nested
    @DisplayName("Execution order")
    class Ordering {

        @Test
        void putsProducersBeforeConsumers() {
            var graph = new WorkflowGraph(
                    List.of(node("b", "test.sink"), node("a", "test.source")),
                    List.of(edge("e1", "a", "files", "b", "files")));

            var result = ExecutionPlan.order(graph);

            assertThat(result.isOrdered()).isTrue();
            assertThat(result.plan().order()).containsExactly("a", "b");
        }

        @Test
        @DisplayName("independent nodes keep document order, so runs are reproducible")
        void tiesBreakByDocumentOrder() {
            var graph = new WorkflowGraph(
                    List.of(node("first", "test.source"), node("second", "test.source"),
                            node("third", "test.source")),
                    List.of());

            assertThat(ExecutionPlan.order(graph).plan().order())
                    .containsExactly("first", "second", "third");
        }

        @Test
        void refusesACycleAndNamesTheLoop() {
            var graph = new WorkflowGraph(
                    List.of(node("a", "test.sink"), node("b", "test.sink")),
                    List.of(edge("e1", "a", "done", "b", "label"),
                            edge("e2", "b", "done", "a", "label")));

            var result = ExecutionPlan.order(graph);

            assertThat(result.isOrdered()).isFalse();
            assertThat(result.cycle()).containsExactlyInAnyOrder("a", "b");
        }

        @Test
        @DisplayName("a node caught behind a cycle is not itself reported as the cycle")
        void reportsOnlyTheActualLoop() {
            var graph = new WorkflowGraph(
                    List.of(node("a", "test.sink"), node("b", "test.sink"), node("downstream", "test.sink")),
                    List.of(edge("e1", "a", "done", "b", "label"),
                            edge("e2", "b", "done", "a", "label"),
                            edge("e3", "b", "done", "downstream", "label")));

            assertThat(ExecutionPlan.order(graph).cycle())
                    .containsExactlyInAnyOrder("a", "b")
                    .doesNotContain("downstream");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        void acceptsAWellFormedGraph() {
            var graph = new WorkflowGraph(
                    List.of(node("a", "test.source"),
                            new WorkflowGraph.GraphNode("b", "test.sink",
                                    Map.of("label", "hello"), new WorkflowGraph.Position(0, 0))),
                    List.of(edge("e1", "a", "files", "b", "files")));

            assertThat(validator.validate(graph).isRunnable()).isTrue();
        }

        @Test
        void rejectsAnUnknownNodeType() {
            var graph = new WorkflowGraph(List.of(node("a", "test.absent")), List.of());

            assertThat(validator.validate(graph).summary()).contains("Unknown node type");
        }

        @Test
        void rejectsIncompatiblePortTypes() {
            var graph = new WorkflowGraph(
                    List.of(node("a", "test.source"), node("b", "test.sink")),
                    List.of(edge("e1", "a", "files", "b", "label")));

            assertThat(validator.validate(graph).summary()).contains("Text");
        }

        @Test
        void rejectsAnEdgeIntoASettingThatIsNotConnectable() {
            var graph = new WorkflowGraph(
                    List.of(node("a", "test.sink"), node("b", "test.sink")),
                    List.of(edge("e1", "a", "done", "b", "fixed")));

            assertThat(validator.validate(graph).summary()).contains("cannot be connected");
        }

        @Test
        @DisplayName("two edges into one input would make the result depend on ordering")
        void rejectsASecondWriterOnOneInput() {
            var graph = new WorkflowGraph(
                    List.of(node("a", "test.source"), node("b", "test.source"), node("c", "test.sink")),
                    List.of(edge("e1", "a", "files", "c", "files"),
                            edge("e2", "b", "files", "c", "files")));

            assertThat(validator.validate(graph).summary()).contains("already has an incoming connection");
        }

        @Test
        void rejectsAMissingRequiredInput() {
            var graph = new WorkflowGraph(List.of(node("b", "test.sink")), List.of());

            assertThat(validator.validate(graph).summary()).contains("needs a value");
        }

        @Test
        void rejectsAnEdgeNamingAPortThatDoesNotExist() {
            var graph = new WorkflowGraph(
                    List.of(node("a", "test.source"), node("b", "test.sink")),
                    List.of(edge("e1", "a", "nope", "b", "files")));

            assertThat(validator.validate(graph).summary()).contains("no output named nope");
        }
    }

    @Nested
    @DisplayName("Input resolution")
    class Resolution {

        @Test
        @DisplayName("an edge beats the widget value")
        void edgeWinsOverWidgetValue() {
            var target = new WorkflowGraph.GraphNode("b", "test.sink",
                    Map.of("files", List.of("typed-in")), new WorkflowGraph.Position(0, 0));

            var resolved = InputResolver.resolve(SINK, target,
                    List.of(edge("e1", "a", "files", "b", "files")),
                    (nodeId, portKey) -> List.of("from-edge"));

            assertThat(resolved.get("files")).isEqualTo(List.of("from-edge"));
        }

        @Test
        void widgetValueWinsOverTheDeclaredDefault() {
            var source = new WorkflowGraph.GraphNode("a", "test.source",
                    Map.of("path", "chosen"), new WorkflowGraph.Position(0, 0));

            var resolved = InputResolver.resolve(SOURCE, source, List.of(), (nodeId, portKey) -> null);

            assertThat(resolved.get("path")).isEqualTo("chosen");
        }

        @Test
        void fallsBackToTheDeclaredDefault() {
            var resolved = InputResolver.resolve(
                    SOURCE, node("a", "test.source"), List.of(), (nodeId, portKey) -> null);

            assertThat(resolved.get("path")).isEqualTo("somewhere");
        }

        @Test
        @DisplayName("JSON numbers and strings are coerced to what the port declares")
        void coercesScalarsToTheDeclaredType() {
            var descriptor = NodeDescriptor.of("test.coerce", "Coerce")
                    .setting("count", "Count", Types.NUMBER, Widget.NumberField.of(0, 10), 0d)
                    .setting("on", "On", Types.BOOLEAN, new Widget.Toggle(), false)
                    .setting("name", "Name", Types.TEXT, Widget.TextField.of(""), "")
                    .out("out", "Out", Types.TEXT)
                    .build();

            var values = new LinkedHashMap<String, Object>();
            values.put("count", "7");
            values.put("on", "true");
            values.put("name", 42);
            var graphNode = new WorkflowGraph.GraphNode(
                    "n", "test.coerce", values, new WorkflowGraph.Position(0, 0));

            var resolved = InputResolver.resolve(descriptor, graphNode, List.of(), (id, port) -> null);

            assertThat(resolved.get("count")).isEqualTo(7d);
            assertThat(resolved.get("on")).isEqualTo(true);
            assertThat(resolved.get("name")).isEqualTo("42");
        }
    }
}
