package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.settings.bundle.BundleReader;
import com.unbi.engine.settings.bundle.ConflictPolicy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CredentialsSectionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void backupTransfersDefinitionsButNeverRotatingSessions(@TempDir Path home) throws Exception {
        var sourceData = new DataDirectory(home.resolve("source"));
        var targetData = new DataDirectory(home.resolve("target"));
        var source = new ManagedCredentialStore(sourceData);
        var target = new ManagedCredentialStore(targetData);
        var config = JSON.createObjectNode().put("resourceBaseUrl", "https://gateway.example/v1")
                .put("grantType", "authorization_code").put("clientId", "registered-client")
                .put("clientSecret", "client-secret").put("clientAuthentication", "client_secret_basic")
                .put("authorizationUrl", "https://issuer.example/authorize").put("tokenUrl", "https://issuer.example/token");
        var created = source.create("gateway", ManagedCredentialStore.Type.OAUTH2, config);
        source.renew(created, Duration.ofSeconds(2), () -> false,
                (entry, root) -> JSON.createObjectNode().put("accessToken", "distinctive-access-token")
                        .put("refreshToken", "distinctive-refresh-token"));
        var entries = new LinkedHashMap<String, byte[]>();
        section(sourceData, source).export(entries::put);
        assertThat(entries.values().stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList())
                .allSatisfy(text -> assertThat(text).doesNotContain("distinctive-access-token", "distinctive-refresh-token"));
        var report = section(targetData, target).importFrom(reader(entries), ConflictPolicy.REPLACE);
        assertThat(report.problems()).isEmpty();
        assertThat(report.imported()).isEqualTo(1);
        var imported = target.find("gateway").orElseThrow();
        assertThat(imported.session().hasNonNull("accessToken")).isFalse();
        assertThat(imported.session().hasNonNull("refreshToken")).isFalse();
        assertThat(imported.configuration().path("clientSecret").asString()).isEqualTo("client-secret");
    }

    @Test
    void replacingDefinitionInvalidatesExistingSession(@TempDir Path home) throws Exception {
        var data = new DataDirectory(home);
        var managed = new ManagedCredentialStore(data);
        var config = JSON.createObjectNode().put("resourceBaseUrl", "https://gateway.example/v1");
        var created = managed.create("chatgpt", ManagedCredentialStore.Type.CODEX, config);
        managed.renew(created, Duration.ofSeconds(2), () -> false,
                (entry, root) -> JSON.createObjectNode().put("accessToken", "must-not-survive-import"));
        var section = section(data, managed);
        var entries = new LinkedHashMap<String, byte[]>();
        section.export(entries::put);
        section.importFrom(reader(entries), ConflictPolicy.SKIP);
        assertThat(managed.find("chatgpt").orElseThrow().session().path("accessToken").asString()).isEqualTo("must-not-survive-import");
        section.importFrom(reader(entries), ConflictPolicy.KEEP_BOTH);
        assertThat(managed.names()).contains("chatgpt", "chatgpt-2");
        assertThat(managed.find("chatgpt-2").orElseThrow().session().hasNonNull("accessToken")).isFalse();
        section.importFrom(reader(entries), ConflictPolicy.REPLACE);
        assertThat(managed.find("chatgpt").orElseThrow().session().hasNonNull("accessToken")).isFalse();
    }

    @Test
    void refusesSessionSmugglingAndPreservesExistingCredential(@TempDir Path home) throws Exception {
        var data = new DataDirectory(home);
        var managed = new ManagedCredentialStore(data);
        var file = new PropertiesFileCredentials(data);
        file.store("gateway", "keep-this-key");
        var section = new CredentialsSection(file, managed, new CredentialStore(List.of(file)));
        var imported = JSON.createObjectNode().put("version", 1).put("name", "gateway").put("type", "oauth2");
        imported.putObject("configuration").put("resourceBaseUrl", "https://gateway.example/v1");
        imported.putObject("session").put("refreshToken", "stolen-refresh-token");
        var report = section.importFrom(reader(Map.of("credentials/definitions/gateway.json", JSON.writeValueAsBytes(imported))), ConflictPolicy.REPLACE);
        assertThat(report.imported()).isZero();
        assertThat(report.replaced()).isZero();
        assertThat(report.problems()).hasSize(1).allSatisfy(error -> assertThat(error).doesNotContain("stolen-refresh-token"));
        assertThat(file.find("gateway").orElseThrow().token()).isEqualTo("keep-this-key");
    }

    private static CredentialsSection section(DataDirectory data, ManagedCredentialStore managed) {
        var file = new PropertiesFileCredentials(data);
        return new CredentialsSection(file, managed, new CredentialStore(List.of(file)));
    }

    private static BundleReader reader(Map<String, byte[]> entries) {
        return new BundleReader() {
            @Override public List<String> entries(String prefix) { return entries.keySet().stream().filter(key -> key.startsWith(prefix)).toList(); }
            @Override public byte[] read(String path) { return entries.get(path); }
        };
    }
}
