package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.unbi.engine.llm.spec.LlmFailure;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Local-provider regression coverage for the native ChatGPT protocol. No live account is used. */
class ChatGptOAuthClientTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void browserLoginUsesPkceRejectsWrongStateAndConsumesReplay() throws Exception {
        try (var fixture = new Fixture(); var client = client(fixture)) {
            var tokenRelease = new CountDownLatch(1);
            fixture.server.createContext("/oauth/token", exchange -> {
                var form = form(exchange);
                assertThat(form.get("grant_type")).isEqualTo("authorization_code");
                assertThat(form.get("code_verifier")).isNotBlank();
                assertThat(form.get("redirect_uri")).isEqualTo(fixture.callback.toString());
                try {
                    tokenRelease.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                reply(exchange, 200, token("browser-access", "browser-refresh", "acct-browser"));
            });
            fixture.server.start();

            var started = new CountDownLatch(1);
            var visible = new AtomicReference<ObjectNode>();
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var login = workers.submit(() -> client.login("browser", value -> {
                    visible.set(value);
                    started.countDown();
                }, () -> false));
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                var publicState = visible.get();
                assertThat(publicState.path("status").asString()).isEqualTo("pending");
                assertThat(publicState.toString()).doesNotContain("browser-refresh", "browser-access");
                try (var duplicate = client(fixture)) {
                    assertThatThrownBy(() -> duplicate.login("browser", ignoredValue -> { }, () -> false))
                            .isInstanceOfSatisfying(LlmFailure.class,
                                    failure -> assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.AUTH));
                }
                var auth = URI.create(publicState.path("authorizationUrl").asString());
                var state = query(auth).get("state");
                assertThat(query(auth)).containsEntry("code_challenge_method", "S256");
                assertThat(state).isNotBlank();

                var callback = HttpClient.newHttpClient();
                var wrong = callback.send(HttpRequest.newBuilder(URI.create(fixture.callback + "?state=wrong&code=one-time"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(wrong.statusCode()).isEqualTo(400);
                var first = callback.send(HttpRequest.newBuilder(URI.create(fixture.callback + "?state=" + state + "&code=one-time"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(first.statusCode()).isEqualTo(200);
                var replay = callback.send(HttpRequest.newBuilder(URI.create(fixture.callback + "?state=" + state + "&code=one-time"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(replay.statusCode()).isEqualTo(400);
                assertThat(replay.body()).doesNotContain("one-time");
                tokenRelease.countDown();
                var session = login.get(3, TimeUnit.SECONDS);
                assertThat(session.path("status").asString()).isEqualTo("ready");
                assertThat(session.path("accountId").asString()).isEqualTo("acct-browser");
                assertThat(session.path("refreshToken").asString()).isEqualTo("browser-refresh");
            }
        }
    }

    @Test
    void deviceLoginUsesCodexDeviceAuthAndNeverPublishesDeviceSecret() throws Exception {
        try (var fixture = new Fixture(); var client = client(fixture)) {
            var polls = new AtomicInteger();
            fixture.server.createContext("/api/accounts/deviceauth/usercode", exchange -> reply(exchange, 200,
                    "{\"device_auth_id\":\"private-device-id\",\"user_code\":\"ABCD-EFGH\",\"interval\":1}"));
            fixture.server.createContext("/api/accounts/deviceauth/token", exchange -> {
                if (polls.getAndIncrement() == 0) {
                    reply(exchange, 403, "{\"error\":\"pending\",\"device_auth_id\":\"private-device-id\"}");
                } else {
                    var verifier = "device-verifier";
                    reply(exchange, 200, "{\"authorization_code\":\"private-authorization-code\",\"code_verifier\":\""
                            + verifier + "\",\"code_challenge\":\"" + challenge(verifier) + "\"}");
                }
            });
            fixture.server.createContext("/oauth/token", exchange -> {
                var form = form(exchange);
                assertThat(form.get("grant_type")).isEqualTo("authorization_code");
                assertThat(form.get("redirect_uri")).isEqualTo(fixture.issuer + "/deviceauth/callback");
                reply(exchange, 200, token("device-access", "device-refresh", "acct-device"));
            });
            fixture.server.start();

            var started = new AtomicReference<ObjectNode>();
            var session = client.login("device", started::set, () -> false);
            assertThat(started.get().path("verificationUrl").asText()).isEqualTo(fixture.issuer + "/codex/device");
            assertThat(started.get().toString()).doesNotContain("private-device-id", "private-authorization-code");
            assertThat(session.path("accountId").asString()).isEqualTo("acct-device");
            assertThat(polls).hasValue(2);
        }
    }

    @Test
    void browserCancellationStopsCallbackListenerAndRefreshRejectsChangedAccount() throws Exception {
        try (var fixture = new Fixture(); var client = client(fixture)) {
            fixture.server.createContext("/oauth/token", exchange -> reply(exchange, 200,
                    token("refresh-access", "rotated-refresh", "acct-new")));
            fixture.server.start();
            var cancelled = new AtomicBoolean();
            var started = new CountDownLatch(1);
            try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                var pending = workers.submit(() -> client.login("browser", value -> started.countDown(), cancelled::get));
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                cancelled.set(true);
                assertThatThrownBy(() -> pending.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .satisfies(error -> assertThat(error.getCause()).isInstanceOfSatisfying(LlmFailure.class,
                                failure -> assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.CANCELLED)));
            }
            // A cancelled ceremony must release the fixed callback port before returning.
            var probe = HttpServer.create(new InetSocketAddress("127.0.0.1", fixture.callback.getPort()), 0);
            try {
                probe.createContext("/auth/callback", exchange -> reply(exchange, 200, "ok"));
                probe.start();
            } finally {
                probe.stop(0);
            }

            var old = JSON.createObjectNode().put("status", "ready").put("accessToken", "old-access")
                    .put("refreshToken", "old-refresh").put("accountId", "acct-old")
                    .put("issuedAt", CLOCK.instant().toString()).put("expiresAt", "2026-01-01T00:10:00Z");
            assertThatThrownBy(() -> client.refresh(old, () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.AUTH);
                        assertThat(failure.getMessage()).doesNotContain("refresh-access", "rotated-refresh");
                    });
        }
    }

    @Test
    void refreshPreservesOmittedRefreshTokenAndRejectsExplicitEmptyToken() throws Exception {
        try (var fixture = new Fixture(); var client = client(fixture)) {
            var responses = new AtomicInteger();
            fixture.server.createContext("/oauth/token", exchange -> {
                if (responses.getAndIncrement() == 0) {
                    reply(exchange, 200, "{\"access_token\":\"" + jwt("acct-same")
                            + "\",\"token_type\":\"bearer\",\"expires_in\":60}");
                } else {
                    reply(exchange, 200, "{\"access_token\":\"" + jwt("acct-same")
                            + "\",\"refresh_token\":\"\",\"token_type\":\"bearer\",\"expires_in\":60}");
                }
            });
            fixture.server.start();
            var old = JSON.createObjectNode().put("status", "ready").put("accessToken", jwt("acct-same"))
                    .put("refreshToken", "old-refresh").put("accountId", "acct-same")
                    .put("issuedAt", CLOCK.instant().toString()).put("expiresAt", "2026-01-01T00:10:00Z");
            assertThat(client.refresh(old, () -> false).path("refreshToken").asString()).isEqualTo("old-refresh");
            assertThatThrownBy(() -> client.refresh(old, () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.RESPONSE_FORMAT));
        }
    }

    @Test
    void refreshBoundsTokenBodyAndClassifiesProviderStatusWithoutBodyDetails() throws Exception {
        try (var fixture = new Fixture(); var client = client(fixture)) {
            var calls = new AtomicInteger();
            fixture.server.createContext("/oauth/token", exchange -> {
                int call = calls.getAndIncrement();
                if (call == 0) {
                    reply(exchange, 429, "{\"error\":\"private-rate-detail\"}");
                } else if (call == 1) {
                    reply(exchange, 503, "{\"error\":\"private-server-detail\"}");
                } else {
                    var oversized = "{\"access_token\":\"private-oversized-secret\",\"padding\":\""
                            + "x".repeat(70_000) + "\"}";
                    reply(exchange, 200, oversized);
                }
            });
            fixture.server.start();
            var old = JSON.createObjectNode().put("status", "ready").put("accessToken", jwt("acct-same"))
                    .put("refreshToken", "old-refresh").put("accountId", "acct-same")
                    .put("issuedAt", CLOCK.instant().toString()).put("expiresAt", "2026-01-01T00:10:00Z");
            assertThatThrownBy(() -> client.refresh(old, () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.RATE_LIMIT);
                        assertThat(failure.getMessage()).doesNotContain("private-rate-detail");
                    });
            assertThatThrownBy(() -> client.refresh(old, () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.SERVER);
                        assertThat(failure.getMessage()).doesNotContain("private-server-detail");
                    });
            assertThatThrownBy(() -> client.refresh(old, () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.RESPONSE_FORMAT);
                        assertThat(failure.getMessage()).doesNotContain("private-oversized-secret");
                    });
        }
    }
    @Test
    void refreshRejectsNonBearerTokensWithoutLeakingProviderFields() throws Exception {
        try (var fixture = new Fixture(); var client = client(fixture)) {
            fixture.server.createContext("/oauth/token", exchange -> reply(exchange, 200,
                    "{\"access_token\":\"" + jwt("acct-same")
                            + "\",\"refresh_token\":\"private-refresh\",\"token_type\":\"DPoP\",\"expires_in\":60}"));
            fixture.server.start();
            var old = JSON.createObjectNode().put("status", "ready").put("accessToken", jwt("acct-same"))
                    .put("refreshToken", "old-refresh").put("accountId", "acct-same")
                    .put("issuedAt", CLOCK.instant().toString()).put("expiresAt", "2026-01-01T00:10:00Z");
            assertThatThrownBy(() -> client.refresh(old, () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.UNSUPPORTED);
                        assertThat(failure.getMessage()).doesNotContain("private-refresh");
                    });
        }
    }

    @Test
    void accountClaimFallsBackToIdTokenAndRejectsContradictoryClaims() throws Exception {
        try (var fixture = new Fixture(); var client = client(fixture)) {
            var responses = new AtomicInteger();
            fixture.server.createContext("/oauth/token", exchange -> {
                if (responses.getAndIncrement() == 0) {
                    reply(exchange, 200, "{\"access_token\":\"opaque-access\",\"id_token\":\"" + jwt("acct-id")
                            + "\",\"refresh_token\":\"same-refresh\",\"token_type\":\"Bearer\",\"expires_in\":60}");
                } else {
                    reply(exchange, 200, "{\"access_token\":\"" + jwt("acct-access") + "\",\"id_token\":\""
                            + jwt("acct-other") + "\",\"refresh_token\":\"same-refresh\",\"token_type\":\"Bearer\",\"expires_in\":60}");
                }
            });
            fixture.server.start();
            var old = JSON.createObjectNode().put("status", "ready").put("accessToken", jwt("acct-id"))
                    .put("refreshToken", "old-refresh").put("accountId", "acct-id")
                    .put("issuedAt", CLOCK.instant().toString()).put("expiresAt", "2026-01-01T00:10:00Z");
            var first = client.refresh(old, () -> false);
            assertThat(first.path("accountId").asString()).isEqualTo("acct-id");
            assertThatThrownBy(() -> client.refresh(first, () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.AUTH));
        }
    }

    private static ChatGptOAuthClient client(Fixture fixture) {
        ChatGptOAuthClient.Sleeper noWait = (duration, cancelled) -> { };
        return new ChatGptOAuthClient(URI.create(fixture.issuer), fixture.callback, CLOCK, noWait,
                java.time.Duration.ofSeconds(8), java.time.Duration.ofSeconds(8));
    }

    private static String token(String access, String refresh, String account) {
        return "{\"access_token\":\"" + jwt(account) + "\",\"refresh_token\":\"" + refresh
                + "\",\"token_type\":\"Bearer\",\"expires_in\":60}";
    }

    private static String jwt(String account) {
        var header = Base64.getUrlEncoder().withoutPadding().encodeToString("{}".getBytes(StandardCharsets.UTF_8));
        var payload = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\""
                + account + "\"}}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".signature";
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static Map<String, String> query(URI uri) {
        var result = new LinkedHashMap<String, String>();
        for (var pair : uri.getRawQuery().split("&")) {
            var values = pair.split("=", 2);
            result.put(URLDecoder.decode(values[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(values.length == 2 ? values[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        var result = new LinkedHashMap<String, String>();
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (var pair : body.split("&")) {
            var values = pair.split("=", 2);
            result.put(URLDecoder.decode(values[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(values.length == 2 ? values[1] : "", StandardCharsets.UTF_8));
        }
        return result;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final String issuer;
        final URI callback;

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            issuer = "http://127.0.0.1:" + server.getAddress().getPort();
            callback = URI.create("http://localhost:" + freePort() + "/auth/callback");
        }

        @Override public void close() { server.stop(0); }
    }
}
