package com.unbi.engine.profiles;

import com.unbi.engine.core.node.NodeProbe;
import com.unbi.engine.json.JsonValues;
import com.unbi.engine.transport.codec.CatalogCodec;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
 * Profiles over HTTP: the schema as a form the editor can draw, the saved profiles, save, delete,
 * and a test of an unsaved draft.
 *
 * <p>{@code GET /api/profiles/{schema}} answers with both the fields and the profiles in one body,
 * because the editor never wants one without the other — the dialog draws the fields and lists the
 * profiles, and the node's dropdown lists the profiles under the schema's label.
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ProfileStore store;

    public ProfileController(ProfileStore store) {
        this.store = store;
    }

    @GetMapping("/{schema}")
    public ObjectNode list(@PathVariable String schema) {
        var declared = schemaOr404(schema);
        var root = NODES.objectNode();
        var shape = root.putObject("schema");
        shape.put("id", declared.id());
        shape.put("label", declared.label());
        shape.put("testable", declared.testable());
        var fields = shape.putArray("fields");
        declared.fields().forEach(field -> fields.add(CatalogCodec.inputNode(field)));
        var profiles = root.putArray("profiles");
        store.list(declared.id()).forEach(profile -> profiles.add(write(profile)));
        return root;
    }

    @PostMapping("/{schema}")
    public ObjectNode save(@PathVariable String schema, @RequestBody JsonNode body) throws IOException {
        var declared = schemaOr404(schema);
        return write(store.save(read(declared, body)));
    }

    @DeleteMapping("/{schema}/{id}")
    public ResponseEntity<Void> delete(@PathVariable String schema, @PathVariable String id) throws IOException {
        var declared = schemaOr404(schema);
        return store.delete(declared.id(), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Tries a draft out without saving it.
     *
     * <p>Values only — the same shape a node probe receives — so a test can reach a gateway to ask
     * whether it answers and can never run anything.
     */
    @PostMapping("/{schema}/test")
    public ObjectNode test(@PathVariable String schema, @RequestBody JsonNode body) {
        var declared = schemaOr404(schema);
        var values = declared.withDefaults(values(body.path("values")));
        NodeProbe.Result result;
        try {
            result = declared.test(values).orElseGet(() -> NodeProbe.Result.failed(
                    declared.label() + " profiles cannot be tested."));
        } catch (RuntimeException failure) {
            result = NodeProbe.Result.failed(
                    failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
        var node = NODES.objectNode();
        node.put("ok", result.ok());
        node.put("message", result.message());
        var details = node.putArray("details");
        result.details().forEach(details::add);
        return node;
    }

    private ProfileSchema schemaOr404(String id) {
        try {
            return store.schema(id);
        } catch (IllegalArgumentException unknown) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, unknown.getMessage(), unknown);
        }
    }

    private static Profile read(ProfileSchema schema, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected a profile object");
        }
        try {
            return new Profile(
                    body.path("id").asString(""),
                    schema.id(),
                    body.path("name").asString(""),
                    body.path("description").asString(""),
                    values(body.path("values")),
                    Instant.now());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    private static Map<String, Object> values(JsonNode node) {
        var values = new LinkedHashMap<String, Object>();
        if (node != null && node.isObject()) {
            node.propertyNames().forEach(name -> values.put(name, JsonValues.from(node.get(name))));
        }
        return values;
    }

    private static ObjectNode write(Profile profile) {
        var node = NODES.objectNode();
        node.put("id", profile.id());
        node.put("schema", profile.schema());
        node.put("name", profile.name());
        node.put("description", profile.description());
        node.put("updatedAt", profile.updatedAt().toString());
        var values = node.putObject("values");
        profile.values().forEach((key, value) -> values.set(key, JsonValues.of(value)));
        return node;
    }
}
