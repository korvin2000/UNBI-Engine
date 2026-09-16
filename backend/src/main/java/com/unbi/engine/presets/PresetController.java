package com.unbi.engine.presets;

import com.unbi.engine.core.node.NodeCatalog;
import com.unbi.engine.json.JsonValues;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Presets over HTTP: discovery by type, group and name, plus save and delete.
 *
 * <p>Three query parameters rather than one search box because the editor asks three different
 * questions with them — "what can I drop onto this canvas" is a filter by node type, "what is in my
 * Extraction set" is a filter by group, and "where did I put that prompt" is free text.
 *
 * <p>A saved preset is checked against the node catalog before it is written. A preset for a node
 * type that does not exist is a file nobody can ever use, and the moment to say so is now rather
 * than when someone tries to drag it onto a canvas.
 */
@RestController
@RequestMapping("/api/presets")
public class PresetController {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final PresetStore store;
    private final NodeCatalog catalog;

    public PresetController(PresetStore store, NodeCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    @GetMapping
    public ObjectNode list(
            @RequestParam(name = "type", required = false) String nodeType,
            @RequestParam(name = "group", required = false) String group,
            @RequestParam(name = "q", required = false) String query) {

        var root = NODES.objectNode();
        var presets = root.putArray("presets");
        store.query(nodeType, group, query).forEach(preset -> presets.add(write(preset)));
        var groups = root.putArray("groups");
        store.groups().forEach(groups::add);
        return root;
    }

    @PostMapping
    public ObjectNode save(@RequestBody JsonNode body) throws IOException {
        var preset = read(body);
        if (catalog.find(preset.nodeType()).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "No node type named " + preset.nodeType());
        }
        return write(store.save(preset));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        return store.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private Preset read(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected a preset object");
        }
        var values = new LinkedHashMap<String, Object>();
        var valuesNode = body.path("values");
        if (valuesNode.isObject()) {
            valuesNode.propertyNames().forEach(name -> values.put(name, JsonValues.from(valuesNode.get(name))));
        }
        try {
            return new Preset(
                    body.path("id").asString(""),
                    body.path("name").asString(""),
                    body.path("group").asString(""),
                    body.path("nodeType").asString(""),
                    body.path("description").asString(""),
                    values,
                    Instant.now());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    private ObjectNode write(Preset preset) {
        var node = NODES.objectNode();
        node.put("id", preset.id());
        node.put("name", preset.name());
        node.put("group", preset.group());
        node.put("nodeType", preset.nodeType());
        node.put("description", preset.description());
        node.put("updatedAt", preset.updatedAt().toString());
        node.put("label", catalog.find(preset.nodeType())
                .map(com.unbi.engine.core.node.NodeDescriptor::label)
                .orElse(preset.nodeType()));
        var values = node.putObject("values");
        preset.values().forEach((key, value) -> values.set(key, JsonValues.of(value)));
        return node;
    }


}
