package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.RatePolicy;
import com.unbi.engine.llm.spec.TokenUsage;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RequestAuthorizationTest {
    @Test
    void sendsAnApiKeyInTheConfiguredHeaderAndCredentialHeadersWinCaseInsensitively() throws Exception {
        var endpoint = endpoint(EndpointSpec.AuthScheme.API_KEY, Map.of("X-Route", "endpoint"), "header", "X-Api-Key");
        var credential = new Credential("gateway", "header-secret", Map.of("x-route", "credential"));
        var authorization = RequestAuthorization.forEndpoint(endpoint, credential, "/probe");
        var request = send(authorization);

        assertThat(request.apiKey()).isEqualTo("header-secret");
        assertThat(request.route()).isEqualTo("credential");
    }

    @Test
    void sendsAnApiKeyInTheQueryWithRfc3986Encoding() throws Exception {
        var endpoint = endpoint(EndpointSpec.AuthScheme.API_KEY, Map.of(), "query", "api_key");
        var authorization = RequestAuthorization.forEndpoint(endpoint,
                Credential.bearer("gateway", "a b+c/ü"), "/probe");
        var request = send(authorization);

        assertThat(request.uri().getRawQuery()).isEqualTo("api_key=a%20b%2Bc%2F%C3%BC");
        assertThat(authorization.safeUrl()).doesNotContain("a%20b%2Bc%2F%C3%BC").contains("<redacted>");
    }

    @Test
    void sendsAnApiKeyAsCookie() throws Exception {
        var endpoint = endpoint(EndpointSpec.AuthScheme.API_KEY, Map.of(), "cookie", "session_key");
        var request = send(RequestAuthorization.forEndpoint(endpoint,
                Credential.bearer("gateway", "cookie-secret"), "/probe"));

        assertThat(request.cookie()).isEqualTo("session_key=cookie-secret");
    }

    @Test
    void sendsBasicCredentialsUsingUtf8AndTheFirstColon() throws Exception {
        var endpoint = endpoint(EndpointSpec.AuthScheme.BASIC, Map.of(), "header", "unused");
        var credential = Credential.bearer("basic", "münchen:päs:sword");
        var authorization = RequestAuthorization.forEndpoint(endpoint, credential, "/probe");
        var request = send(authorization);

        var expected = Base64.getEncoder().encodeToString(credential.token().getBytes(StandardCharsets.UTF_8));
        assertThat(request.authorization()).isEqualTo("Basic " + expected);
        assertThat(authorization.redact(expected + " Basic " + expected)).doesNotContain(expected);
    }

    @Test
    void sendsBearerAndCodexTokensAsBearerAuthorization() throws Exception {
        for (var auth : new EndpointSpec.AuthScheme[] {
            EndpointSpec.AuthScheme.BEARER, EndpointSpec.AuthScheme.CODEX, EndpointSpec.AuthScheme.OAUTH2
        }) {
            var endpoint = endpoint(auth, Map.of(), "header", "unused");
            var authorization = RequestAuthorization.forEndpoint(endpoint,
                    Credential.bearer("gateway", "token-" + auth.wireName()), "/probe");
            var request = send(authorization);
            assertThat(request.authorization()).isEqualTo("Bearer token-" + auth.wireName());
        }
    }

    @Test
    void noneAndNullCredentialDoNotAddAuthorization() throws Exception {
        var endpoint = endpoint(EndpointSpec.AuthScheme.NONE, Map.of("X-Route", "public"), "header", "unused");
        var authorization = RequestAuthorization.forEndpoint(endpoint, Credential.bearer("ignored", "secret"), "/probe");
        var request = send(authorization);

        assertThat(request.authorization()).isNull();
        assertThat(request.route()).isEqualTo("public");
        assertThat(RequestAuthorization.forEndpoint(endpoint, null, "/probe").headers())
                .doesNotContainKey("Authorization");
    }

    @Test
    void rejectsSecretBearingExtraHeadersAndUnsafePlacementValues() {
        assertThatThrownBy(() -> endpoint(EndpointSpec.AuthScheme.BEARER,
                Map.of("Authorization", "saved-secret"), "header", "unused"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> endpoint(EndpointSpec.AuthScheme.BEARER,
                Map.of("X-Route", "bad\nvalue"), "header", "unused"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> endpoint(EndpointSpec.AuthScheme.API_KEY,
                Map.of(), "query", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> endpoint(EndpointSpec.AuthScheme.API_KEY,
                Map.of(), "cookie", "bad;name"))
                .isInstanceOf(IllegalArgumentException.class);
        var cookieEndpoint = endpoint(EndpointSpec.AuthScheme.API_KEY, Map.of(), "cookie", "key");
        assertThatThrownBy(() -> RequestAuthorization.forEndpoint(cookieEndpoint,
                Credential.bearer("key", "bad;cookie"), "/probe"))
                .isInstanceOf(IllegalArgumentException.class);
        var queryEndpoint = endpoint(EndpointSpec.AuthScheme.API_KEY, Map.of(), "query", "key");
        assertThatThrownBy(() -> RequestAuthorization.forEndpoint(queryEndpoint,
                Credential.bearer("key", "bad\nsecret"), "/probe"))
                .isInstanceOf(IllegalArgumentException.class);
        var basicEndpoint = endpoint(EndpointSpec.AuthScheme.BASIC, Map.of(), "header", "unused");
        assertThatThrownBy(() -> RequestAuthorization.forEndpoint(basicEndpoint,
                Credential.bearer("key", "user\n:password"), "/probe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> endpoint(EndpointSpec.AuthScheme.BEARER,
                Map.of("X-Route", "nul\0value"), "header", "unused"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redactsRawAndDerivedCredentialsAndNeverPrintsThem() {
        var endpoint = endpoint(EndpointSpec.AuthScheme.API_KEY, Map.of(), "query", "api_key");
        var authorization = RequestAuthorization.forEndpoint(endpoint,
                Credential.bearer("gateway", "secret value"), "/probe");
        var diagnostic = "secret value secret%20value " + authorization.url();

        assertThat(authorization.redact(diagnostic)).doesNotContain("secret value", "secret%20value");
        assertThat(authorization.toString()).doesNotContain("secret");
        assertThat(authorization.safeUrl()).doesNotContain("secret");
    }

    private static HttpExchangeSnapshot send(RequestAuthorization authorization) throws Exception {
        var seen = new AtomicReference<HttpExchangeSnapshot>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/probe", exchange -> {
            seen.set(new HttpExchangeSnapshot(exchange.getRequestURI(), exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-Api-Key"), exchange.getRequestHeaders().getFirst("Cookie"),
                    exchange.getRequestHeaders().getFirst("X-Route")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            var requestBuilder = HttpRequest.newBuilder(URI.create(
                    authorization.url().replace("http://127.0.0.1:1", "http://127.0.0.1:" + server.getAddress().getPort())));
            authorization.headers().forEach(requestBuilder::header);
            try (var client = HttpClient.newHttpClient()) {
                client.send(requestBuilder.GET().build(), HttpResponse.BodyHandlers.discarding());
            }
            return seen.get();
        } finally {
            server.stop(0);
        }
    }

    private static EndpointSpec endpoint(
            EndpointSpec.AuthScheme auth, Map<String, String> headers, String location, String name) {
        var endpoint = new EndpointSpec("test", "custom", "http://127.0.0.1:1/v1", auth, "gateway", headers, RatePolicy.UNLIMITED, 5_000, false, TokenUsage.CachedTokenMode.INCLUDED, false, ApiFormat.CHAT_COMPLETIONS, EndpointSpec.ResponsesDialect.STANDARD, location, auth == EndpointSpec.AuthScheme.API_KEY ? name : "");
        RequestAuthorization.validateEndpoint(endpoint);
        return endpoint;
    }

    private record HttpExchangeSnapshot(
            URI uri, String authorization, String apiKey, String cookie, String route) {}
}
