package com.unbi.engine.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The library endpoints, driven the way the editor drives them, against a temporary home. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkflowApiTest {

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
    void saveListFavouriteOpenDelete() {
        var saved = post("/api/workflows", """
                {"name":"Daily digest","document":{"format":"unbi-workflow","version":1,"nodes":[],"edges":[]}}""");
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().path("id").asString()).isEqualTo("Daily digest");

        var again = post("/api/workflows", """
                {"name":"daily digest","document":{"format":"unbi-workflow","version":1,"nodes":[],"edges":[]}}""");
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().path("detail").asString()).contains("already exists");

        var starred = post("/api/workflows/Daily%20digest/favorite", "{\"favorite\":true}");
        assertThat(starred.body().path("favorite").asBoolean()).isTrue();
        assertThat(home.resolve("workflows/favorites/Daily digest.unbi.json")).exists();

        var listing = get("/api/workflows");
        assertThat(listing.body().path("directory").asString()).isEqualTo(home.resolve("workflows").toString());
        assertThat(listing.body().path("workflows")).hasSize(1);

        var opened = get("/api/workflows/Daily%20digest");
        assertThat(opened.body().path("document").path("format").asString()).isEqualTo("unbi-workflow");

        var renamed = post("/api/workflows/Daily%20digest/rename", "{\"name\":\"Weekly digest\"}");
        assertThat(renamed.body().path("name").asString()).isEqualTo("Weekly digest");

        var deleted = RestClient.create()
                .delete()
                .uri(URI.create("http://localhost:" + port + "/api/workflows/Weekly%20digest"))
                .exchange((request, reply) -> reply.getStatusCode().value());
        assertThat(deleted).isEqualTo(204);
        assertThat(get("/api/workflows/Weekly%20digest").status()).isEqualTo(404);
    }

    @Test
    void aBadNameIsRefusedWithTheReason() {
        var refused = post("/api/workflows", """
                {"name":"../escape","document":{"format":"unbi-workflow","version":1,"nodes":[],"edges":[]}}""");
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body().path("detail").asString()).contains("cannot contain");
    }

    private Reply get(String path) {
        return RestClient.create()
                .get()
                .uri(URI.create("http://localhost:" + port + path))
                .exchange((request, reply) -> new Reply(reply.getStatusCode().value(), parse(reply.bodyTo(String.class))));
    }

    private Reply post(String path, String json) {
        return RestClient.create()
                .post()
                .uri(URI.create("http://localhost:" + port + path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange((request, reply) -> new Reply(reply.getStatusCode().value(), parse(reply.bodyTo(String.class))));
    }

    private JsonNode parse(String body) {
        return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    private record Reply(int status, JsonNode body) {}
}
