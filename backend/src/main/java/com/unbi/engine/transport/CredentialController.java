package com.unbi.engine.transport;

import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.auth.PropertiesFileCredentials;
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
 * Credentials the editor may name, add and remove — and never read.
 *
 * <p>Three verbs, deliberately asymmetric. {@code GET} lists names with the source that answers for
 * each, {@code POST} writes a key into {@code credentials.properties} on the engine, {@code DELETE}
 * removes one from that file, and nothing here or anywhere else returns a value. A key typed into
 * the profile dialog travels to the engine once and is then only ever a name in a dropdown, which is
 * the same property the environment variable path has and the one that makes a workflow, a preset
 * and a profile safe to share.
 *
 * <p>Only the file source is writable: a variable set in the engine's environment is the
 * deployment's decision, and the editor has no business changing it.
 */
@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final CredentialStore credentials;
    private final PropertiesFileCredentials file;

    public CredentialController(CredentialStore credentials, PropertiesFileCredentials file) {
        this.credentials = credentials;
        this.file = file;
    }

    @GetMapping
    public ObjectNode list() {
        var root = NODES.objectNode();
        var names = root.putArray("credentials");
        credentials.catalog().forEach((name, source) -> names.add(NODES.objectNode()
                .put("name", name)
                .put("source", source)
                // Only what the file holds can be removed from here; the rest is read-only.
                .put("removable", "file".equals(source))));
        root.put("file", file.location().toString());
        return root;
    }

    @PostMapping
    public ObjectNode add(@RequestBody JsonNode body) throws IOException {
        var name = body.path("name").asString("").trim();
        var value = body.path("value").asString("");
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,60}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A credential name is letters, digits, dots, dashes or underscores.");
        }
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The key is empty.");
        }
        file.store(name, value.trim());
        return NODES.objectNode().put("name", name).put("source", "file");
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> remove(@PathVariable String name) throws IOException {
        return file.remove(name) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
