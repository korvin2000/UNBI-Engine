package com.unbi.engine.core.graph;

import com.unbi.engine.core.node.NodeCatalog;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.type.TypeSystem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Decides whether a graph is fit to run, and says why when it is not.
 *
 * <p>The editor runs the equivalent checks in the browser for immediate feedback, but this is the
 * authority: the browser copy is an affordance, and a graph can arrive here from a saved file, a
 * script, or a stale tab. Validating only in the UI would mean trusting the client.
 */
public final class GraphValidator {

    private final NodeCatalog catalog;
    private final TypeSystem types;

    public GraphValidator(NodeCatalog catalog, TypeSystem types) {
        this.catalog = catalog;
        this.types = types;
    }

    public ValidationReport validate(WorkflowGraph graph) {
        var issues = new ArrayList<Issue>();
        checkNodeTypesExist(graph, issues);
        checkEdgeEndpoints(graph, issues);
        checkPortCompatibility(graph, issues);
        checkSingleWriterPerInput(graph, issues);
        checkRequiredInputs(graph, issues);
        checkAcyclic(graph, issues);
        return new ValidationReport(List.copyOf(issues));
    }

    private void checkNodeTypesExist(WorkflowGraph graph, List<Issue> issues) {
        for (var node : graph.nodes()) {
            if (catalog.find(node.typeId()).isEmpty()) {
                issues.add(Issue.node(node.id(), "Unknown node type: " + node.typeId()));
            }
        }
    }

    private void checkEdgeEndpoints(WorkflowGraph graph, List<Issue> issues) {
        for (var edge : graph.edges()) {
            var source = descriptorFor(graph, edge.sourceNode());
            var target = descriptorFor(graph, edge.targetNode());
            if (source == null || target == null) {
                issues.add(Issue.edge(edge.id(), "Edge refers to a node that is not in the graph"));
                continue;
            }
            if (source.outputs().stream().noneMatch(out -> out.key().equals(edge.sourcePort()))) {
                issues.add(Issue.edge(edge.id(),
                        "%s has no output named %s".formatted(source.label(), edge.sourcePort())));
            }
            if (target.inputs().stream().noneMatch(in -> in.key().equals(edge.targetPort()))) {
                issues.add(Issue.edge(edge.id(),
                        "%s has no input named %s".formatted(target.label(), edge.targetPort())));
            }
        }
    }

    private void checkPortCompatibility(WorkflowGraph graph, List<Issue> issues) {
        for (var edge : graph.edges()) {
            var source = descriptorFor(graph, edge.sourceNode());
            var target = descriptorFor(graph, edge.targetNode());
            if (source == null || target == null) {
                continue;
            }
            var output = source.outputs().stream()
                    .filter(candidate -> candidate.key().equals(edge.sourcePort()))
                    .findFirst();
            var input = target.inputs().stream()
                    .filter(candidate -> candidate.key().equals(edge.targetPort()))
                    .findFirst();
            if (output.isEmpty() || input.isEmpty()) {
                continue; // already reported by checkEdgeEndpoints
            }
            if (!input.get().connectable()) {
                issues.add(Issue.edge(edge.id(),
                        "%s is a setting on %s and cannot be connected"
                                .formatted(input.get().label(), target.label())));
                continue;
            }
            if (!types.assignable(output.get().type(), input.get().type())) {
                issues.add(Issue.edge(edge.id(), "%s to %s: %s".formatted(
                        output.get().label(),
                        input.get().label(),
                        types.explainRejection(output.get().type(), input.get().type()))));
            }
        }
    }

    /**
     * An input takes one edge. Two writers would make the resolved value depend on iteration order,
     * which is exactly the kind of silent non-determinism this system should not have.
     */
    private void checkSingleWriterPerInput(WorkflowGraph graph, List<Issue> issues) {
        // A record key rather than a concatenated string: a node id containing whatever
        // separator was chosen would collide with a different node, and the bug would surface as
        // a spurious "already connected" on an unrelated edge.
        record Slot(String nodeId, String portKey) {}

        var seen = new HashSet<Slot>();
        for (var edge : graph.edges()) {
            if (!seen.add(new Slot(edge.targetNode(), edge.targetPort()))) {
                issues.add(Issue.edge(edge.id(), "This input already has an incoming connection"));
            }
        }
    }

    private void checkRequiredInputs(WorkflowGraph graph, List<Issue> issues) {
        for (var node : graph.nodes()) {
            var descriptor = catalog.find(node.typeId()).orElse(null);
            if (descriptor == null) {
                continue;
            }
            var connected = graph.incoming(node.id()).stream()
                    .map(WorkflowGraph.GraphEdge::targetPort)
                    .collect(java.util.stream.Collectors.toSet());
            for (var input : descriptor.inputs()) {
                if (!input.required()) {
                    continue;
                }
                var hasValue = connected.contains(input.key())
                        || node.values().get(input.key()) != null
                        || input.defaultValue() != null;
                if (!hasValue) {
                    issues.add(Issue.port(node.id(), input.key(),
                            "%s needs a value for %s".formatted(descriptor.label(), input.label())));
                }
            }
        }
    }

    private void checkAcyclic(WorkflowGraph graph, List<Issue> issues) {
        var result = ExecutionPlan.order(graph);
        if (!result.isOrdered()) {
            issues.add(Issue.cycle(result.cycle(),
                    "These nodes form a loop: " + String.join(" → ", result.cycle())));
        }
    }

    private NodeDescriptor descriptorFor(WorkflowGraph graph, String nodeId) {
        return graph.node(nodeId)
                .flatMap(node -> catalog.find(node.typeId()))
                .orElse(null);
    }

    /** Where a problem is, so the editor can point at it. */
    public record Issue(String nodeId, String edgeId, String portKey, List<String> cycle, String message) {

        public Issue {
            cycle = List.copyOf(cycle);
        }

        static Issue node(String nodeId, String message) {
            return new Issue(nodeId, null, null, List.of(), message);
        }

        static Issue edge(String edgeId, String message) {
            return new Issue(null, edgeId, null, List.of(), message);
        }

        static Issue port(String nodeId, String portKey, String message) {
            return new Issue(nodeId, null, portKey, List.of(), message);
        }

        static Issue cycle(List<String> cycle, String message) {
            return new Issue(null, null, null, cycle, message);
        }
    }

    public record ValidationReport(List<Issue> issues) {

        public ValidationReport {
            issues = List.copyOf(issues);
        }

        public boolean isRunnable() {
            return issues.isEmpty();
        }

        public String summary() {
            return issues.stream().map(Issue::message).reduce((a, b) -> a + "; " + b).orElse("Graph is valid");
        }
    }
}
