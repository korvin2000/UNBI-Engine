package com.unbi.engine.llm.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.llm.auth.ManagedCredentialStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiImportApiTest {
    @TempDir static Path home;
    @LocalServerPort int port;
    @Autowired ManagedCredentialStore credentials;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry registry) { registry.add("unbi.home", () -> home.toString()); }

    @Test
    void advertisedImportProducesOnlyAnUnsavedDraft() {
        var schema = get("/api/profiles/llm.endpoint");
        assertThat(schema.path("schema").path("importFormats").get(0).asString()).isEqualTo("openapi");
        var document = """
                {"openapi":"3.2.1","servers":[{"url":"https://gateway.example/v1"}],
                 "paths":{"/responses":{"post":{}}},"security":[{"basic":[]}],
                 "components":{"securitySchemes":{"basic":{"type":"http","scheme":"basic"}}}}
                """;
        var request = JSON.createObjectNode().put("document", document);
        var projected = JSON.readTree(RestClient.create().post()
                .uri(base() + "/api/profiles/llm.endpoint/import/openapi")
                .contentType(MediaType.APPLICATION_JSON).body(request.toString()).retrieve().body(String.class));
        assertThat(projected.path("complete").asBoolean()).as(projected.toString()).isTrue();
        assertThat(projected.path("values").path("auth").asString()).isEqualTo("basic");
        assertThat(get("/api/profiles/llm.endpoint").path("profiles")).isEmpty();
        assertThat(credentials.names()).isEmpty();
        assertThat(home.resolve("profiles")).doesNotExist();
    }

    private JsonNode get(String path) { return JSON.readTree(RestClient.create().get().uri(base() + path).retrieve().body(String.class)); }
    private String base() { return "http://localhost:" + port; }
}
