package com.unbi.engine.core.graph;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A workflow as submitted for execution: nodes, their widget values, and the edges between them.
 *
 * <p>Immutable and free of any editor concern. Node positions are carried because the same document
 * shape is what the editor saves and loads, and splitting "the graph" from "the drawing of the
 * graph" into two formats would buy nothing for a single-editor system.
 */
public record WorkflowGraph(List<GraphNode> nodes, List<GraphEdge> edges) {

    public WorkflowGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    public Optional<GraphNode> node(String id) {
        return nodes.stream().filter(node -> node.id().equals(id)).findFirst();
    }

    /** Every edge that terminates on the given node. */
    public List<GraphEdge> incoming(String nodeId) {
        return edges.stream().filter(edge -> edge.targetNode().equals(nodeId)).toList();
    }

    /** Every edge that leaves the given node. */
    public List<GraphEdge> outgoing(String nodeId) {
        return edges.stream().filter(edge -> edge.sourceNode().equals(nodeId)).toList();
    }

    /**
     * One node placed on the canvas.
     *
     * @param typeId  which {@code NodeDefinition} this is an instance of
     * @param values  widget values keyed by input key; an edge into the same key wins over these
     */
    public record GraphNode(String id, String typeId, Map<String, Object> values, Position position) {

        public GraphNode {
            values = Map.copyOf(values);
        }
    }

    public record GraphEdge(
            String id, String sourceNode, String sourcePort, String targetNode, String targetPort) {}

    public record Position(double x, double y) {}
}
