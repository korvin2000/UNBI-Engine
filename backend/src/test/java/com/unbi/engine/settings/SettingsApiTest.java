package com.unbi.engine.settings;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.config.DataDirectory;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The settings endpoints against a real application whose home is a temporary directory — so the
 * test never reads or writes the settings of whoever runs it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettingsApiTest {

    @TempDir
    static Path home;

    @TempDir
    Path elsewhere;

    @DynamicPropertySource
    static void useTemporaryHome(DynamicPropertyRegistry registry) {
        registry.add("unbi.home", () -> home.toString());
    }

    @LocalServerPort
    int port;


    @Autowired
    DataDirectory dataDirectory;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void describesWhereEverythingIsAndWhatTheEngineIs() {
        var settings = get("/api/settings");

        assertThat(settings.get("format").asString()).isEqualTo("unbi-settings");
        assertThat(settings.path("paths").path("data").path("effective").asString()).isEqualTo(home.toString());
        assertThat(settings.path("paths").path("workflows").path("default").asString())
                .isEqualTo(home.resolve("workflows").toString());
        assertThat(settings.path("engine").path("version").asString()).isNotBlank();
        assertThat(settings.path("engine").path("java").asString()).startsWith("26");
    }

    @Test
    void listsTheProfileSchemasForTheSettingsPage() {
        var schemas = get("/api/profiles").path("schemas");

        assertThat(schemas).isNotEmpty();
        assertThat(schemas.get(0).path("id").asString()).isEqualTo("llm.endpoint");
        assertThat(schemas.get(0).path("label").asString()).isEqualTo("LLM Endpoint");
        assertThat(schemas.get(0).path("count").asInt()).isZero();
    }

    @Test
    void savesPreferencesAndReturnsTheWhole() {
        var settings = post("/api/settings/preferences", "{\"language\":\"en\",\"confirmDelete\":true}");

        assertThat(settings.path("preferences").path("language").asString()).isEqualTo("en");
        assertThat(settings.path("preferences").path("confirmDelete").asBoolean()).isTrue();
        assertThat(home.resolve("settings.json")).exists();
    }

    @Test
    void relocatesTheWorkflowLibraryAndSaysWhatItDid() {
        var body = "{\"target\":\"workflows\",\"directory\":" + mapper.writeValueAsString(elsewhere.toString())
                + ",\"copyExisting\":true}";
        var settings = post("/api/settings/paths", body);

        assertThat(settings.path("paths").path("workflows").path("effective").asString())
                .isEqualTo(elsewhere.toString());
        assertThat(settings.path("relocation").path("to").asString()).isEqualTo(elsewhere.toString());
        assertThat(settings.path("relocation").path("copied").asInt()).isZero();

        // And back to the default, so the other tests in this class are not affected by the order.
        var restored = post("/api/settings/paths", "{\"target\":\"workflows\",\"directory\":\"\"}");
        assertThat(restored.path("paths").path("workflows").path("configured").asString()).isEmpty();
    }

    @Test
    void dataRelocationReportsConflictWhileAnAuthenticationLeaseIsHeld() {
        try (var lease = dataDirectory.acquireLease()) {
            var response = RestClient.create()
                    .post()
                    .uri("http://localhost:" + port + "/api/settings/paths")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"target\":\"data\",\"directory\":"
                            + mapper.writeValueAsString(elsewhere.toString())
                            + "}")
                    .exchange((request, reply) -> new Reply(reply.getStatusCode(), reply.bodyTo(String.class)));

            assertThat(response.status().value()).isEqualTo(409);
            assertThat(response.body()).contains("finish or cancel authentication");
            assertThat(dataDirectory.root()).isEqualTo(home);
        }
    }

    @Test
    void aBadRequestCarriesTheReason() {
        var response = RestClient.create()
                .post()
                .uri("http://localhost:" + port + "/api/settings/paths")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"target\":\"attic\",\"directory\":\"\"}")
                .exchange((request, reply) -> new Reply(reply.getStatusCode(), reply.bodyTo(String.class)));

        assertThat(response.status().value()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("detail").asString()).contains("target must be");
    }

    private JsonNode get(String path) {
        return mapper.readTree(RestClient.create()
                .get()
                .uri("http://localhost:" + port + path)
                .retrieve()
                .body(String.class));
    }

    private JsonNode post(String path, String json) {
        return mapper.readTree(RestClient.create()
                .post()
                .uri("http://localhost:" + port + path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .body(String.class));
    }

    private record Reply(HttpStatusCode status, String body) {}
}
