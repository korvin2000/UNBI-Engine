package com.unbi.engine.settings.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bundle endpoints end to end: export as a download, then the same bytes uploaded twice — once
 * to be inspected, once to be imported — the way the settings dialog does it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BundleApiTest {

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void useTemporaryHome(DynamicPropertyRegistry registry) {
        registry.add("unbi.home", () -> home.toString());
    }

    @LocalServerPort
    int port;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void exportInspectImport() {
        post("/api/workflows", """
                {"name":"Roundtrip","document":{"format":"unbi-workflow","version":1,"nodes":[],"edges":[]}}""");
        post("/api/settings/preferences", "{\"language\":\"en\"}");

        var sections = mapper.readTree(get("/api/settings/sections"));
        assertThat(sections).extracting(node -> node.path("id").asString())
                .containsExactly("profiles", "credentials", "presets", "workflows", "preferences");
        assertThat(sections.get(3).path("count").asInt()).isEqualTo(1);
        assertThat(sections.get(1).path("sensitive").asBoolean()).isTrue();

        var download = RestClient.create()
                .post()
                .uri(URI.create("http://localhost:" + port + "/api/settings/export"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"sections\":[\"workflows\",\"preferences\"],\"password\":\"pw\"}")
                .exchange((request, reply) -> new Download(
                        reply.getStatusCode().value(),
                        reply.getHeaders().getFirst("Content-Disposition"),
                        reply.bodyTo(byte[].class)));
        assertThat(download.status()).isEqualTo(200);
        assertThat(download.disposition()).contains("unbi-settings-").contains(".ucfg");
        assertThat(download.bytes()).isNotEmpty();

        var inspected = mapper.readTree(upload("/api/settings/import/inspect", download.bytes(), null, null, null));
        assertThat(inspected.path("encrypted").asBoolean()).isTrue();
        assertThat(inspected.path("sections")).extracting(node -> node.path("id").asString())
                .containsExactly("workflows", "preferences");
        assertThat(inspected.path("sections").get(0).path("label").asString()).isEqualTo("Workflows");

        var imported = mapper.readTree(upload("/api/settings/import", download.bytes(), "workflows,preferences", "pw", "skip"));
        assertThat(imported.path("sections").get(0).path("skipped").asInt()).isEqualTo(1);
        assertThat(imported.path("sections").get(1).path("skipped").asInt()).isEqualTo(1);

        var wrong = RestClient.create()
                .post()
                .uri(URI.create("http://localhost:" + port + "/api/settings/import"))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form(download.bytes(), "workflows", "nope", "skip"))
                .exchange((request, reply) -> new Download(reply.getStatusCode().value(), "", reply.bodyTo(byte[].class)));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(mapper.readTree(wrong.bytes()).path("detail").asString()).contains("Wrong password");
    }

    private String get(String path) {
        return RestClient.create().get().uri(URI.create("http://localhost:" + port + path)).retrieve().body(String.class);
    }

    private String post(String path, String json) {
        return RestClient.create()
                .post()
                .uri(URI.create("http://localhost:" + port + path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .retrieve()
                .body(String.class);
    }

    private String upload(String path, byte[] file, String sections, String password, String conflicts) {
        return RestClient.create()
                .post()
                .uri(URI.create("http://localhost:" + port + path))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form(file, sections, password, conflicts))
                .retrieve()
                .body(String.class);
    }

    private static LinkedMultiValueMap<String, Object> form(byte[] file, String sections, String password, String conflicts) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("file", new ByteArrayResource(file) {
            @Override
            public String getFilename() {
                return "settings.ucfg";
            }
        });
        if (sections != null) {
            form.add("sections", sections);
        }
        if (password != null) {
            form.add("password", password);
        }
        if (conflicts != null) {
            form.add("conflicts", conflicts);
        }
        return form;
    }

    private record Download(int status, String disposition, byte[] bytes) {}
}
