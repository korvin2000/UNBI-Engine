package com.unbi.engine.llm.openapi;

import com.unbi.engine.llm.auth.ManagedCredentialStore;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public final class OpenApiSecurity {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private OpenApiSecurity() { }

    /**
     * Produces choices for a Security Requirement Object array.
     * A choice is deliberately an entire requirement alternative: the endpoint supports exactly
     * one credential binding and must not silently weaken an AND requirement.
     */
    public static ArrayNode alternatives(OpenApiDocument doc, JsonNode security, String pointer, String resourceBaseUrl) {
        var choices = JSON.arrayNode();
        if (security == null || security.isMissingNode()) {
            choices.add(anonymous(pointer));
            return choices;
        }
        if (security.isArray()) {
            if (security.isEmpty()) {
                choices.add(anonymous(pointer));
                return choices;
            }
            for (int index = 0; index < security.size(); index++) {
                choices.add(project(doc, security.get(index), OpenApiDocument.child(pointer, Integer.toString(index)), resourceBaseUrl));
            }
            return choices;
        }
        choices.add(unavailable(pointer, "Invalid security declaration", "unsupported", "Security must be an array of alternatives"));
        return choices;
    }

    private static ObjectNode project(OpenApiDocument doc, JsonNode requirement, String pointer, String resourceBaseUrl) {
        if (!requirement.isObject()) return unavailable(pointer, "Invalid security requirement", "unsupported",
                "A security alternative must be an object");
        if (requirement.isEmpty()) return anonymous(pointer);

        var entries = new ArrayList<Map.Entry<String, JsonNode>>();
        entries.addAll(requirement.properties());
        if (entries.size() != 1) return unavailable(pointer, "Multiple security schemes", "multiple",
                "Multiple security schemes in one alternative are not supported");
        var entry = entries.getFirst();
        var schemeName = entry.getKey();
        var scopes = requiredScopes(entry.getValue());
        if (scopes == null) return unavailable(pointer, schemeName, "unsupported",
                "Security requirement scopes must be an array of nonblank strings");

        var component = component(doc, schemeName);
        if (component == null) {
            var message = doc.minor() >= 2 && isUri(schemeName)
                    ? "Bundle external references before import"
                    : "Security scheme is not declared in components";
            return unavailable(pointer, schemeName, "unsupported", message);
        }
        try {
            var resolved = doc.resolve(component.node(), component.pointer());
            return projectScheme(doc, resolved.node(), resolved.pointer(), pointer, schemeName, scopes, resourceBaseUrl);
        } catch (OpenApiDocument.Problem problem) {
            return unavailable(pointer, schemeName, "unsupported", problem.getMessage());
        } catch (IllegalArgumentException problem) {
            return unavailable(pointer, schemeName, "unsupported", safe(problem));
        }
    }

    private static Component component(OpenApiDocument doc, String name) {
        var schemes = doc.root().path("components").path("securitySchemes");
        if (!schemes.isObject() || !schemes.has(name)) {
            if (doc.minor() >= 2 && name.startsWith("#")) {
                return new Component(JSON.objectNode().put("$ref", name), "");
            }
            return null;
        }
        return new Component(schemes.get(name), OpenApiDocument.child(
                OpenApiDocument.child(OpenApiDocument.child("", "components"), "securitySchemes"), name));
    }

    private static ObjectNode projectScheme(OpenApiDocument doc, JsonNode scheme, String schemePointer, String choicePointer,
                                            String name, List<String> scopes, String resourceBaseUrl) {
        if (!scheme.isObject()) return unavailable(choicePointer, name, "unsupported", "Security scheme must be an object");
        var deprecated = scheme.path("deprecated").asBoolean(false);
        var type = requiredText(scheme, "type");
        if (type == null) return unavailable(choicePointer, name, "unsupported", "Security scheme requires a type", deprecated);
        return switch (type) {
            case "http" -> http(scheme, choicePointer, name, scopes, resourceBaseUrl, deprecated);
            case "apiKey" -> apiKey(scheme, choicePointer, name, scopes, deprecated);
            case "oauth2" -> oauth(doc, scheme, schemePointer, choicePointer, name, scopes, resourceBaseUrl, deprecated);
            case "openIdConnect" -> oidc(doc, scheme, schemePointer, choicePointer, name, scopes, resourceBaseUrl, deprecated);
            case "mutualTLS" -> unavailable(choicePointer, name, "unsupported", "mutualTLS security is not supported", deprecated);
            default -> unavailable(choicePointer, name, "unsupported", "Unsupported security scheme type", deprecated);
        };
    }

    private static ObjectNode http(JsonNode scheme, String choicePointer, String name,
                                   List<String> scopes, String resourceBaseUrl, boolean deprecated) {
        if (!scopes.isEmpty()) return unavailable(choicePointer, name, "unsupported",
                "Only OAuth security requirements may declare scopes", deprecated);
        var method = requiredText(scheme, "scheme");
        if (method == null) return unavailable(choicePointer, name, "unsupported", "HTTP security requires a scheme", deprecated);
        return switch (method.toLowerCase(java.util.Locale.ROOT)) {
            case "bearer" -> available(choicePointer, name, "bearer", deprecated);
            case "basic" -> available(choicePointer, name, "basic", deprecated)
                    .set("credentialDraft", basicDraft(resourceBaseUrl));
            default -> unavailable(choicePointer, name, "unsupported", "Unsupported HTTP authentication scheme", deprecated);
        };
    }

    private static ObjectNode apiKey(JsonNode scheme, String choicePointer, String name, List<String> scopes, boolean deprecated) {
        if (!scopes.isEmpty()) return unavailable(choicePointer, name, "unsupported",
                "Only OAuth security requirements may declare scopes", deprecated);
        var location = requiredText(scheme, "in");
        var keyName = requiredText(scheme, "name");
        if (location == null || keyName == null) return unavailable(choicePointer, name, "api_key",
                "API key security requires both name and in", deprecated);
        if (!location.equals("header") && !location.equals("query") && !location.equals("cookie")) {
            return unavailable(choicePointer, name, "api_key", "API key location must be header, query, or cookie", deprecated);
        }
        var result = available(choicePointer, name, "api_key", deprecated);
        result.put("apiKeyLocation", location);
        result.put("apiKeyName", keyName);
        var draft = JSON.objectNode().put("type", "api_key");
        draft.putObject("configuration").put("location", location).put("name", keyName);
        draft.set("requiredFields", strings(List.of("value")));
        draft.put("complete", false);
        result.set("credentialDraft", draft);
        return result;
    }


    private static ObjectNode oauth(OpenApiDocument doc, JsonNode scheme, String schemePointer, String choicePointer,
                                    String name, List<String> scopes, String resourceBaseUrl, boolean deprecated) {
        var metadata = scheme.path("oauth2MetadataUrl");
        var flows = scheme.path("flows");
        if ((!flows.isObject() || flows.isEmpty()) && metadata.isString() && !metadata.asString().isBlank()) {
            return metadataOnly(doc, metadata.asString(), OpenApiDocument.child(schemePointer, "oauth2MetadataUrl"),
                    choicePointer, name, scopes, resourceBaseUrl, deprecated);
        }
        if (!flows.isObject()) return unavailable(choicePointer, name, "oauth2", "OAuth security requires flows", deprecated);

        var supported = new ArrayList<ObjectNode>();
        String unsupported = null;
        var flowEntries = new ArrayList<Map.Entry<String, JsonNode>>();
        flowEntries.addAll(flows.properties());
        for (var flow : flowEntries) {
            var result = flow(doc, flow.getKey(), flow.getValue(), OpenApiDocument.child(schemePointer, "flows"), scopes);
            if (result.available()) supported.add(result.value());
            else if (unsupported == null) unsupported = result.reason();
        }
        if (supported.isEmpty()) return unavailable(choicePointer, name, "oauth2",
                unsupported == null ? "OAuth security requires a supported flow" : unsupported, deprecated);

        var result = available(choicePointer, name, "oauth2", deprecated);
        var draft = oauthDraft(resourceBaseUrl, scopes, supported);
        if (metadata.isString() && !metadata.asString().isBlank())
            ((ObjectNode) draft.path("configuration")).put("metadataUrl",
                    endpoint(doc, metadata.asString(), OpenApiDocument.child(schemePointer, "oauth2MetadataUrl")));
        result.set("credentialDraft", draft);
        return result;
    }

    private static ObjectNode oidc(OpenApiDocument doc, JsonNode scheme, String schemePointer, String choicePointer,
                                   String name, List<String> scopes, String resourceBaseUrl, boolean deprecated) {
        var url = requiredText(scheme, "openIdConnectUrl");
        if (url == null) return unavailable(choicePointer, name, "oauth2", "OpenID Connect security requires openIdConnectUrl", deprecated);
        return metadataOnly(doc, url, OpenApiDocument.child(schemePointer, "openIdConnectUrl"),
                choicePointer, name, scopes, resourceBaseUrl, deprecated);
    }

    private static ObjectNode metadataOnly(OpenApiDocument doc, String url, String urlPointer, String choicePointer,
                                           String name, List<String> scopes, String resourceBaseUrl, boolean deprecated) {
        try {
            var metadata = endpoint(doc, url, urlPointer);
            var result = available(choicePointer, name, "oauth2", deprecated);
            var draft = draft("oauth2", resourceBaseUrl);
            var configuration = (ObjectNode) draft.path("configuration");
            configuration.put("metadataUrl", metadata);
            configuration.set("scopes", strings(scopes));
            draft.set("requiredFields", strings(List.of("issuer", "clientId", "clientAuthentication", "grantType")));
            draft.put("complete", false);
            result.set("credentialDraft", draft);
            return result;
        } catch (IllegalArgumentException invalid) {
            return unavailable(choicePointer, name, "oauth2", safe(invalid), deprecated);
        }
    }

    private static Flow flow(OpenApiDocument doc, String kind, JsonNode value, String flowsPointer, List<String> scopes) {
        var pointer = OpenApiDocument.child(flowsPointer, kind);
        if (!value.isObject()) return Flow.unsupported("OAuth flow must be an object");
        if (kind.equals("implicit") || kind.equals("password")) return Flow.unsupported("OAuth " + kind + " flow is not supported");
        var grant = switch (kind) {
            case "authorizationCode" -> "authorization_code";
            case "clientCredentials" -> "client_credentials";
            case "deviceAuthorization" -> "device_authorization";
            default -> null;
        };
        if (grant == null) return Flow.unsupported("Unsupported OAuth flow");
        try {
            var result = JSON.objectNode().put("id", pointer).put("grantType", grant);
            if (grant.equals("authorization_code")) result.put("authorizationUrl",
                    endpoint(doc, required(value, "authorizationUrl"), OpenApiDocument.child(pointer, "authorizationUrl")));
            if (grant.equals("device_authorization")) result.put("deviceAuthorizationUrl",
                    endpoint(doc, required(value, "deviceAuthorizationUrl"), OpenApiDocument.child(pointer, "deviceAuthorizationUrl")));
            result.put("tokenUrl", endpoint(doc, required(value, "tokenUrl"), OpenApiDocument.child(pointer, "tokenUrl")));
            var refresh = requiredText(value, "refreshUrl");
            if (refresh != null) result.put("refreshUrl", endpoint(doc, refresh, OpenApiDocument.child(pointer, "refreshUrl")));
            return Flow.available(result);
        } catch (IllegalArgumentException invalid) {
            return Flow.unsupported(safe(invalid));
        }
    }

    private static ObjectNode oauthDraft(String resourceBaseUrl, List<String> scopes, List<ObjectNode> flows) {
        var draft = draft("oauth2", resourceBaseUrl);
        var configuration = (ObjectNode) draft.path("configuration");
        configuration.set("scopes", strings(scopes));
        if (flows.size() == 1) {
            var flow = flows.getFirst();
            configuration.put("grantType", flow.path("grantType").asString());
            copy(flow, configuration, "authorizationUrl");
            copy(flow, configuration, "tokenUrl");
            copy(flow, configuration, "refreshUrl");
            copy(flow, configuration, "deviceAuthorizationUrl");
            draft.set("requiredFields", strings(List.of("clientId", "clientAuthentication")));
        } else {
            var choices = draft.putArray("flows");
            flows.forEach(choices::add);
            draft.set("requiredFields", strings(List.of("grantType", "clientId", "clientAuthentication")));
        }
        draft.put("complete", false);
        return draft;
    }


    private static ObjectNode basicDraft(String resourceBaseUrl) {
        var draft = draft("basic", resourceBaseUrl);
        draft.set("requiredFields", strings(List.of("username", "password")));
        draft.put("complete", false);
        return draft;
    }

    private static ObjectNode draft(String type, String resourceBaseUrl) {
        var draft = JSON.objectNode().put("type", type);
        draft.putObject("configuration").put("resourceBaseUrl", resourceBaseUrl == null ? "" : resourceBaseUrl);
        return draft;
    }

    private static String endpoint(OpenApiDocument doc, String value, String pointer) {
        URI resolved = doc.resolveUrl(value, pointer);
        return ManagedCredentialStore.oauthEndpoint(resolved.toString()).toString();
    }

    private static List<String> requiredScopes(JsonNode node) {
        if (!node.isArray()) return null;
        var scopes = new ArrayList<String>();
        for (var scope : node) {
            if (!scope.isString() || scope.asString().isBlank()) return null;
            scopes.add(scope.asString());
        }
        return scopes;
    }

    private static String required(JsonNode node, String field) {
        var text = requiredText(node, field);
        if (text == null) throw new IllegalArgumentException("OAuth flow requires " + field);
        return text;
    }

    private static String requiredText(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static ObjectNode anonymous(String pointer) { return available(pointer, "No authentication", "none", false); }

    private static ObjectNode available(String id, String label, String auth, boolean deprecated) {
        var result = JSON.objectNode().put("id", id).put("label", label).put("available", true).put("auth", auth);
        if (deprecated) result.put("deprecated", true);
        return result;
    }

    private static ObjectNode unavailable(String id, String label, String auth, String reason) {
        return unavailable(id, label, auth, reason, false);
    }

    private static ObjectNode unavailable(String id, String label, String auth, String reason, boolean deprecated) {
        var result = JSON.objectNode().put("id", id).put("label", label).put("available", false)
                .put("reason", reason).put("auth", auth);
        if (deprecated) result.put("deprecated", true);
        return result;
    }

    private static ArrayNode strings(List<String> values) {
        var result = JSON.arrayNode();
        values.forEach(result::add);
        return result;
    }

    private static void copy(ObjectNode source, ObjectNode destination, String field) {
        if (source.has(field)) destination.put(field, source.path(field).asString());
    }

    private static boolean isUri(String value) {
        try { return URI.create(value).isAbsolute(); }
        catch (IllegalArgumentException malformed) { return false; }
    }

    private static String safe(Exception exception) {
        var message = exception.getMessage();
        return message == null || message.isBlank() ? "Invalid security metadata" : message;
    }

    private record Component(JsonNode node, String pointer) { }
    private record Flow(ObjectNode value, String reason) {
        static Flow available(ObjectNode value) { return new Flow(value, null); }
        static Flow unsupported(String reason) { return new Flow(null, reason); }
        boolean available() { return value != null; }
    }
}
