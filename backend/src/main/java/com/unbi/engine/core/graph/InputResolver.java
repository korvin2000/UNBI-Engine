package com.unbi.engine.core.graph;

import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeInput;
import com.unbi.engine.core.type.PortType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Works out what each input of a node is actually worth, from three possible sources.
 *
 * <p>Precedence is fixed and deliberate: <b>an incoming edge beats the widget value, which beats the
 * declared default</b>. That mirrors what the editor shows — connecting an edge to an input visibly
 * disables its widget — so the running behaviour matches the drawing.
 *
 * <p>Widget values arrive from JSON, where 5 may be an Integer, a Double or "5". Coercing here, once,
 * against the declared port type is what keeps every node free of defensive parsing.
 */
public final class InputResolver {

    private InputResolver() {}

    /** Supplies the value produced earlier in the run by an upstream output port. */
    @FunctionalInterface
    public interface UpstreamValues {
        Object get(String nodeId, String portKey);
    }

    public static Map<String, Object> resolve(
            NodeDescriptor descriptor,
            WorkflowGraph.GraphNode node,
            List<WorkflowGraph.GraphEdge> incoming,
            UpstreamValues upstream) {

        var edgeByTargetPort = new LinkedHashMap<String, WorkflowGraph.GraphEdge>();
        for (var edge : incoming) {
            edgeByTargetPort.put(edge.targetPort(), edge);
        }

        var resolved = new LinkedHashMap<String, Object>();
        for (var input : descriptor.inputs()) {
            resolved.put(input.key(), valueFor(input, node, edgeByTargetPort.get(input.key()), upstream));
        }
        return Map.copyOf(nullSafe(resolved));
    }

    private static Object valueFor(
            NodeInput input,
            WorkflowGraph.GraphNode node,
            WorkflowGraph.GraphEdge edge,
            UpstreamValues upstream) {

        if (edge != null) {
            // Values crossing an edge were produced by a node, already correctly typed.
            return upstream.get(edge.sourceNode(), edge.sourcePort());
        }
        var provided = node.values().get(input.key());
        if (provided != null) {
            return coerce(provided, input.type());
        }
        return input.defaultValue() == null ? null : coerce(input.defaultValue(), input.type());
    }

    /**
     * Converts a JSON-shaped scalar to what the declared type implies.
     *
     * <p>Only scalars are coerced. Structured values only ever reach a node along an edge, where
     * they are already the producing node's own objects, and rewriting those would be both
     * pointless and lossy.
     */
    public static Object coerce(Object raw, PortType type) {
        if (raw == null || !(type instanceof PortType.Primitive primitive)) {
            return raw;
        }
        return switch (primitive.name()) {
            case "Number" -> raw instanceof Number number
                    ? number.doubleValue()
                    : Double.parseDouble(raw.toString().trim());
            case "Boolean" -> raw instanceof Boolean flag
                    ? flag
                    : Boolean.parseBoolean(raw.toString().trim());
            // Text, Directory and any pack-declared primitive all travel as strings.
            default -> raw instanceof String text ? text : raw.toString();
        };
    }

    /** {@code Map.copyOf} rejects null values, but "this input has no value" is a legal state. */
    private static Map<String, Object> nullSafe(Map<String, Object> values) {
        var cleaned = new LinkedHashMap<String, Object>();
        values.forEach((key, value) -> {
            if (value != null) {
                cleaned.put(key, value);
            }
        });
        return cleaned;
    }
}
