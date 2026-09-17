package com.unbi.engine.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.unbi.engine.llm.auth.RequestAuthorization;
import com.unbi.engine.llm.spec.LlmFailure;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpTransportTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancellationClosesABodyThatNeverFinishes(boolean streaming) throws Exception {
        var headers = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            headers.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var transport = new HttpTransport(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cancelled = new AtomicBoolean();
            var future = executor.submit(() -> {
                if (streaming) {
                    transport.postStreaming(RequestAuthorization.unauthenticated(url(server)), new tools.jackson.databind.ObjectMapper().createObjectNode(),
                            Duration.ofSeconds(10), event -> false, cancelled::get);
                    return null;
                }
                return transport.get(RequestAuthorization.unauthenticated(url(server)), Duration.ofSeconds(10), cancelled::get);
            });
            assertThat(headers.await(2, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(LlmFailure.class)
                    .satisfies(failure -> assertThat(((LlmFailure) failure.getCause()).kind()).isEqualTo(LlmFailure.Kind.CANCELLED));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test
    void deadlineCoversBodyNotJustResponseHeaders() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var transport = new HttpTransport()) {
            assertThatThrownBy(() -> transport.get(RequestAuthorization.unauthenticated(url(server)), Duration.ofMillis(250), () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.TIMEOUT));
        } finally { release.countDown(); server.stop(0); }
    }

    @Test
    void redirectsNeverForwardTheCredential() throws Exception {
        var leaked = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", url(server) + "secret");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/secret", exchange -> {
            leaked.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (var transport = new HttpTransport()) {
            var endpoint = com.unbi.engine.nodes.llm.EndpointProfiles.build(new com.unbi.engine.core.node.ValueContext(
                    Map.of("baseUrl", url(server), "gateway", "custom", "auth", "bearer")));
            var authorization = RequestAuthorization.forEndpoint(endpoint,
                    com.unbi.engine.llm.auth.Credential.bearer("test", "distinctive-secret"), "/redirect");
            assertThatThrownBy(() -> transport.get(authorization,
                    Duration.ofSeconds(2), () -> false)).isInstanceOfSatisfying(LlmFailure.class,
                    failure -> assertThat(failure.status()).isEqualTo(302));
            assertThat(leaked.get()).isZero();
        } finally { server.stop(0); }
    }

    @Test
    void queryCredentialIsAbsentFromTransportFailures() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var secret = "distinctive/secret";
        server.createContext("/", exchange -> {
            var bytes = ("{\"error\":{\"message\":\"" + secret + "\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var transport = new HttpTransport()) {
            var endpoint = com.unbi.engine.nodes.llm.EndpointProfiles.build(new com.unbi.engine.core.node.ValueContext(
                    Map.of("baseUrl", url(server), "gateway", "custom", "auth", "api_key",
                            "apiKeyLocation", "query", "apiKeyName", "key")));
            var authorization = RequestAuthorization.forEndpoint(endpoint,
                    com.unbi.engine.llm.auth.Credential.bearer("test", secret), "/");
            assertThatThrownBy(() -> transport.get(authorization, Duration.ofSeconds(2), () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> {
                        assertThat(failure.status()).isEqualTo(401);
                        assertThat(failure.describe() + failure.target() + failure.toString())
                                .doesNotContain(secret, "distinctive%2Fsecret");
                        assertThat(failure.getCause()).isNull();
                    });
        } finally { server.stop(0); }
    }

    @Test
    void modelCatalogMayBeAJsonArray() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var bytes = "[{\"id\":\"array-model\"}]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try (var transport = new HttpTransport()) {
            var body = transport.get(RequestAuthorization.unauthenticated(url(server)), Duration.ofSeconds(2), () -> false);
            assertThat(body.path(0).path("id").asString()).isEqualTo("array-model");
        } finally { server.stop(0); }
    }

    private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/"; }
}
