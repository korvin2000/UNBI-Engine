package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CredentialApiTest {
    @TempDir static Path home;
    @LocalServerPort int port;
    @Autowired ManagedCredentialStore managed;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void temporaryHome(DynamicPropertyRegistry registry) { registry.add("unbi.home", () -> home.toString()); }

    @Test
    void basicSecretsAreWriteOnlyAndOmissionPreservesThem() {
        var created = post("/api/credentials", """
                {"name":"basic-api","type":"basic","username":"alice","password":"distinctive-password",
                 "resourceBaseUrl":"https://gateway.example/v1"}
                """, null);
        assertThat(created.status()).isEqualTo(200);
        var before = get("/api/credentials/basic-api/configuration");
        assertThat(before.body()).doesNotContain("distinctive-password");
        assertThat(JSON.readTree(before.body()).path("hasPassword").asBoolean()).isTrue();
        assertThat(before.cacheControl()).contains("no-store");
        var replacement = post("/api/credentials/basic-api/configuration", """
                {"configuration":{"username":"alice","resourceBaseUrl":"https://gateway.example/v2"}}
                """, null);
        assertThat(replacement.status()).isEqualTo(200);
        assertThat(managed.find("basic-api").orElseThrow().configuration().path("password").asString())
                .isEqualTo("distinctive-password");
        assertThat(get("/api/credentials").body()).doesNotContain("distinctive-password");
    }

    @Test
    void managementRejectsForeignOriginsAndFormsWithoutAddingAnApplicationLoginWall() {
        assertThat(get("/api/catalog").status()).isEqualTo(200);
        assertThat(post("/api/credentials", "{\"name\":\"evil\",\"value\":\"secret\"}", "https://evil.example").status()).isEqualTo(403);
        var form = RestClient.create().post().uri(base() + "/api/credentials")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body("name=evil&value=secret")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(form).isEqualTo(415);
        var allowed = post("/api/credentials", "{\"name\":\"legacy-key\",\"value\":\"write-only-key\"}", "http://localhost:4200");
        assertThat(allowed.status()).isEqualTo(200);
        assertThat(get("/api/credentials").body()).doesNotContain("write-only-key");
    }

    @Test
    void clientCredentialsLoginAndEndpointTestUseTheEngineOwnedToken() throws Exception {
        var tokenCalls = new AtomicInteger();
        var seen = new AtomicReference<String>();
        var gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            tokenCalls.incrementAndGet();
            send(exchange, "{\"access_token\":\"distinctive-access\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
        });
        gateway.createContext("/models", exchange -> {
            seen.set(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, "{\"data\":[{\"id\":\"fixture-model\"}]}");
        });
        gateway.start();
        try {
            var url = "http://127.0.0.1:" + gateway.getAddress().getPort();
            var body = JSON.createObjectNode().put("name", "client-api").put("type", "oauth2");
            body.putObject("configuration").put("resourceBaseUrl", url).put("grantType", "client_credentials")
                    .put("clientId", "registered").put("clientSecret", "distinctive-client-secret")
                    .put("clientAuthentication", "client_secret_basic").put("tokenUrl", url + "/token");
            assertThat(post("/api/credentials", body.toString(), null).status()).isEqualTo(200);
            var login = post("/api/credentials/client-api/login", "{}", null);
            assertThat(login.status()).isEqualTo(200);
            assertThat(JSON.readTree(login.body()).path("status").asString()).isEqualTo("ready");
            var profile = JSON.createObjectNode();
            profile.putObject("values").put("gateway", "custom").put("baseUrl", url)
                    .put("auth", "oauth2").put("credential", "client-api");
            var tested = post("/api/profiles/llm.endpoint/test", profile.toString(), null);
            assertThat(JSON.readTree(tested.body()).path("ok").asBoolean()).isTrue();
            assertThat(seen.get()).isEqualTo("Bearer distinctive-access");
            assertThat(tokenCalls).hasValue(1);
            assertThat(get("/api/credentials").body() + get("/api/credentials/client-api/configuration").body()
                    + login.body() + tested.body()).doesNotContain("distinctive-access", "distinctive-client-secret");
        } finally { gateway.stop(0); }
    }

    @Test
    void discoveryIsAnUnsavedCredentialFreePreviewWithExactIssuerChecking() throws Exception {
        var seenAuthorization = new AtomicReference<String>();
        var issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var url = "http://127.0.0.1:" + issuer.getAddress().getPort();
        issuer.createContext("/metadata", exchange -> {
            seenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            send(exchange, JSON.createObjectNode().put("issuer", url).put("token_endpoint", url + "/token").toString());
        });
        issuer.start();
        try {
            var request = JSON.createObjectNode();
            request.putObject("configuration").put("issuer", url).put("metadataUrl", url + "/metadata")
                    .put("clientSecret", "must-not-travel");
            var preview = post("/api/credentials/discovery-draft/discover", request.toString(), null);
            assertThat(preview.status()).isEqualTo(200);
            assertThat(preview.body()).doesNotContain("must-not-travel");
            assertThat(seenAuthorization.get()).isNull();
            assertThat(managed.names()).doesNotContain("discovery-draft");
            ((tools.jackson.databind.node.ObjectNode) request.path("configuration")).put("issuer", url + "/wrong");
            assertThat(post("/api/credentials/discovery-draft/discover", request.toString(), null).status()).isEqualTo(400);
        } finally { issuer.stop(0); }
    }

    @Test
    void malformedOwnedDefinitionsCanBeRemovedWithoutReadingTheirSecrets() throws Exception {
        assertThat(post("/api/credentials", """
                {"name":"broken-api","type":"basic","username":"user","password":"private-password",
                 "resourceBaseUrl":"https://gateway.example/v1"}
                """, null).status()).isEqualTo(200);
        java.nio.file.Files.writeString(home.resolve("credentials/broken-api.json"), "{invalid private-password");
        var nativeHome = home.resolve("credentials/codex/broken-api");
        SecretFiles.directory(nativeHome);
        SecretFiles.write(nativeHome.resolve("auth.json"), "private-session".getBytes(StandardCharsets.UTF_8));
        assertThat(get("/api/credentials").body()).doesNotContain("private-password", "private-session");
        var status = RestClient.create().delete().uri(base() + "/api/credentials/broken-api")
                .exchange((request, response) -> response.getStatusCode().value());
        assertThat(status).isEqualTo(204);
        assertThat(home.resolve("credentials/broken-api.json")).doesNotExist();
        assertThat(nativeHome).doesNotExist();
    }

    private Reply get(String path) {
        return RestClient.create().get().uri(base() + path).exchange((request, response) -> new Reply(
                response.getStatusCode().value(), response.getHeaders().getFirst("Cache-Control"), response.bodyTo(String.class)));
    }
    private Reply post(String path, String body, String origin) {
        var request = RestClient.create().post().uri(base() + path).contentType(MediaType.APPLICATION_JSON);
        if (origin != null) request.header("Origin", origin);
        return request.body(body).exchange((sent, response) -> new Reply(response.getStatusCode().value(),
                response.getHeaders().getFirst("Cache-Control"), response.bodyTo(String.class)));
    }
    private String base() { return "http://localhost:" + port; }
    private record Reply(int status, String cacheControl, String body) {}
    private static void send(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
