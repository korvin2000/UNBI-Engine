package com.unbi.engine.llm.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.SequencedSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The credential the {@code codex} CLI has already obtained, under the reference {@code codex}.
 *
 * <p>Codex signs in through a browser OAuth flow whose client id, authorize and token endpoints are
 * not published API. Rather than guess them — and be wrong quietly, in an authentication path, where
 * being wrong looks like "the model is down" — this reads the credentials that flow already wrote
 * and that the CLI refreshes while it is in use:
 *
 * <ol>
 *   <li>{@code CODEX_AUTH_JSON}, the variable Codex itself documents for CI, or
 *   <li>{@code $CODEX_HOME/auth.json}, else {@code ~/.codex/auth.json}.
 * </ol>
 *
 * <p>What comes out is a bearer token plus the {@code chatgpt-account-id} the Codex backend requires
 * alongside it. An expired token is reported as such, with the remedy — {@code codex login} — rather
 * than as a 401 from a URL the user never typed.
 *
 * <p>This is the narrow, verifiable half of "support Codex OAuth". The other half, a first-party
 * device-code or PKCE flow, is a second {@link CredentialSource} and changes nothing else.
 */
@Component
public class CodexCredentials implements CredentialSource {

    public static final String REF = "codex";
    public static final String ACCOUNT_HEADER = "chatgpt-account-id";

    private static final Logger log = LoggerFactory.getLogger(CodexCredentials.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String inlineJson;
    private final Path authFile;

    public CodexCredentials() {
        this(System.getenv("CODEX_AUTH_JSON"), defaultAuthFile());
    }

    public CodexCredentials(String inlineJson, Path authFile) {
        this.inlineJson = inlineJson;
        this.authFile = authFile;
    }

    @Override
    public String id() {
        return "codex";
    }

    @Override
    public int order() {
        // Ahead of the generic sources: "codex" names one specific thing, and an environment
        // variable that happens to share the name should not shadow the real credential.
        return 5;
    }

    @Override
    public Optional<Credential> find(String ref) {
        if (!REF.equalsIgnoreCase(ref == null ? "" : ref.trim())) {
            return Optional.empty();
        }
        return read().map(this::toCredential);
    }

    @Override
    public SequencedSet<String> names() {
        var found = new LinkedHashSet<String>();
        if (read().isPresent()) {
            found.add(REF);
        }
        return found;
    }

    /** Where this looked, for the message shown when nothing was found. */
    public String describeLocation() {
        return inlineJson != null && !inlineJson.isBlank()
                ? "the CODEX_AUTH_JSON environment variable"
                : String.valueOf(authFile);
    }

    private Credential toCredential(JsonNode root) {
        var tokens = root.path("tokens");
        var accessToken = tokens.path("access_token").asString("");
        var accountId = firstNonBlank(
                tokens.path("account_id").asString(""),
                root.path("account_id").asString(""));

        var headers = new LinkedHashMap<String, String>();
        if (!accountId.isBlank()) {
            headers.put(ACCOUNT_HEADER, accountId);
        }
        // Both headers are what the Codex backend expects beside the token; sending the token
        // without them is a 403 that reads like a revoked credential.
        headers.put("originator", "codex_cli_rs");
        headers.put("OpenAI-Beta", "responses=experimental");
        return new Credential(REF, accessToken, headers);
    }

    private Optional<JsonNode> read() {
        var json = inlineJson != null && !inlineJson.isBlank() ? inlineJson : readFile();
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            var root = mapper.readTree(json);
            return root.path("tokens").path("access_token").asString("").isBlank()
                    ? Optional.empty()
                    : Optional.of(root);
        } catch (RuntimeException malformed) {
            log.warn("Codex credentials at {} could not be parsed: {}",
                    describeLocation(), malformed.getMessage());
            return Optional.empty();
        }
    }

    private String readFile() {
        if (authFile == null || !Files.isRegularFile(authFile)) {
            return null;
        }
        try {
            return Files.readString(authFile, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            log.warn("Could not read {}: {}", authFile, unreadable.getMessage());
            return null;
        }
    }

    private static Path defaultAuthFile() {
        var home = System.getenv("CODEX_HOME");
        var base = home == null || home.isBlank()
                ? Path.of(System.getProperty("user.home"), ".codex")
                : Path.of(home);
        return base.resolve("auth.json");
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}
