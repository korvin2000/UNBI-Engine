package com.unbi.engine.transport;

import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.auth.ManagedCredentialStore;
import com.unbi.engine.llm.auth.OAuthCredentials;
import com.unbi.engine.llm.auth.OAuthLoginSessions;
import com.unbi.engine.llm.auth.PropertiesFileCredentials;
import java.io.IOException;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Engine-private secrets have write-only management APIs and public metadata built separately. */
@RestController
@RequestMapping("/api/credentials")
public class CredentialController {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final CredentialStore credentials;
    private final PropertiesFileCredentials file;
    private final ManagedCredentialStore managed;
    private final OAuthCredentials oauth;
    private final com.unbi.engine.llm.auth.CodexCredentials codex;
    private final OAuthLoginSessions logins;

    public CredentialController(CredentialStore credentials, PropertiesFileCredentials file,
            ManagedCredentialStore managed, OAuthCredentials oauth, OAuthLoginSessions logins,
            com.unbi.engine.llm.auth.CodexCredentials codex) {
        this.credentials = credentials;
        this.file = file;
        this.managed = managed;
        this.oauth = oauth;
        this.logins = logins;
        this.codex = codex;
    }

    @GetMapping
    public ResponseEntity<ObjectNode> list() {
        var root = NODES.objectNode();
        var names = root.putArray("credentials");
        var catalog = credentials.catalog();
        var owned = managed.names();
        owned.forEach(name -> catalog.putIfAbsent(name, "managed"));
        catalog.forEach((name, source) -> {
            var result = NODES.objectNode().put("name", name).put("source", source)
                    .put("removable", "file".equals(source));
            if (owned.contains(name)) {
                result.put("removable", true);
                try {
                    var entry = managed.find(name).orElseThrow();
                    if (entry.type() == ManagedCredentialStore.Type.CODEX) {
                        result.setAll(codex.status(name));
                    } else result.setAll(oauth.status(name));
                } catch (RuntimeException invalid) {
                    result.put("type", "unknown").put("status", "error").put("renewable", false).putNull("expiresAt");
                }
            } else if (source.equals("codex")) result.setAll(codex.status(name));
            else result.put("type", "api_key").put("status", "ready").put("renewable", false).putNull("expiresAt");
            names.add(result);
        });
        root.put("file", file.location().toString());
        var callback = logins.callbackUri("CREDENTIAL_NAME");
        root.put("oauthCallbackUriTemplate", callback.substring(0, callback.length() - "CREDENTIAL_NAME".length()) + "{name}");
        return noStore(root);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> add(@RequestBody JsonNode body) throws IOException {
        var name = body.path("name").asString("").trim();
        ManagedCredentialStore.validateName(name);
        var typeName = body.path("type").asString("api_key");
        var source = credentials.catalog().get(name);
        if (typeName.equals("api_key")) {
            if (managed.names().contains(name) || (credentials.contains(name) && !file.names().contains(name)))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A different credential source owns that name");
            var value = body.path("value").asString("");
            if (value.isBlank()) throw new IllegalArgumentException("The key is empty");
            file.store(name, value.trim());
        } else {
            if (credentials.contains(name) || managed.names().contains(name))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A credential already owns that name");
            var type = type(typeName);
            ObjectNode config;
            if (body.path("configuration") instanceof ObjectNode configured) config = configured.deepCopy();
            else if (type == ManagedCredentialStore.Type.BASIC) {
                config = NODES.objectNode();
                for (var field : ManagedCredentialStore.configurationFields(type))
                    if (body.has(field)) config.set(field, body.get(field));
            } else throw new IllegalArgumentException("A connection configuration is required");
            managed.create(name, type, config);
        }
        return noStore(NODES.objectNode().put("name", name).put("source", typeName.equals("api_key") ? "file" : "managed"));
    }

    @GetMapping("/{name}/configuration")
    public ResponseEntity<ObjectNode> configuration(@PathVariable String name) {
        var entry = requireManaged(name);
        var config = entry.configuration();
        var visible = NODES.objectNode();
        for (var field : ManagedCredentialStore.configurationFields(entry.type())) {
            if (!field.equals("password") && !field.equals("clientSecret") && config.has(field))
                visible.set(field, config.get(field));
        }
        var result = NODES.objectNode().put("name", name).put("type", entry.type().name().toLowerCase(Locale.ROOT));
        result.set("configuration", visible);
        result.put("hasPassword", !config.path("password").asString("").isBlank());
        result.put("hasClientSecret", !config.path("clientSecret").asString("").isBlank());
        if (entry.type() == ManagedCredentialStore.Type.OAUTH2) result.put("callbackUri", logins.callbackUri(name));
        return noStore(result);
    }

    @PostMapping(value = "/{name}/configuration", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> configure(@PathVariable String name, @RequestBody JsonNode body) {
        var entry = requireManaged(name);
        if (!(body.path("configuration") instanceof ObjectNode incoming))
            throw new IllegalArgumentException("A connection configuration is required");
        var config = incoming.deepCopy();
        var previous = entry.configuration();
        for (var secret : new String[] {"password", "clientSecret"})
            if (!config.has(secret) && previous.has(secret)) config.set(secret, previous.get(secret));
        logins.cancelAll(name);
        managed.configure(name, config);
        return configuration(name);
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> remove(@PathVariable String name) throws IOException {
        ManagedCredentialStore.validateName(name);
        if (managed.names().contains(name)) {
            logins.cancelAll(name);
            managed.remove(name);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        var owner = credentials.catalog().get(name);
        if (owner == null) return ResponseEntity.notFound().build();
        if (!"file".equals(owner))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Externally managed credentials are read-only");
        return file.remove(name) ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                : ResponseEntity.notFound().build();
    }

    private ManagedCredentialStore.Entry requireManaged(String name) {
        ManagedCredentialStore.validateName(name);
        return managed.find(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No managed credential with that name"));
    }

    private static ManagedCredentialStore.Type type(String value) {
        return switch (value) {
            case "basic" -> ManagedCredentialStore.Type.BASIC;
            case "oauth2" -> ManagedCredentialStore.Type.OAUTH2;
            case "codex" -> ManagedCredentialStore.Type.CODEX;
            default -> throw new IllegalArgumentException("Unknown credential type");
        };
    }

    private static ResponseEntity<ObjectNode> noStore(ObjectNode result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
