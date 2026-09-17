package com.unbi.engine.workflows;

import java.io.IOException;
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
 * The workflow library over HTTP: list, open, save, delete, favourite, rename.
 *
 * <p>Save refuses a taken name with 409 unless the body says {@code overwrite}, so the editor can
 * ask "replace it?" with the engine's answer in hand rather than guessing from a listing that may
 * be a minute old.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final WorkflowStore store;

    public WorkflowController(WorkflowStore store) {
        this.store = store;
    }

    @GetMapping
    public ObjectNode list() {
        var root = NODES.objectNode();
        root.put("directory", store.root().toString());
        var workflows = root.putArray("workflows");
        store.list().forEach(entry -> workflows.add(describe(entry)));
        return root;
    }

    @GetMapping("/{id}")
    public ObjectNode open(@PathVariable String id) throws IOException {
        var entry = store.find(id).orElseThrow(() -> notFound(id));
        var node = describe(entry);
        node.set("document", store.read(entry.id()).orElseThrow(() -> notFound(id)));
        return node;
    }

    /** Body: {@code name}, {@code document}, and {@code overwrite} to replace a workflow of that name. */
    @PostMapping
    public ObjectNode save(@RequestBody JsonNode body) throws IOException {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected a workflow to save");
        }
        try {
            return describe(store.save(
                    body.path("name").asString(""), body.path("document"), body.path("overwrite").asBoolean(false)));
        } catch (WorkflowStore.AlreadyExists taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, taken.getMessage(), taken);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) throws IOException {
        return store.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/favorite")
    public ObjectNode favorite(@PathVariable String id, @RequestBody JsonNode body) throws IOException {
        var wanted = body != null && body.path("favorite").asBoolean(false);
        return describe(store.setFavorite(id, wanted).orElseThrow(() -> notFound(id)));
    }

    @PostMapping("/{id}/rename")
    public ObjectNode rename(@PathVariable String id, @RequestBody JsonNode body) throws IOException {
        try {
            return describe(store.rename(id, body == null ? "" : body.path("name").asString(""))
                    .orElseThrow(() -> notFound(id)));
        } catch (WorkflowStore.AlreadyExists taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, taken.getMessage(), taken);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No workflow called \"" + id + "\"");
    }

    private static ObjectNode describe(StoredWorkflow entry) {
        var node = NODES.objectNode();
        node.put("id", entry.id());
        node.put("name", entry.name());
        node.put("favorite", entry.favorite());
        node.put("updatedAt", entry.updatedAt().toString());
        node.put("size", entry.size());
        return node;
    }
}
