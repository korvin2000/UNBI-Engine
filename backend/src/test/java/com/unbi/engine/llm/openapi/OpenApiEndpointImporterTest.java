package com.unbi.engine.llm.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OpenApiEndpointImporterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final OpenApiEndpointImporter importer = new OpenApiEndpointImporter();

    @ParameterizedTest
    @ValueSource(strings = {"v3.0.json", "v3.1.yaml", "v3.2.1.yaml"})
    void projectsBundledVersionsWithoutSecretsOrDuplicatedVersionPaths(String fixture) throws Exception {
        var projected = preview(fixture(fixture));
        assertThat(projected.path("complete").asBoolean()).as(projected.toString()).isTrue();
        assertThat(projected.path("values").path("baseUrl").asString()).isEqualTo("https://gateway.example/v1");
        assertThat(projected.path("values").path("apiFormat").asString())
                .isEqualTo(fixture.equals("v3.0.json") ? "chat_completions" : "responses");
        assertThat(projected.toString()).doesNotContain("ignored-example-secret", "ignored-default-secret",
                "ignored-password-secret", "ignored-client-id", "ignored-client-secret");
        assertThat(projected.path("values").path("credential").asString()).isEmpty();
        if (fixture.equals("v3.2.1.yaml")) {
            var config = projected.path("credentialDraft").path("configuration");
            assertThat(config.path("grantType").asString()).isEqualTo("authorization_code");
            assertThat(config.path("scopes").toString()).isEqualTo("[\"inference.write\"]");
            assertThat(config.has("clientId")).isFalse();
            assertThat(config.has("clientSecret")).isFalse();
        }
    }

    @Test
    void operationServersAndAnonymousSecurityOverridePathAndRoot() {
        var document = JSON.readTree("""
                {"openapi":"3.2.1","servers":[{"url":"https://root.example/v1"}],
                 "security":[{"bearer":[]}],"components":{"securitySchemes":{"bearer":{"type":"http","scheme":"bearer"}}},
                 "paths":{"/responses":{"servers":[{"url":"https://path.example/v1"}],"post":{
                 "servers":[{"url":"https://operation.example/v1"}],"security":[]}}}}
                """);
        var operation = (ObjectNode) document.path("paths").path("/responses").path("post");
        var projected = preview(document.toString());
        assertThat(projected.path("values").path("baseUrl").asString()).isEqualTo("https://operation.example/v1");
        assertThat(projected.path("values").path("auth").asString()).isEqualTo("none");
        operation.remove("servers");
        assertThat(preview(document.toString()).path("values").path("baseUrl").asString()).isEqualTo("https://path.example/v1");
        operation.remove("security");
        assertThat(preview(document.toString()).path("values").path("auth").asString()).isEqualTo("bearer");
    }

    @Test
    void selectionMustBeACompleteSupportedSecurityAlternative() {
        var document = """
                {"openapi":"3.1.0","servers":[{"url":"https://gateway.example/v1"}],"paths":{"/responses":{"post":{}}},
                 "security":[{"bearer":[],"key":[]},{"bearer":[]}],"components":{"securitySchemes":{
                 "bearer":{"type":"http","scheme":"bearer"},"key":{"type":"apiKey","in":"header","name":"X-Key"}}}}
                """;
        assertThat(preview(document).path("complete").asBoolean()).isFalse();
        var and = importer.preview(new OpenApiEndpointImporter.Request(document, null, null, null, null, "/security/0", null));
        assertThat(and.path("complete").asBoolean()).isFalse();
        assertThat(and.path("values").has("auth")).isFalse();
        var or = importer.preview(new OpenApiEndpointImporter.Request(document, null, null, null, null, "/security/1", null));
        assertThat(or.path("complete").asBoolean()).isTrue();
        assertThat(or.path("values").path("auth").asString()).isEqualTo("bearer");
    }

    @Test
    void variablesRequireValuesAndRespectTheirEnums() {
        var document = """
                {"openapi":"3.2.1","servers":[{"url":"https://gateway.example/{version}",
                 "variables":{"version":{"enum":["v1","v2"]}}}],"paths":{"/responses":{"post":{}}}}
                """;
        assertThat(preview(document).path("complete").asBoolean()).isFalse();
        var invalid = importer.preview(new OpenApiEndpointImporter.Request(document, null, null, null, Map.of("version", "v3"), null, null));
        assertThat(invalid.path("complete").asBoolean()).isFalse();
        var valid = importer.preview(new OpenApiEndpointImporter.Request(document, null, null, null, Map.of("version", "v2"), null, null));
        assertThat(valid.path("complete").asBoolean()).isTrue();
        assertThat(valid.path("values").path("baseUrl").asString()).isEqualTo("https://gateway.example/v2");
    }

    @Test
    void relativeServersNeedAnExplicitDocumentBaseAndManualProtocolIsNeverGuessed() {
        var document = "{\"openapi\":\"3.0.3\",\"servers\":[{\"url\":\"../v1\"}]}";
        assertThat(preview(document).path("complete").asBoolean()).isFalse();
        var noProtocol = importer.preview(new OpenApiEndpointImporter.Request(document, "https://gateway.example/spec/openapi.yaml", null, null, null, null, null));
        assertThat(noProtocol.path("complete").asBoolean()).isFalse();
        var chosen = importer.preview(new OpenApiEndpointImporter.Request(document, "https://gateway.example/spec/openapi.yaml", null, null, null, null, "responses"));
        assertThat(chosen.path("complete").asBoolean()).isTrue();
        assertThat(chosen.path("values").path("baseUrl").asString()).isEqualTo("https://gateway.example/v1");
        assertThat(chosen.path("values").path("apiFormat").asString()).isEqualTo("responses");
    }

    @Test
    void consumedExternalPathReferencesCannotBecomeAnonymousDrafts() {
        var document = """
                {"openapi":"3.2.1","servers":[{"url":"https://gateway.example/v1"}],
                 "paths":{"/responses":{"$ref":"https://schemas.example/private-path.yaml"}}}
                """;
        var result = preview(document);
        assertThat(result.path("complete").asBoolean()).isFalse();
        assertThat(result.path("values").has("auth")).isFalse();
        assertThat(result.path("operations").path(0).path("available").asBoolean()).isFalse();
    }

    @Test
    void localPathItemReferencesKeepRealSelectionPointers() {
        var document = """
                {"openapi":"3.2.1","servers":[{"url":"https://gateway.example/v1"}],
                 "paths":{"/responses":{"$ref":"#/components/pathItems/shared"}},
                 "components":{"pathItems":{"shared":{"post":{"security":[{}]}}}}}
                """;
        var result = preview(document);
        assertThat(result.path("complete").asBoolean()).as(result.toString()).isTrue();
        var root = JSON.readTree(document);
        for (var operation : result.path("operations")) assertThat(root.at(operation.path("id").asString()).isMissingNode()).isFalse();
        for (var alternative : result.path("securityAlternatives")) assertThat(root.at(alternative.path("id").asString()).isMissingNode()).isFalse();
        assertThat(result.path("values").path("auth").asString()).isEqualTo("none");
    }

    @Test
    void oldSelectionsAreRevalidatedAgainstTheNewDocument() {
        var document = "{\"openapi\":\"3.1.0\",\"servers\":[{\"url\":\"https://gateway.example\"}],\"paths\":{\"/responses\":{\"post\":{}}}}";
        var result = importer.preview(new OpenApiEndpointImporter.Request(document, null, "/paths/~1old/post", null, null, null, null));
        assertThat(result.path("complete").asBoolean()).isFalse();
        assertThat(result.path("values").isEmpty()).isTrue();
    }

    @Test
    void yamlAliasesUseTheirValuesRatherThanTheirPotentiallyMisleadingNames() {
        var document = """
                openapi: 3.2.1
                x-kind: &bearer basic
                servers: [{url: 'https://gateway.example/v1'}]
                paths: {'/responses': {post: {}}}
                security: [{auth: []}]
                components:
                  securitySchemes:
                    auth: {type: http, scheme: *bearer}
                """;
        var projected = preview(document);
        assertThat(projected.path("complete").asBoolean()).isTrue();
        assertThat(projected.path("values").path("auth").asString()).isEqualTo("basic");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "\"bearer\"", "[null]", "[{\"missing\":[]}]"})
    void malformedSecurityNeverBecomesAnonymous(String security) {
        var document = "{\"openapi\":\"3.2.1\",\"servers\":[{\"url\":\"https://gateway.example\"}],"
                + "\"paths\":{\"/responses\":{\"post\":{}}},\"security\":" + security + "}";
        var projected = preview(document);
        assertThat(projected.path("complete").asBoolean()).isFalse();
        assertThat(projected.path("values").has("auth")).isFalse();
        assertThat(projected.path("securityAlternatives").get(0).path("auth").asString()).isNotEqualTo("none");
    }

    @Test
    void uriNamedRequirementsPreferAComponentNameThenResolveLocalPointers() {
        var document = JSON.readTree("""
                {"openapi":"3.2.1","servers":[{"url":"https://gateway.example"}],"paths":{"/responses":{"post":{}}},
                 "security":[{"#/components/securitySchemes/bearer":[]}],
                 "components":{"securitySchemes":{"bearer":{"type":"http","scheme":"bearer"}}}}
                """);
        assertThat(preview(document.toString()).path("values").path("auth").asString()).isEqualTo("bearer");
        ((ObjectNode) document.path("components").path("securitySchemes"))
                .set("#/components/securitySchemes/bearer", JSON.createObjectNode().put("type", "http").put("scheme", "basic"));
        assertThat(preview(document.toString()).path("values").path("auth").asString()).isEqualTo("basic");
    }

    @ParameterizedTest
    @ValueSource(strings = {"oauth2", "openIdConnect"})
    void metadataUrlsCreateDiscoveryDraftsWithoutInventedClientDetails(String type) {
        var scheme = JSON.createObjectNode().put("type", type);
        scheme.put(type.equals("oauth2") ? "oauth2MetadataUrl" : "openIdConnectUrl", "https://issuer.example/metadata");
        var document = JSON.createObjectNode().put("openapi", "3.2.1");
        document.putArray("servers").addObject().put("url", "https://gateway.example/v1");
        document.putObject("paths").putObject("/responses").putObject("post");
        document.putArray("security").addObject().putArray("auth").add("inference");
        document.putObject("components").putObject("securitySchemes").set("auth", scheme);
        var projected = preview(document.toString());
        assertThat(projected.path("complete").asBoolean()).as(projected.toString()).isTrue();
        var draft = projected.path("credentialDraft").path("configuration");
        assertThat(draft.path("metadataUrl").asString()).isEqualTo("https://issuer.example/metadata");
        assertThat(draft.has("clientId")).isFalse();
        assertThat(draft.has("clientSecret")).isFalse();
        assertThat(draft.has("grantType")).isFalse();
    }

    private ObjectNode preview(String document) {
        return importer.preview(new OpenApiEndpointImporter.Request(document, null, null, null, null, null, null));
    }
    private static String fixture(String name) throws Exception {
        try (var stream = OpenApiEndpointImporterTest.class.getResourceAsStream("/llm/openapi/" + name)) {
            return new String(java.util.Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
