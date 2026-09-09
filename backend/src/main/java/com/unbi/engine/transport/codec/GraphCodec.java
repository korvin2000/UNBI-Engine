package com.unbi.engine.transport.codec;

import com.unbi.engine.core.graph.WorkflowGraph;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import tools.jackson.databind.JsonNode;

/**
 * Reads a workflow submitted by the editor.
 *
 * <p>Everything here is untrusted input, so every field is checked and every failure names what was
 * wrong with which element. A graph arriving malformed should produce a message a user can act on,
 * not a {@code NullPointerException} three layers down in the engine.
 */
public final class GraphCodec {

    private GraphCodec() {}

    public static WorkflowGraph read(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Expected a graph object");
        }
        var nodes = new ArrayList<WorkflowGraph.GraphNode>();
        var edges = new ArrayList<WorkflowGraph.GraphEdge>();

        var nodesNode = root.get("nodes");
        if (nodesNode != null && nodesNode.isArray()) {
            nodesNode.forEach(node -> nodes.add(readNode(node)));
        }
        var edgesNode = root.get("edges");
        if (edgesNode != null && edgesNode.isArray()) {
            edgesNode.forEach(edge -> edges.add(readEdge(edge)));
        }
        return new WorkflowGraph(nodes, edges);
    }

    private static WorkflowGraph.GraphNode readNode(JsonNode node) {
        var id = requireText(node, "id", "node");
        var typeId = requireText(node, "type", "node " + id);

        var values = new LinkedHashMap<String, Object>();
        var valuesNode = node.get("values");
        if (valuesNode != null && valuesNode.isObject()) {
            valuesNode.propertyNames().forEach(key -> {
                var value = JsonValues.from(valuesNode.get(key));
                if (value != null) {
                    values.put(key, value);
                }
            });
        }

        var positionNode = node.get("position");
        var position = positionNode == null || !positionNode.isObject()
                ? new WorkflowGraph.Position(0, 0)
                : new WorkflowGraph.Position(
                        positionNode.path("x").asDouble(0), positionNode.path("y").asDouble(0));

        return new WorkflowGraph.GraphNode(id, typeId, values, position);
    }

    private static WorkflowGraph.GraphEdge readEdge(JsonNode edge) {
        var id = requireText(edge, "id", "edge");
        return new WorkflowGraph.GraphEdge(
                id,
                requireText(edge, "sourceNode", "edge " + id),
                requireText(edge, "sourcePort", "edge " + id),
                requireText(edge, "targetNode", "edge " + id),
                requireText(edge, "targetPort", "edge " + id));
    }

    private static String requireText(JsonNode node, String field, String where) {
        var value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("Missing '%s' on %s".formatted(field, where));
        }
        return value.asString();
    }
}
