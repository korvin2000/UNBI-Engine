package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.settings.EngineHome;
import com.unbi.engine.settings.SettingsStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CodexCredentialsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final URI RESOURCE = URI.create("https://chatgpt.example/backend-api/codex");

    @Test
    void managedNativeSessionResolvesAndSurvivesStoreRestart(@TempDir Path root) throws Exception {
        try (var fixture = new Fixture(root, null)) {
            var created = fixture.store.create("codex", ManagedCredentialStore.Type.CODEX, configuration());
            fixture.store.commitSession("codex", created.revision(), nativeSession("access-one", "refresh-one", "acct-a"));

            var first = fixture.source.resolve("codex", RESOURCE, Duration.ofSeconds(2), () -> false).orElseThrow();
            assertThat(first.token()).isEqualTo("access-one");
            assertThat(first.headers()).containsEntry(CodexCredentials.ACCOUNT_HEADER, "acct-a");
            assertThat(fixture.store.find("codex").orElseThrow().toString())
                    .doesNotContain("access-one", "refresh-one", "acct-a");
            assertThat(fixture.source.status("codex").toString()).doesNotContain("access-one", "refresh-one", "acct-a");

            try (var restarted = new ManagedCredentialStore(fixture.data)) {
                var source = new CodexCredentials(restarted, null, Clock.systemUTC(), null, null);
                assertThat(source.resolve("codex", RESOURCE, Duration.ofSeconds(2), () -> false).orElseThrow().token())
                        .isEqualTo("access-one");
            }
        }
    }

    @Test
    void nativeRefreshUsesLocalOAuthAndPersistsRotatedSession(@TempDir Path root) throws Exception {
        var refreshes = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            refreshes.incrementAndGet();
            var body = "{\"access_token\":\"" + accessToken("acct-a", "new")
                    + "\",\"refresh_token\":\"refresh-two\",\"expires_in\":3600}";
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try (var client = new ChatGptOAuthClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        Clock.systemUTC(),
                        (duration, cancelled) -> {});
                var fixture = new Fixture(root, null, client)) {
            var created = fixture.store.create("codex", ManagedCredentialStore.Type.CODEX, configuration());
            fixture.store.commitSession("codex", created.revision(),
                    nativeSession(accessToken("acct-a", "old"), "refresh-one", "acct-a"));
            var rejected = fixture.source.resolve("codex", RESOURCE, Duration.ofSeconds(2), () -> false).orElseThrow();
            var refreshed = fixture.source.refresh("codex", rejected, RESOURCE, Duration.ofSeconds(2), () -> false).orElseThrow();

            assertThat(refreshes.get()).isEqualTo(1);
            assertThat(refreshed.token()).isEqualTo(accessToken("acct-a", "new"));
            assertThat(fixture.store.find("codex").orElseThrow().session().path("refreshToken").asString())
                    .isEqualTo("refresh-two");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void legacyReadySessionWithoutNativeTokensRequiresReconnectAndNeverUsesExternal(@TempDir Path root) throws Exception {
        var external = root.resolve("external-auth.json");
        Files.writeString(external,
                "{\"tokens\":{\"access_token\":\"external-token\",\"account_id\":\"external-account\"}}");
        try (var fixture = new Fixture(root, external)) {
            var created = fixture.store.create("codex", ManagedCredentialStore.Type.CODEX, configuration());
            fixture.store.commitSession("codex", created.revision(), JSON.createObjectNode().put("status", "ready")
                    .put("accountId", "old-account"));

            assertThat(fixture.source.find("codex")).isEmpty();
            assertThatThrownBy(() -> fixture.source.resolve("codex", RESOURCE, Duration.ofSeconds(2), () -> false))
                    .isInstanceOfSatisfying(LlmFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.AUTH));
            assertThat(fixture.source.status("codex").path("status").asString()).isEqualTo("reauth_required");
            assertThat(fixture.source.names()).contains("codex");
        }
    }

    @Test
    void explicitExternalCredentialsRemainReadOnly(@TempDir Path root) throws Exception {
        var external = root.resolve("external-auth.json");
        var original = "{\"tokens\":{\"access_token\":\"external-token\",\"account_id\":\"external-account\"}}";
        Files.writeString(external, original);
        var source = new CodexCredentials(null, external);
        assertThat(source.resolve("codex", RESOURCE, Duration.ofSeconds(2), () -> false).orElseThrow().token())
                .isEqualTo("external-token");
        assertThat(source.status("codex").path("status").asString()).isEqualTo("ready");
        assertThat(Files.readString(external)).isEqualTo(original);
    }

    private static ObjectNode nativeSession(String access, String refresh, String account) {
        var now = Instant.now();
        return JSON.createObjectNode().put("status", "ready").put("accessToken", access)
                .put("refreshToken", refresh).put("accountId", account)
                .put("issuedAt", now.toString()).put("expiresAt", now.plusSeconds(3600).toString());
    }

    private static String accessToken(String account, String marker) {
        var payload = JSON.createObjectNode();
        payload.putObject("https://api.openai.com/auth").put("chatgpt_account_id", account);
        payload.put("nonce", marker);
        return "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(JSON.writeValueAsBytes(payload)) + ".signature";
    }

    private static ObjectNode configuration() {
        return JSON.createObjectNode().put("resourceBaseUrl", RESOURCE.toString());
    }

    private static final class Fixture implements AutoCloseable {
        final SettingsStore settings;
        final DataDirectory data;
        final ManagedCredentialStore store;
        final CodexCredentials source;
        Fixture(Path root, Path external) {
            this(root, external, null);
        }

        Fixture(Path root, Path external, ChatGptOAuthClient nativeOAuth) {
            settings = new SettingsStore(new EngineHome(root));
            data = new DataDirectory(settings);
            store = new ManagedCredentialStore(data);
            source = new CodexCredentials(store, nativeOAuth, Clock.systemUTC(), null, external);
        }


        @Override public void close() { store.close(); }
    }
}
