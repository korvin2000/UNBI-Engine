package com.unbi.engine.transport.codec;

import com.unbi.engine.core.graph.GraphValidator;
import com.unbi.engine.core.run.EngineEvent;
import java.util.List;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes engine events as wire frames.
 *
 * <p>Every frame carries a {@code type}. The switch below is exhaustive over the sealed
 * {@link EngineEvent} hierarchy, so a new event kind cannot reach production without someone
 * deciding how the browser should hear about it.
 */
public final class EventCodec {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private EventCodec() {}

    public static ObjectNode write(EngineEvent event) {
        var node = NODES.objectNode();
        node.put("runId", event.runId());
        node.put("at", event.at().toString());

        switch (event) {
            case EngineEvent.RunStarted started -> {
                node.put("type", "run.started");
                var order = node.putArray("order");
                started.order().forEach(order::add);
            }
            case EngineEvent.NodeStateChanged changed -> {
                node.put("type", "node.state");
                node.put("nodeId", changed.nodeId());
                node.put("state", changed.state().name());
                node.put("message", changed.message());
                node.set("durationMillis", changed.durationMillis() == null
                        ? NODES.nullNode()
                        : NODES.numberNode(changed.durationMillis()));
            }
            case EngineEvent.NodeProgress progress -> {
                node.put("type", "node.progress");
                node.put("nodeId", progress.nodeId());
                node.put("fraction", progress.fraction());
                node.put("message", progress.message());
            }
            case EngineEvent.NodeLog logged -> {
                node.put("type", "node.log");
                node.put("nodeId", logged.nodeId());
                node.put("message", logged.message());
            }
            case EngineEvent.RunFinished finished -> {
                node.put("type", "run.finished");
                node.put("outcome", finished.outcome().name());
                node.put("message", finished.message());
                node.put("durationMillis", finished.durationMillis());
            }
            case EngineEvent.RunRejected rejected -> {
                node.put("type", "run.rejected");
                var problems = node.putArray("problems");
                rejected.problems().forEach(problems::add);
            }
        }
        return node;
    }

    /** Validation performed on request, without starting a run. */
    public static ObjectNode validation(String requestId, List<GraphValidator.Issue> issues) {
        var node = NODES.objectNode();
        node.put("type", "validation");
        node.put("requestId", requestId);
        node.put("valid", issues.isEmpty());
        var array = node.putArray("issues");
        issues.forEach(issue -> {
            var entry = NODES.objectNode();
            entry.put("nodeId", issue.nodeId());
            entry.put("edgeId", issue.edgeId());
            entry.put("portKey", issue.portKey());
            entry.put("message", issue.message());
            var cycle = entry.putArray("cycle");
            issue.cycle().forEach(cycle::add);
            array.add(entry);
        });
        return node;
    }

    public static ObjectNode simple(String type, String field, String value) {
        var node = NODES.objectNode();
        node.put("type", type);
        if (field != null) {
            node.put(field, value);
        }
        return node;
    }

    public static ObjectNode runAccepted(String requestId, String runId) {
        var node = NODES.objectNode();
        node.put("type", "run.accepted");
        node.put("requestId", requestId);
        node.put("runId", runId);
        return node;
    }

    public static ObjectNode error(String message) {
        return simple("error", "message", message);
    }
}
