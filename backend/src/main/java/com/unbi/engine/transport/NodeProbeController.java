package com.unbi.engine.transport;

import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeProbe;
import com.unbi.engine.json.JsonValues;
import com.unbi.engine.registry.NodeRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Running one node's declared action against the settings the editor currently shows.
 *
 * <p>The counterpart to the buttons a descriptor advertises. The editor sends this node's widget
 * values plus those of everything wired upstream of it, and nothing else — no graph, no node ids, no
 * instruction to execute anything. That shape is the safety property: pressing a test button can
 * reach a gateway to ask what it serves, and can never run the Replace In Files node three hops back.
 *
 * <p>Values arrive as the editor holds them, which for an untouched field is nothing at all. They
 * are filled in from the descriptor here, once, so that every probe reads a complete configuration
 * and no node has to re-implement "what would this have been if the user had not touched it".
 */
@RestController
@RequestMapping("/api/nodes")
public class NodeProbeController {

    /** A graph deep enough to need more than this is not one a probe should be walking. */
    private static final int MAX_DEPTH = 8;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final NodeRegistry registry;

    public NodeProbeController(NodeRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/{type}/probe/{action}")
    public ObjectNode probe(
            @PathVariable String type, @PathVariable String action, @RequestBody JsonNode body) {

        var definition = registry.definition(type)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No node type named " + type));
        if (!(definition instanceof NodeProbe probe)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, type + " has nothing to check");
        }
        if (definition.descriptor().actions().stream().noneMatch(declared -> declared.key().equals(action))) {
            // Declared actions only: the descriptor is the contract, and honouring a key it never
            // advertised would make the button list a suggestion rather than the API.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, type + " declares no action called " + action);
        }

        var request = new NodeProbe.Request(
                type,
                withDefaults(definition.descriptor(), body.path("values")),
                sources(body.path("sources"), 0));

        try {
            return write(probe.probe(action, request));
        } catch (Exception failure) {
            // A probe that threw is a probe that answered "no". The user pressed a button to find
            // out whether something works; an HTTP 500 tells them only that this engine does not
            // know either.
            return write(NodeProbe.Result.failed(
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
        }
    }

    private Map<String, NodeProbe.Source> sources(JsonNode node, int depth) {
        var found = new LinkedHashMap<String, NodeProbe.Source>();
        if (depth >= MAX_DEPTH || node == null || !node.isObject()) {
            return found;
        }
        node.propertyNames().forEach(socket -> {
            var entry = node.get(socket);
            if (entry == null || !entry.isObject()) {
                return;
            }
            var type = entry.path("nodeType").asString("");
            var descriptor = registry.find(type).orElse(null);
            if (descriptor == null) {
                return;
            }
            found.put(socket, new NodeProbe.Source(
                    type,
                    withDefaults(descriptor, entry.path("values")),
                    sources(entry.path("sources"), depth + 1)));
        });
        return found;
    }

    /**
     * Every input the node declares, holding either what the editor sent or the descriptor's default.
     *
     * <p>Unknown keys are dropped rather than passed through. A probe reads its own node's settings;
     * anything else in the payload is either stale or someone else's, and neither belongs in the
     * values a gateway is about to be contacted with.
     */
    private static Map<String, Object> withDefaults(NodeDescriptor descriptor, JsonNode values) {
        var resolved = new LinkedHashMap<String, Object>();
        for (var input : descriptor.inputs()) {
            var sent = values != null && values.isObject() ? values.get(input.key()) : null;
            resolved.put(
                    input.key(),
                    sent == null || sent.isNull() ? input.defaultValue() : JsonValues.from(sent));
        }
        return resolved;
    }

    private static ObjectNode write(NodeProbe.Result result) {
        var node = NODES.objectNode();
        node.put("ok", result.ok());
        node.put("message", result.message());
        var details = node.putArray("details");
        result.details().forEach(details::add);

        var options = node.putObject("options");
        result.options().forEach((key, choices) -> {
            var array = options.putArray(key);
            choices.forEach(choice -> array.add(
                    NODES.objectNode().put("value", choice.value()).put("label", choice.label())));
        });

        var values = node.putObject("values");
        result.values().forEach((key, value) -> values.set(key, JsonValues.of(value)));
        return node;
    }
}
