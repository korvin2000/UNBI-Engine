package com.unbi.engine.llm.auth;

import com.unbi.engine.llm.provider.HttpTransport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Login actions are explicit; workflows only resolve or renew previously configured credentials. */
@RestController
@RequestMapping("/api/credentials")
public class CredentialSessionsController {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private final ManagedCredentialStore managed;
    private final OAuthLoginSessions logins;
    private final HttpTransport transport;
    private final CodexCredentials codex;

    public CredentialSessionsController(ManagedCredentialStore managed, OAuthLoginSessions logins,
            HttpTransport transport, CodexCredentials codex) {
        this.managed = managed;
        this.logins = logins;
        this.transport = transport;
        this.codex = codex;
    }

    @PostMapping(value = "/{name}/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> login(@PathVariable String name, @RequestBody(required = false) JsonNode body,
            HttpServletRequest request, HttpServletResponse response) {
        if (managed.find(name).isEmpty() && body != null && body.path("configuration") instanceof ObjectNode draft)
            return noStore(logins.takeover(name, draft, body.path("mode").asString("browser"), request, response));
        var entry = require(name);
        if (entry.type() == ManagedCredentialStore.Type.BASIC)
            return noStore(NODES.objectNode().put("status", "ready").put("loginId", ""));
        return noStore(logins.start(name, body == null ? "" : body.path("mode").asString(""), request, response));
    }

    @GetMapping("/{name}/login/{loginId}")
    public ResponseEntity<ObjectNode> status(@PathVariable String name, @PathVariable String loginId, HttpServletRequest request) {
        return noStore(logins.status(name, loginId, request));
    }

    @PostMapping(value = "/{name}/login/{loginId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> cancel(@PathVariable String name, @PathVariable String loginId, HttpServletRequest request) {
        logins.cancel(name, loginId, request);
        return noStore(NODES.objectNode().put("status", "reauth_required"));
    }

    @PostMapping(value = "/{name}/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> logout(@PathVariable String name) {
        var entry = require(name);
        logins.cancelAll(name);
        if (entry.type() == ManagedCredentialStore.Type.CODEX) codex.logout(name);
        else managed.logout(name);
        return noStore(NODES.objectNode().put("status", "reauth_required"));
    }

    @GetMapping(value = "/oauth/callback/{name}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@PathVariable String name,
            @RequestParam(required = false) String state, @RequestParam(required = false) String code,
            @RequestParam(required = false) String error, HttpServletRequest request, HttpServletResponse response) {
        try {
            logins.callback(name, state, code, error, request, response);
            return page(HttpStatus.OK, "Authentication received. You can close this window and check the connection in UNBI-Engine.");
        } catch (RuntimeException invalid) {
            return page(HttpStatus.BAD_REQUEST, "This authentication callback is invalid or expired. Start a new connection in UNBI-Engine.");
        }
    }

    /** Draft discovery is allowed before creating a definition; it never sends client credentials. */
    @PostMapping(value = "/{name}/discover", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> discover(@PathVariable String name, @RequestBody(required = false) JsonNode body) {
        ManagedCredentialStore.validateName(name);
        var config = body != null && body.path("configuration") instanceof ObjectNode draft
                ? draft : require(name).configuration();
        var expectedIssuer = config.path("issuer").asString("");
        if (expectedIssuer.isBlank()) throw new IllegalArgumentException("Enter the expected OAuth issuer before discovery");
        var issuer = ManagedCredentialStore.oauthEndpoint(expectedIssuer);
        if (issuer.getRawQuery() != null) throw new IllegalArgumentException("An OAuth issuer must not have a query");
        var metadataUrl = config.path("metadataUrl").asString("");
        if (metadataUrl.isBlank()) {
            metadataUrl = issuer.getScheme() + "://" + issuer.getRawAuthority()
                    + "/.well-known/oauth-authorization-server" + (issuer.getRawPath().equals("/") ? "" : issuer.getRawPath());
        }
        var metadataUri = ManagedCredentialStore.oauthEndpoint(metadataUrl);
        var metadata = transport.get(RequestAuthorization.unauthenticated(metadataUri.toString()), Duration.ofSeconds(20),
                () -> Thread.currentThread().isInterrupted());
        if (!expectedIssuer.equals(metadata.path("issuer").asString("")))
            throw new IllegalArgumentException("OAuth metadata issuer does not match the expected issuer");
        var preview = NODES.objectNode();
        for (var field : ManagedCredentialStore.configurationFields(ManagedCredentialStore.Type.OAUTH2))
            if (!field.equals("clientSecret") && config.has(field)) preview.set(field, config.get(field));
        preview.put("issuer", expectedIssuer).put("metadataUrl", metadataUrl);
        for (var endpoint : Map.of("authorization_endpoint", "authorizationUrl", "token_endpoint", "tokenUrl",
                "device_authorization_endpoint", "deviceAuthorizationUrl").entrySet()) {
            var url = metadata.path(endpoint.getKey()).asString("");
            if (!url.isEmpty()) preview.put(endpoint.getValue(), ManagedCredentialStore.oauthEndpoint(url).toString());
        }
        if (preview.path("tokenUrl").asString("").isBlank())
            throw new IllegalArgumentException("OAuth metadata does not advertise a token endpoint");
        var result = NODES.objectNode();
        result.set("configuration", preview);
        result.set("tokenAuthenticationMethodsSupported", metadata.path("token_endpoint_auth_methods_supported").isArray()
                ? metadata.path("token_endpoint_auth_methods_supported") : NODES.arrayNode());
        result.set("scopesSupported", metadata.path("scopes_supported").isArray() ? metadata.path("scopes_supported") : NODES.arrayNode());
        return noStore(result);
    }

    private ManagedCredentialStore.Entry require(String name) {
        ManagedCredentialStore.validateName(name);
        return managed.find(name).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No managed credential with that name"));
    }

    private static ResponseEntity<ObjectNode> noStore(ObjectNode node) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(node);
    }

    private static ResponseEntity<String> page(HttpStatus status, String text) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).contentType(MediaType.TEXT_HTML)
                .header("Content-Security-Policy", "default-src 'none'").header("X-Content-Type-Options", "nosniff")
                .body("<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><title>UNBI-Engine authentication</title><p>" + text + "</p></html>");
    }
}
