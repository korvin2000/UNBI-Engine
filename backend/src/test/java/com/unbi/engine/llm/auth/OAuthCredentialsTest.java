package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class OAuthCredentialsTest {
    private final java.util.List<ManagedCredentialStore> stores = new java.util.ArrayList<>();
    private final java.util.List<OAuthTokenClient> clients = new java.util.ArrayList<>();

    @AfterEach
    void closeResources() {
        clients.forEach(OAuthTokenClient::close);
        stores.forEach(ManagedCredentialStore::close);
    }

    @Test
    void basicCredentialsAreLocalAndStatusDoesNotExposePassword(@TempDir Path root) {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var store = store(root, clock);
        var entry = store.create("basic", ManagedCredentialStore.Type.BASIC,
                JsonNodeFactory.instance.objectNode().put("resourceBaseUrl", "https://gateway.example/v1")
                        .put("username", "alice").put("password", "private-password"));
        var source = new OAuthCredentials(store, client(clock), clock);

        assertThat(source.find("basic")).get().extracting(Credential::token).isEqualTo("alice:private-password");
        assertThat(source.names()).containsExactly("basic");
        assertThat(source.status("basic").toString()).doesNotContain("private-password", "alice");
        assertThatThrownBy(() -> source.validate("basic", endpoint("https://gateway.example/v1", EndpointSpec.AuthScheme.OAUTH2)))
                .isInstanceOf(LlmFailure.class);
        assertThat(entry.session()).isEmpty();
    }

    @Test
    void clientCredentialsRefreshesAtTheProactiveBoundary(@TempDir Path root) throws IOException {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var tokenCalls = new AtomicInteger();
        var server = server(tokenCalls, false);
        try {
            var store = store(root, clock);
            var config = oauthConfig(server, "client_credentials");
            store.create("gateway", ManagedCredentialStore.Type.OAUTH2, config);
            var source = new OAuthCredentials(store, client(clock), clock);
            var first = source.resolve("gateway", java.net.URI.create(config.path("resourceBaseUrl").asString()),
                    Duration.ofSeconds(5), () -> false).orElseThrow();
            assertThat(first.token()).isEqualTo("access-1");
            clock.advance(Duration.ofSeconds(91));
            var second = source.resolve("gateway", java.net.URI.create(config.path("resourceBaseUrl").asString()),
                    Duration.ofSeconds(5), () -> false).orElseThrow();
            assertThat(second.token()).isEqualTo("access-2");
            assertThat(tokenCalls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refreshRotationIsPersistedAndStatusIsSecretFree(@TempDir Path root) throws IOException {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var calls = new AtomicInteger();
        var server = server(calls, true);
        try {
            var store = store(root, clock);
            var config = oauthConfig(server, "authorization_code");
            var created = store.create("gateway", ManagedCredentialStore.Type.OAUTH2, config);
            store.commitSession("gateway", created.revision(), session(clock.instant(), "old-access", "old-refresh", 100));
            var source = new OAuthCredentials(store, client(clock), clock);
            clock.advance(Duration.ofSeconds(91));
            var value = source.resolve("gateway", java.net.URI.create(config.path("resourceBaseUrl").asString()),
                    Duration.ofSeconds(5), () -> false).orElseThrow();
            assertThat(value.token()).isEqualTo("new-access");
            var persisted = store.find("gateway").orElseThrow().session();
            assertThat(persisted.path("refreshToken").asString()).isEqualTo("old-refresh");
            assertThat(source.status("gateway").toString()).doesNotContain("old-access", "old-refresh", "new-access");
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingRefreshTokenRequiresReconnectAndForcedFailureDoesNotReuseToken(@TempDir Path root) {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var store = store(root, clock);
        var config = oauthConfig("https://gateway.example/v1", "authorization_code");
        var created = store.create("gateway", ManagedCredentialStore.Type.OAUTH2, config);
        store.commitSession("gateway", created.revision(), session(clock.instant(), "access", null, 1));
        var source = new OAuthCredentials(store, client(clock), clock);
        clock.advance(Duration.ofSeconds(2));
        assertThatThrownBy(() -> source.resolve("gateway", java.net.URI.create("https://gateway.example/v1"),
                Duration.ofSeconds(1), () -> false)).isInstanceOf(LlmFailure.class);
        assertThat(store.find("gateway").orElseThrow().session().path("status").asString())
                .isEqualTo("reauth_required");
        assertThat(source.refresh("gateway", Credential.bearer("gateway", "access"),
                java.net.URI.create("https://gateway.example/v1"), Duration.ofSeconds(1), () -> false)).isEmpty();
    }

    @Test
    void concurrentExpirySharesOneRefreshAndPersistsRotation(@TempDir Path root) throws Exception {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var calls = new AtomicInteger();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            var body = "{\"access_token\":\"rotated-access\",\"token_type\":\"Bearer\",\"expires_in\":100,\"refresh_token\":\"rotated-refresh\"}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var store = store(root, clock);
            var config = oauthConfig(server, "authorization_code");
            var created = store.create("gateway", ManagedCredentialStore.Type.OAUTH2, config);
            store.commitSession("gateway", created.revision(), session(clock.instant(), "old-access", "old-refresh", 1));
            var client = client(clock);
            var source = new OAuthCredentials(store, client, clock);
            clock.advance(Duration.ofSeconds(2));
            var resource = java.net.URI.create(config.path("resourceBaseUrl").asString());
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var callsInFlight = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Credential>>();
                for (var i = 0; i < 20; i++) {
                    callsInFlight.add(java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> source.resolve("gateway", resource, Duration.ofSeconds(5), () -> false).orElseThrow(), executor));
                }
                assertThat(started.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                release.countDown();
                java.util.concurrent.CompletableFuture.allOf(callsInFlight.toArray(java.util.concurrent.CompletableFuture[]::new)).join();
                assertThat(callsInFlight).allSatisfy(value -> assertThat(value.join().token()).isEqualTo("rotated-access"));
            }
            assertThat(calls).hasValue(1);
            assertThat(store.find("gateway").orElseThrow().session().path("refreshToken").asString())
                    .isEqualTo("rotated-refresh");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void logoutRequiresAnExplicitReconnectEvenForClientCredentials(@TempDir Path root) throws Exception {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var calls = new AtomicInteger();
        var server = server(calls, false);
        try {
            var store = store(root, clock);
            var config = oauthConfig(server, "client_credentials");
            store.create("gateway", ManagedCredentialStore.Type.OAUTH2, config);
            var source = new OAuthCredentials(store, client(clock), clock);
            source.connect("gateway", Duration.ofSeconds(2), () -> false);
            store.logout("gateway");
            assertThatThrownBy(() -> source.resolve("gateway", java.net.URI.create(config.path("resourceBaseUrl").asString()),
                    Duration.ofSeconds(2), () -> false)).isInstanceOf(LlmFailure.class);
            assertThat(calls).hasValue(1);
            source.connect("gateway", Duration.ofSeconds(2), () -> false);
            assertThat(calls).hasValue(2);
        } finally { server.stop(0); }
    }

    @Test
    void aNonrenewableTokenRemainsUsableUntilItsActualExpiry(@TempDir Path root) {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var store = store(root, clock);
        var config = oauthConfig("https://gateway.example/v1", "authorization_code");
        var entry = store.create("gateway", ManagedCredentialStore.Type.OAUTH2, config);
        store.commitSession("gateway", entry.revision(), session(clock.instant(), "still-valid", null, 100));
        var source = new OAuthCredentials(store, client(clock), clock);
        clock.advance(Duration.ofSeconds(91));
        assertThat(source.resolve("gateway", java.net.URI.create("https://gateway.example/v1"),
                Duration.ofSeconds(1), () -> false).orElseThrow().token()).isEqualTo("still-valid");
        clock.advance(Duration.ofSeconds(9));
        assertThatThrownBy(() -> source.resolve("gateway", java.net.URI.create("https://gateway.example/v1"),
                Duration.ofSeconds(1), () -> false)).isInstanceOf(LlmFailure.class);
    }

    private ManagedCredentialStore store(Path root, Clock clock) {
        var value = new ManagedCredentialStore(new DataDirectory(root), clock);
        stores.add(value);
        return value;
    }

    private OAuthTokenClient client(Clock clock) {
        var value = new OAuthTokenClient(clock);
        clients.add(value);
        return value;
    }

    private static EndpointSpec endpoint(String base, EndpointSpec.AuthScheme auth) {
        return new EndpointSpec("test", "custom", base, auth, "basic", java.util.Map.of(), null, 5_000, false, null, false, null, EndpointSpec.ResponsesDialect.STANDARD, "header", "");
    }

    private static ObjectNode oauthConfig(HttpServer server, String grant) {
        var root = "http://localhost:" + server.getAddress().getPort();
        return oauthConfig(root + "/v1", grant).put("tokenUrl", root + "/token");
    }

    private static ObjectNode oauthConfig(String resource, String grant) {
        return JsonNodeFactory.instance.objectNode().put("resourceBaseUrl", resource)
                .put("grantType", grant).put("clientId", "client")
                .put("clientAuthentication", "none").put("tokenUrl", resource + "/token")
                .put("authorizationUrl", resource + "/authorize");
    }

    private static ObjectNode session(Instant issued, String access, String refresh, long seconds) {
        var result = JsonNodeFactory.instance.objectNode().put("accessToken", access).put("tokenType", "Bearer")
                .put("issuedAt", issued.toString()).put("expiresAt", issued.plusSeconds(seconds).toString()).put("status", "ready");
        if (refresh != null) result.put("refreshToken", refresh);
        return result;
    }

    private static HttpServer server(AtomicInteger calls, boolean refresh) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            var body = refresh
                    ? "{\"access_token\":\"new-access\",\"token_type\":\"Bearer\",\"expires_in\":100}"
                    : "{\"access_token\":\"access-" + calls.get() + "\",\"token_type\":\"Bearer\",\"expires_in\":100,\"refresh_token\":\"refresh-" + calls.get() + "\"}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
