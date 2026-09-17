package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class OAuthTokenClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void clientCredentialsUsesSpringGrantClientAndReturnsSafeSession() throws Exception {
        var seenAuthorization = new String[1];
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            seenAuthorization[0] = exchange.getRequestHeaders().getFirst("Authorization");
            reply(exchange, 200, "{\"access_token\":\"access-secret\",\"token_type\":\"Bearer\",\"expires_in\":60,\"refresh_token\":\"refresh-secret\"}");
        });
        server.start();
        var configuration = configuration("client_credentials", "/token").put("clientAuthentication", "client_secret_basic");
        try (var client = new OAuthTokenClient(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))) {
            var session = client.clientCredentials(configuration, Duration.ofSeconds(2));
            assertThat(session.path("accessToken").asString()).isEqualTo("access-secret");
            assertThat(session.path("refreshToken").asString()).isEqualTo("refresh-secret");
            assertThat(session.path("tokenType").asString()).isEqualTo("Bearer");
            assertThat(session.path("expiresAt").asText()).isEqualTo("2026-01-01T00:01:00Z");
        }
        assertThat(seenAuthorization[0]).startsWith("Basic ");
    }

    @Test
    void refreshOmissionPreservesPriorRefreshTokenButExplicitEmptyIsMalformed() throws Exception {
        var responses = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            if (responses.getAndIncrement() == 0) {
                reply(exchange, 200, "{\"access_token\":\"new-access\",\"token_type\":\"bearer\"}");
            } else {
                reply(exchange, 200, "{\"access_token\":\"new-access\",\"token_type\":\"bearer\",\"refresh_token\":\"\"}");
            }
        });

        server.start();
        var configuration = configuration("authorization_code", "/token").put("clientAuthentication", "none");
        var previous = JSON.createObjectNode().put("accessToken", "old-access").put("refreshToken", "old-refresh")
                .put("issuedAt", "2026-01-01T00:00:00Z");
        try (var client = new OAuthTokenClient(Clock.systemUTC())) {
            assertThat(client.refresh(configuration, previous, Duration.ofSeconds(2)).path("refreshToken").asText()).isEqualTo("old-refresh");
            assertThatThrownBy(() -> client.refresh(configuration, previous, Duration.ofSeconds(2)))
                    .isInstanceOf(OAuthTokenClient.Failure.class)
                    .satisfies(error -> assertThat(((OAuthTokenClient.Failure) error).kind()).isEqualTo(com.unbi.engine.llm.spec.LlmFailure.Kind.RESPONSE_FORMAT));
        }
    }
    @Test
    void authorizationCodeSendsRedirectUriAndMandatoryPkceVerifier() throws Exception {
        var body = new String[1];
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            body[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(exchange, 200, "{\"access_token\":\"code-access\",\"token_type\":\"bearer\"}");
        });
        server.start();
        var configuration = configuration("authorization_code", "/token").put("clientAuthentication", "none");
        try (var client = new OAuthTokenClient()) {
            var session = client.authorizationCode(configuration, "one-time-code", "http://127.0.0.1/callback",
                    "pkce-verifier", Duration.ofSeconds(2));
            assertThat(session.path("accessToken").asText()).isEqualTo("code-access");
        }
        assertThat(body[0]).contains("code=one-time-code", "redirect_uri=http%3A%2F%2F127.0.0.1%2Fcallback",
                "code_verifier=pkce-verifier");
    }

    @Test
    void malformedAndNonBearerResponsesNeverExposeBodyOrToken() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> reply(exchange, 200,
                "{\"access_token\":\"distinctive-secret\",\"token_type\":\"DPoP\",\"description\":\"private-body\"}"));
        server.start();
        var configuration = configuration("client_credentials", "/token").put("clientAuthentication", "none");
        try (var client = new OAuthTokenClient()) {
            assertThatThrownBy(() -> client.clientCredentials(configuration, Duration.ofSeconds(2)))
                    .isInstanceOfSatisfying(OAuthTokenClient.Failure.class, failure -> {
                        assertThat(failure.status()).isZero();
                        assertThat(failure.getMessage()).doesNotContain("distinctive-secret", "private-body");
                        assertThat(failure.code()).isEqualTo("unsupported_token_type");
                    });
        }
    }

    @Test
    void deviceAuthorizationPollsPendingAndSlowDownWithoutExposingDeviceCode() throws Exception {
        var polls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/device", exchange -> reply(exchange, 200,
                "{\"device_code\":\"private-device-code\",\"user_code\":\"ABCD\",\"verification_uri\":\"" + url("/verify") + "\",\"expires_in\":120,\"interval\":1}"));
        server.createContext("/verify", exchange -> reply(exchange, 200, "ok"));
        server.createContext("/token", exchange -> {
            int attempt = polls.getAndIncrement();
            if (attempt == 0) reply(exchange, 400, "{\"error\":\"authorization_pending\"}");
            else if (attempt == 1) reply(exchange, 400, "{\"error\":\"slow_down\"}");
            else reply(exchange, 200, "{\"access_token\":\"device-access\",\"token_type\":\"bearer\"}");
        });
        server.start();
        var configuration = configuration("device_authorization", "/token")
                .put("deviceAuthorizationUrl", url("/device")).put("clientAuthentication", "none");
        var waits = new java.util.ArrayList<Duration>();
        var sleeper = (OAuthTokenClient.Sleeper) (duration, cancelled) -> waits.add(duration);
        try (var client = new OAuthTokenClient(Clock.systemUTC(), sleeper)) {
            var challenge = client.deviceAuthorization(configuration, Duration.ofSeconds(2));
            assertThat(challenge.toString()).doesNotContain("private-device-code");
            assertThat(client.pollDevice(configuration, challenge, () -> false).path("accessToken").asText()).isEqualTo("device-access");
        }
        assertThat(polls.get()).isEqualTo(3);
        assertThat(waits).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(6));
    }

    @Test
    void devicePollingCancellationIsClassifiedAndDoesNotSendARequest() throws Exception {
        var calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> { calls.incrementAndGet(); reply(exchange, 200, "{}"); });
        server.start();
        var configuration = configuration("device_authorization", "/token").put("clientAuthentication", "none");
        var challenge = new OAuthTokenClient.DeviceAuthorization("private-device-code", "ABCD", url("/verify"), null,
                Instant.now().plusSeconds(30), Duration.ofSeconds(1));
        var cancelled = new AtomicBoolean(true);
        try (var client = new OAuthTokenClient()) {
            assertThatThrownBy(() -> client.pollDevice(configuration, challenge, cancelled::get))
                    .isInstanceOfSatisfying(OAuthTokenClient.Failure.class, failure -> assertThat(failure.kind()).isEqualTo(com.unbi.engine.llm.spec.LlmFailure.Kind.CANCELLED));
        }
        assertThat(calls.get()).isZero();
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void tokenDeadlineClosesAStalledBodyWithoutReplayingThePost() throws Exception {
        var calls = new AtomicInteger();
        var closed = new java.util.concurrent.CountDownLatch(1);
        var finish = new AtomicBoolean();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write('{');
                while (!finish.get()) {
                    exchange.getResponseBody().write(' ');
                    exchange.getResponseBody().flush();
                    Thread.sleep(5);
                }
            } catch (java.io.IOException disconnected) { closed.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var client = new OAuthTokenClient()) {
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.clientCredentials(configuration("client_credentials", "/token"), Duration.ofMillis(150)))
                    .isInstanceOfSatisfying(OAuthTokenClient.Failure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(com.unbi.engine.llm.spec.LlmFailure.Kind.TIMEOUT));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(closed.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(calls).hasValue(1);
        } finally { finish.set(true); }
    }

    private ObjectNode configuration(String grant, String tokenPath) {
        return JSON.createObjectNode().put("grantType", grant).put("clientId", "client-id")
                .put("clientSecret", "client-secret").put("clientAuthentication", "client_secret_post")
                .put("tokenUrl", url(tokenPath)).put("authorizationUrl", url("/authorize"));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
