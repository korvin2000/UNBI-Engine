package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.settings.EngineHome;
import com.unbi.engine.settings.SettingsStore;
import jakarta.servlet.http.Cookie;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class OAuthLoginSessionsTest {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    @Test
    void browserCodeExchangeProvesPkceAndExactRedirectWithoutPublicSecrets(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root, false)) {
            var owner = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            var started = fixture.logins.start("gateway", "browser", owner, response);
            var parameters = query(URI.create(started.path("authorizationUrl").asString()).getRawQuery());
            fixture.expectedChallenge.set(parameters.get("code_challenge"));
            assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
            assertThat(parameters.get("response_type")).isEqualTo("code");
            assertThat(parameters.get("tenant")).isEqualTo("fixture");
            assertThat(parameters.get("redirect_uri")).isEqualTo(fixture.logins.callbackUri("gateway"));
            assertThat(response.getHeader("Set-Cookie")).contains("HttpOnly", "SameSite=Lax", "Max-Age=600");
            fixture.logins.callback("gateway", parameters.get("state"), "code", null,
                    callback(fixture, owner, response), new MockHttpServletResponse());
            await(() -> fixture.logins.status("gateway", started.path("loginId").asString(), owner)
                    .path("status").asString().equals("ready"));
            assertThat(fixture.posts).hasValue(1);
            assertThat(fixture.store.find("gateway").orElseThrow().session().path("accessToken").asString()).isEqualTo("private-access");
            assertThat(started.toString() + fixture.logins.status("gateway", started.path("loginId").asString(), owner))
                    .doesNotContain("private-access", "private-refresh", "code_verifier");
        }
    }

    @Test
    void wrongSessionDeniedConsentAndReplayNeverExchangeACode(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root, false)) {
            var owner = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            var started = fixture.logins.start("gateway", "browser", owner, response);
            var state = query(URI.create(started.path("authorizationUrl").asString()).getRawQuery()).get("state");
            var other = new MockHttpServletRequest();
            other.getSession(true);
            assertThatThrownBy(() -> fixture.logins.callback("gateway", state, "code", null,
                    callback(fixture, other, response), new MockHttpServletResponse())).isInstanceOf(LlmFailure.class);
            var valid = callback(fixture, owner, response);
            fixture.logins.callback("gateway", state, null, "access_denied", valid, new MockHttpServletResponse());
            assertThatThrownBy(() -> fixture.logins.callback("gateway", state, "code", null, valid,
                    new MockHttpServletResponse())).isInstanceOf(LlmFailure.class);
            await(() -> fixture.logins.status("gateway", started.path("loginId").asString(), owner)
                    .path("status").asString().equals("error"));
            assertThat(fixture.posts).hasValue(0);
            assertThat(fixture.store.find("gateway").orElseThrow().session().hasNonNull("accessToken")).isFalse();
        }
    }

    @Test
    void expiryConsumesStateAndReleasesTheRelocationLease(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root, false)) {
            var owner = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            var started = fixture.logins.start("gateway", "browser", owner, response);
            assertThatThrownBy(() -> fixture.settings.relocate(SettingsStore.Target.DATA, root.resolve("elsewhere").toString(), false))
                    .isInstanceOf(SettingsStore.DataBusyException.class);
            fixture.clock.advance(Duration.ofMinutes(11));
            await(() -> fixture.logins.status("gateway", started.path("loginId").asString(), owner)
                    .path("status").asString().equals("error"));
            var state = query(URI.create(started.path("authorizationUrl").asString()).getRawQuery()).get("state");
            assertThatThrownBy(() -> fixture.logins.callback("gateway", state, "code", null,
                    callback(fixture, owner, response), new MockHttpServletResponse())).isInstanceOf(LlmFailure.class);
            await(() -> fixture.relocate(root.resolve("elsewhere")));
            assertThat(fixture.posts).hasValue(0);
        }
    }

    @Test
    void deviceCodeIsPrivateAndCancellationDoesNotCreateASession(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root, true)) {
            var owner = new MockHttpServletRequest();
            var started = fixture.logins.start("gateway", "device", owner, new MockHttpServletResponse());
            assertThat(started.toString()).doesNotContain("private-device");
            assertThat(started.path("userCode").asString()).isEqualTo("ABCD-EFGH");
            assertThat(started.path("verificationUrl").asString()).isEqualTo(fixture.base + "/verify");
            fixture.logins.cancel("gateway", started.path("loginId").asString(), owner);
            await(() -> fixture.logins.status("gateway", started.path("loginId").asString(), owner)
                    .path("status").asString().equals("error"));
            assertThat(fixture.store.find("gateway").orElseThrow().session().hasNonNull("accessToken")).isFalse();
            await(() -> fixture.relocate(root.resolve("elsewhere")));
        }
    }

    private static MockHttpServletRequest callback(Fixture fixture, MockHttpServletRequest owner, MockHttpServletResponse start) {
        var request = new MockHttpServletRequest();
        request.setSession(owner.getSession(false));
        request.setRequestURI(URI.create(fixture.logins.callbackUri("gateway")).getRawPath());
        var pair = start.getHeader("Set-Cookie").split(";", 2)[0].split("=", 2);
        request.setCookies(new Cookie(pair[0], pair[1]));
        return request;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static Map<String, String> query(String encoded) {
        var values = new LinkedHashMap<String, String>();
        for (var pair : encoded.split("&")) {
            var parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return values;
    }

    private static final class Fixture implements AutoCloseable {
        final MutableClock clock = new MutableClock();
        final SettingsStore settings;
        final ManagedCredentialStore store;
        final OAuthTokenClient tokens;
        final OAuthLoginSessions logins;
        final HttpServer server;
        final String base;
        final AtomicInteger posts = new AtomicInteger();
        final AtomicReference<String> expectedChallenge = new AtomicReference<>();

        Fixture(Path root, boolean device) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/device", exchange -> reply(exchange, 200, "{\"device_code\":\"private-device\",\"user_code\":\"ABCD-EFGH\","
                    + "\"verification_uri\":\"" + base + "/verify\",\"expires_in\":300,\"interval\":1}"));
            server.createContext("/token", exchange -> {
                posts.incrementAndGet();
                var form = query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (device) { reply(exchange, 400, "{\"error\":\"authorization_pending\"}"); return; }
                try {
                    var actual = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                            .digest(form.getOrDefault("code_verifier", "").getBytes(StandardCharsets.US_ASCII)));
                    if (!actual.equals(expectedChallenge.get()) || !"code".equals(form.get("code"))
                            || !"http://localhost:8081/api/credentials/oauth/callback/gateway".equals(form.get("redirect_uri"))) {
                        reply(exchange, 400, "{\"error\":\"invalid_grant\"}"); return;
                    }
                    reply(exchange, 200, "{\"access_token\":\"private-access\",\"refresh_token\":\"private-refresh\",\"token_type\":\"Bearer\",\"expires_in\":100}");
                } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            });
            server.start();
            settings = new SettingsStore(new EngineHome(root));
            var data = new DataDirectory(settings);
            store = new ManagedCredentialStore(data, clock);
            tokens = new OAuthTokenClient(clock);
            var config = JSON.objectNode().put("resourceBaseUrl", base + "/v1").put("grantType", device ? "device_authorization" : "authorization_code")
                    .put("clientId", "client").put("clientAuthentication", "none").put("tokenUrl", base + "/token")
                    .put("authorizationUrl", base + "/authorize?tenant=fixture&response_type=token").put("deviceAuthorizationUrl", base + "/device");
            store.create("gateway", ManagedCredentialStore.Type.OAUTH2, config);
            logins = new OAuthLoginSessions(store, tokens, new OAuthCredentials(store, tokens, clock), data, null, "http://localhost:8081", clock);
        }

        boolean relocate(Path destination) {
            try { settings.relocate(SettingsStore.Target.DATA, destination.toString(), false); return true; }
            catch (SettingsStore.DataBusyException pending) { return false; }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
        }
        @Override public void close() { logins.close(); tokens.close(); store.close(); server.stop(0); }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
