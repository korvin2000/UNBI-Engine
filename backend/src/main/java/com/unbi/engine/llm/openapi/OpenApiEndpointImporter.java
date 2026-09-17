package com.unbi.engine.llm.openapi;

import com.unbi.engine.core.node.ValueContext;
import com.unbi.engine.json.JsonValues;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.nodes.llm.EndpointProfiles;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Projects only server, protocol and authentication metadata into an unsaved endpoint draft. */
@Component
public final class OpenApiEndpointImporter {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Pattern VARIABLE = Pattern.compile("\\{([^{}]+)}");

    public record Request(String document, String documentUri, String operation, String server,
            Map<String, String> variables, String securityAlternative, String apiFormat) {}

    public ObjectNode preview(Request request) {
        var result = JSON.objectNode();
        result.putArray("servers");
        result.putArray("operations");
        result.putArray("securityAlternatives");
        result.putObject("values");
        result.putNull("credentialDraft");
        result.putArray("issues");
        result.put("complete", false);
        try {
            if (request == null) throw new OpenApiDocument.Problem("", "An OpenAPI document is required");
            var document = OpenApiDocument.parse(request.document(), request.documentUri());
            var operations = operations(document);
            var operationNodes = result.withArray("operations");
            for (var operation : operations) {
                var node = JSON.objectNode().put("id", operation.id()).put("label", "POST " + operation.path())
                        .put("path", operation.path()).put("apiFormat", operation.format().wireName())
                        .put("available", operation.problem().isEmpty());
                if (!operation.problem().isEmpty()) node.put("reason", operation.problem());
                operationNodes.add(node);
            }
            Operation selectedOperation = null;
            ApiFormat format = null;
            if (!operations.isEmpty()) {
                if (blank(request.operation()) && operations.size() == 1) selectedOperation = operations.getFirst();
                else for (var candidate : operations) if (candidate.id().equals(request.operation())) selectedOperation = candidate;
                if (selectedOperation == null) {
                    issue(result, "/paths", "error", blank(request.operation()) ? "Select a generation operation" : "The selected operation is not in this document");
                    return result;
                }
                if (!selectedOperation.problem().isEmpty()) {
                    issue(result, selectedOperation.id(), "error", selectedOperation.problem());
                    return result;
                }
                format = selectedOperation.format();
                if (!blank(request.apiFormat()) && ApiFormat.of(request.apiFormat()) != format) {
                    issue(result, selectedOperation.id(), "error", "The selected protocol contradicts the selected operation");
                    return result;
                }
            } else {
                if (!blank(request.operation())) {
                    issue(result, "/paths", "error", "The selected operation is not in this document");
                    return result;
                }
                if (!blank(request.apiFormat())) format = ApiFormat.of(request.apiFormat());
                issue(result, "/paths", format == null ? "error" : "warning",
                        "No recognized LLM generation operation: choose a protocol explicitly. Only server and authentication metadata can be imported");
            }

            var servers = servers(document, selectedOperation);
            var serverNodes = result.withArray("servers");
            for (var candidate : servers) serverNodes.add(serverPreview(candidate, request.variables()));
            Server selectedServer = null;
            if (request.server() == null && servers.size() == 1) selectedServer = servers.getFirst();
            else for (var candidate : servers) if (candidate.pointer().equals(request.server())) selectedServer = candidate;
            String baseUrl = "";
            if (selectedServer == null) {
                issue(result, "/servers", "error", request.server() == null ? "Select an endpoint server" : "The selected server is not in this document");
            } else {
                try {
                    var url = resolveServer(document, selectedServer, request.variables());
                    baseUrl = endpointBase(url, selectedOperation);
                    result.put("selectedServer", selectedServer.pointer());
                } catch (OpenApiDocument.Problem invalid) { issue(result, invalid.pointer(), "error", invalid.getMessage()); }
            }
            if (selectedOperation != null) result.put("selectedOperation", selectedOperation.id());

            var security = selectedOperation != null && selectedOperation.operation().has("security")
                    ? selectedOperation.operation().path("security") : document.root().path("security");
            var securityPointer = selectedOperation != null && selectedOperation.operation().has("security")
                    ? OpenApiDocument.child(selectedOperation.operationPointer(), "security")
                    : document.root().has("security") ? "/security" : "";
            var alternatives = OpenApiSecurity.alternatives(document, security, securityPointer, baseUrl);
            result.set("securityAlternatives", alternatives);
            JsonNode selectedSecurity = null;
            if (request.securityAlternative() == null && alternatives.size() == 1) selectedSecurity = alternatives.get(0);
            else for (var candidate : alternatives) if (candidate.path("id").asString().equals(request.securityAlternative())) selectedSecurity = candidate;
            if (selectedSecurity == null) {
                issue(result, securityPointer, "error", request.securityAlternative() == null
                        ? "Select one complete security alternative" : "The selected security alternative is not in this document");
            } else if (!selectedSecurity.path("available").asBoolean(false)) {
                issue(result, selectedSecurity.path("id").asString(), "error",
                        selectedSecurity.path("reason").asString("This security alternative is unsupported"));
            } else {
                result.put("selectedSecurityAlternative", selectedSecurity.path("id").asString());
                if (selectedSecurity.path("deprecated").asBoolean(false))
                    issue(result, selectedSecurity.path("id").asString(), "warning", "This security scheme is deprecated");
                if (selectedSecurity.hasNonNull("credentialDraft")) {
                    result.set("credentialDraft", selectedSecurity.get("credentialDraft"));
                    issue(result, selectedSecurity.path("id").asString(), "warning",
                            "Configure a credential explicitly. Client registration and secrets are never obtained from this document");
                }
            }
            if (format == null || baseUrl.isEmpty() || selectedSecurity == null || hasErrors(result)) return result;

            var values = JSON.objectNode().put("gateway", "custom").put("baseUrl", baseUrl)
                    .put("apiFormat", format.wireName()).put("responsesDialect", "standard")
                    .put("auth", selectedSecurity.path("auth").asString()).put("credential", "")
                    .put("apiKeyLocation", selectedSecurity.path("apiKeyLocation").asString("header"))
                    .put("apiKeyName", selectedSecurity.path("apiKeyName").asString(""));
            var validation = new LinkedHashMap<String, Object>();
            values.properties().forEach(field -> validation.put(field.getKey(), JsonValues.from(field.getValue())));
            EndpointProfiles.build(new ValueContext(validation));
            result.set("values", values);
            result.put("complete", true);
        } catch (OpenApiDocument.Problem invalid) {
            issue(result, invalid.pointer(), "error", invalid.getMessage());
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            // Validators only receive projected URL/protocol/authentication metadata, not secrets.
            issue(result, "", "error", invalid.getMessage() == null ? "Invalid endpoint metadata" : invalid.getMessage());
        }
        return result;
    }

    private static List<Operation> operations(OpenApiDocument document) {
        var paths = document.root().path("paths");
        if (paths.isMissingNode()) return List.of();
        if (!paths.isObject()) throw new OpenApiDocument.Problem("/paths", "Paths must be an object");
        var found = new ArrayList<Operation>();
        for (var field : paths.properties()) {
            var format = formatOf(field.getKey());
            if (format == null) continue;
            var itemPointer = OpenApiDocument.child("/paths", field.getKey());
            try {
                var item = document.resolve(field.getValue(), itemPointer);
                if (!item.node().has("post")) continue;
                var operationPointer = OpenApiDocument.child(item.pointer(), "post");
                var operation = document.resolve(item.node().path("post"), operationPointer);
                // A referenced path item has no local /post node; its local reference object is the
                // actual document pointer identifying this route, not a fabricated child pointer.
                var id = field.getValue().has("$ref") ? itemPointer : OpenApiDocument.child(itemPointer, "post");
                var problem = field.getKey().contains("{") || field.getKey().contains("}")
                        ? "Generation path parameters require manual endpoint configuration" : "";
                found.add(new Operation(id, field.getKey(), format, operation.node(), operation.pointer(),
                        item.node(), item.pointer(), problem));
            } catch (OpenApiDocument.Problem unavailable) {
                found.add(new Operation(itemPointer, field.getKey(), format, JSON.objectNode(), itemPointer,
                        JSON.objectNode(), itemPointer, unavailable.getMessage()));
            }
        }
        return List.copyOf(found);
    }

    private static List<Server> servers(OpenApiDocument document, Operation operation) {
        JsonNode owner = document.root();
        String pointer = "";
        if (operation != null && operation.operation().has("servers")) {
            owner = operation.operation(); pointer = operation.operationPointer();
        } else if (operation != null && operation.pathItem().has("servers")) {
            owner = operation.pathItem(); pointer = operation.pathItemPointer();
        }
        if (!owner.has("servers")) {
            if (owner != document.root()) { owner = document.root(); pointer = ""; }
        }
        if (!owner.has("servers")) return List.of(new Server("", JSON.objectNode().put("url", "/")));
        var values = owner.path("servers");
        var listPointer = OpenApiDocument.child(pointer, "servers");
        if (!values.isArray()) throw new OpenApiDocument.Problem(listPointer, "Servers must be an array");
        if (values.isEmpty()) return List.of(new Server(listPointer, JSON.objectNode().put("url", "/")));
        var servers = new ArrayList<Server>();
        for (int i = 0; i < values.size(); i++) servers.add(new Server(listPointer + "/" + i, values.get(i)));
        return List.copyOf(servers);
    }

    private static ObjectNode serverPreview(Server server, Map<String, String> supplied) {
        var node = JSON.objectNode().put("id", server.pointer()).put("url", server.node().path("url").asString(""));
        var variables = node.putObject("variables");
        var matcher = VARIABLE.matcher(node.path("url").asString());
        while (matcher.find()) {
            var name = matcher.group(1);
            var declaration = server.node().path("variables").path(name);
            var variable = variables.putObject(name);
            if (declaration.path("default").isString()) variable.set("default", declaration.path("default"));
            if (declaration.path("enum").isArray()) variable.set("enum", declaration.path("enum"));
            variable.put("required", !declaration.path("default").isString());
            if (supplied != null && supplied.containsKey(name)) variable.put("value", supplied.get(name));
            else if (declaration.path("default").isString()) variable.put("value", declaration.path("default").asString());
        }
        return node;
    }

    private static URI resolveServer(OpenApiDocument document, Server server, Map<String, String> variables) {
        if (!server.node().isObject() || !server.node().path("url").isString())
            throw new OpenApiDocument.Problem(server.pointer(), "A server needs a URL");
        var template = server.node().path("url").asString();
        var matcher = VARIABLE.matcher(template);
        var resolved = new StringBuilder();
        while (matcher.find()) {
            var name = matcher.group(1);
            var declaration = server.node().path("variables").path(name);
            String value = variables != null && variables.containsKey(name) ? variables.get(name)
                    : declaration.path("default").isString() ? declaration.path("default").asString() : null;
            if (value == null) throw new OpenApiDocument.Problem(server.pointer(), "Supply server variable: " + name);
            if (declaration.has("enum")) {
                var choices = declaration.path("enum");
                boolean accepted = false;
                if (!choices.isArray()) throw new OpenApiDocument.Problem(server.pointer(), "Server variable enum must be an array");
                for (var choice : choices) {
                    if (!choice.isString()) throw new OpenApiDocument.Problem(server.pointer(), "Server variable choices must be strings");
                    if (choice.asString().equals(value)) accepted = true;
                }
                if (!accepted) throw new OpenApiDocument.Problem(server.pointer(), "Server variable is outside its declared enum: " + name);
            }
            matcher.appendReplacement(resolved, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        if (resolved.indexOf("{") >= 0 || resolved.indexOf("}") >= 0)
            throw new OpenApiDocument.Problem(server.pointer(), "Unresolved server URL variable");
        return document.resolveUrl(resolved.toString(), server.pointer());
    }

    private static String endpointBase(URI server, Operation operation) {
        if (!server.isAbsolute() || server.getHost() == null || server.getUserInfo() != null
                || server.getRawQuery() != null || server.getRawFragment() != null
                || !(server.getScheme().equalsIgnoreCase("http") || server.getScheme().equalsIgnoreCase("https")))
            throw new OpenApiDocument.Problem("/servers", "Endpoint servers must use HTTP(S), without userinfo, query strings or fragments");
        var base = trimSlash(server.toASCIIString());
        if (operation == null) return base;
        var path = operation.path();
        var prefix = trimSlash(server.getRawPath());
        var joined = !prefix.isEmpty() && path.startsWith(prefix + "/")
                ? server.getScheme() + "://" + server.getRawAuthority() + path : base + path;
        return trimSlash(joined.substring(0, joined.length() - operation.format().path().length()));
    }

    private static ApiFormat formatOf(String path) {
        if (!path.startsWith("/")) return null;
        if (path.endsWith(ApiFormat.RESPONSES.path())) return ApiFormat.RESPONSES;
        if (path.endsWith(ApiFormat.CHAT_COMPLETIONS.path())) return ApiFormat.CHAT_COMPLETIONS;
        return null;
    }
    private static String trimSlash(String value) {
        if (value == null) return "";
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') end--;
        return value.substring(0, end);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void issue(ObjectNode result, String pointer, String severity, String message) {
        result.withArray("issues").addObject().put("pointer", pointer).put("severity", severity).put("message", message);
    }
    private static boolean hasErrors(ObjectNode result) {
        for (var issue : result.path("issues")) if (issue.path("severity").asString().equals("error")) return true;
        return false;
    }
    private record Server(String pointer, JsonNode node) {}
    private record Operation(String id, String path, ApiFormat format, JsonNode operation, String operationPointer,
            JsonNode pathItem, String pathItemPointer, String problem) {}
}
