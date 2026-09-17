package com.unbi.engine.llm.auth;

import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * ChatGPT subscription credentials. Managed sessions are native OAuth snapshots persisted by
 * {@link ManagedCredentialStore}; external Codex auth files remain read-only.
 */
@Component
public class CodexCredentials implements CredentialSource {
    public static final String REF = "codex";
    public static final String ACCOUNT_HEADER = "chatgpt-account-id";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final ManagedCredentialStore managed;
    private final ChatGptOAuthClient nativeOAuth;
    private final Clock clock;
    private final String inlineJson;
    private final Path externalFile;

    @Autowired
    public CodexCredentials(ManagedCredentialStore managed, ChatGptOAuthClient nativeOAuth,
            org.springframework.core.env.Environment ignoredEnvironment) {
        this(managed, nativeOAuth, Clock.systemUTC(), System.getenv("CODEX_AUTH_JSON"), defaultAuthFile());
    }

    /** External-source test seam: no managed store or native login is needed to inspect a file. */
    public CodexCredentials(String inlineJson, Path authFile) {
        this(null, null, Clock.systemUTC(), inlineJson, authFile);
    }

    /** Native OAuth test seam. */
    public CodexCredentials(ManagedCredentialStore managed, ChatGptOAuthClient nativeOAuth,
            Clock clock, String inlineJson, Path externalFile) {
        this.managed = managed;
        this.nativeOAuth = nativeOAuth;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.inlineJson = inlineJson;
        this.externalFile = externalFile;
    }

    @Override public String id() { return "codex"; }
    @Override public int order() { return 5; }

    @Override
    public Optional<Credential> find(String ref) {
        var entry = entry(ref);
        if (entry.isPresent()) {
            if (entry.get().type() != ManagedCredentialStore.Type.CODEX) return Optional.empty();
            return nativeToken(entry.get()).map(Token::credential);
        }
        return REF.equalsIgnoreCase(ref == null ? "" : ref.trim()) ? external().map(Token::credential) : Optional.empty();
    }

    @Override
    public SequencedSet<String> names() {
        var names = new LinkedHashSet<String>();
        var owned = managed == null ? new LinkedHashSet<String>() : managed.names();
        for (var name : owned) {
            try {
                if (managed.find(name).orElseThrow().type() == ManagedCredentialStore.Type.CODEX) names.add(name);
            } catch (LlmFailure malformed) {
                // An owned malformed record must remain visible so another source cannot answer it.
                if (name.equals(REF)) names.add(name);
            }
        }
        if (!owned.contains(REF) && external().isPresent()) names.add(REF);
        return names;
    }

    @Override
    public void validate(String ref, EndpointSpec endpoint) {
        rejectPlatform(URI.create(endpoint.baseUrl()));
        var entry = entry(ref);
        if (entry.isPresent()) {
            if (entry.get().type() != ManagedCredentialStore.Type.CODEX
                    || endpoint.authScheme() != EndpointSpec.AuthScheme.CODEX) {
                throw auth("Credential type does not match endpoint authentication");
            }
            ManagedCredentialStore.validateResource(entry.get(), URI.create(endpoint.baseUrl()));
        }
    }

    @Override
    public Optional<Credential> resolve(String ref, URI resource, Duration timeout, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        rejectPlatform(resource);
        var entry = entry(ref);
        if (entry.isEmpty()) {
            if (!REF.equalsIgnoreCase(ref)) return Optional.empty();
            var external = external().orElseThrow(() -> auth("External Codex credentials are unavailable; use Connect in UNBI"));
            if (expired(external)) throw auth("External Codex credentials expired; run codex login or Connect in UNBI");
            return Optional.of(external.credential());
        }
        var current = entry.get();
        if (current.type() != ManagedCredentialStore.Type.CODEX) throw auth("Credential type does not match endpoint authentication");
        ManagedCredentialStore.validateResource(current, resource);
        var token = nativeToken(current).orElseThrow(() -> auth("Connect this ChatGPT credential in UNBI before running it"));
        if (due(current, token)) {
            try {
                current = renew(current, null, timeout, cancelled);
                token = nativeToken(current).orElseThrow(() -> auth("Codex did not renew the expired session; reconnect in UNBI"));
            } catch (LlmFailure transientFailure) {
                // A proactive exchange may fail transiently. The old snapshot is safe only while
                // its independently-known expiry is still in the future.
                if (!transientFailure(transientFailure) || expired(token)) throw transientFailure;
            }
        }
        if (expired(token)) throw auth("Codex did not renew the expired session; reconnect in UNBI");
        return Optional.of(token.credential());
    }

    @Override
    public Optional<Credential> refresh(String ref, Credential rejected, URI resource, Duration timeout, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        rejectPlatform(resource);
        var entry = entry(ref);
        if (entry.isEmpty()) return Optional.empty();
        var current = entry.get();
        if (current.type() != ManagedCredentialStore.Type.CODEX) throw auth("Credential type does not match endpoint authentication");
        ManagedCredentialStore.validateResource(current, resource);
        var token = nativeToken(current).orElseThrow(() -> auth("Reconnect this ChatGPT credential in UNBI"));
        if (rejected != null && !rejected.headers().getOrDefault(ACCOUNT_HEADER, "").equals(token.accountId())) {
            throw auth("The ChatGPT account changed during the request; start a new request");
        }
        if (rejected == null || !token.credential().token().equals(rejected.token())) {
            if (expired(token)) throw auth("Codex session expired; reconnect in UNBI");
            return Optional.of(token.credential());
        }
        current = renew(current, rejected, timeout, cancelled);
        token = nativeToken(current).orElseThrow(() -> auth("Codex did not return a usable renewed session"));
        if (expired(token)) throw auth("Codex did not return a usable renewed session");
        return Optional.of(token.credential());
    }

    private ManagedCredentialStore.Entry renew(ManagedCredentialStore.Entry entry, Credential rejected,
            Duration timeout, BooleanSupplier cancelled) {
        if (nativeOAuth == null) throw auth("ChatGPT sign-in is unavailable; Connect in UNBI");
        return managed.renew(entry, timeout, cancelled, (current, ignoredRoot) -> {
            // The shared renewal worker, not an individual waiter, owns cancellation of the
            // native exchange. A cancelled waiter must not abort another caller's refresh.
            BooleanSupplier workerCancelled = () -> Thread.currentThread().isInterrupted();
            checkCancelled(workerCancelled);
            var existing = nativeToken(current).orElseThrow(() -> auth("Reconnect this ChatGPT credential in UNBI"));
            var replacement = nativeOAuth.refresh(current.session(), workerCancelled);
            var token = tokenFromSession(current.name(), replacement);
            if (!token.accountId().equals(existing.accountId())) {
                throw auth("The ChatGPT account changed during renewal; reconnect it");
            }
            if (rejected != null && rejected.token().equals(token.credential().token())) {
                throw auth("Codex did not return a new session; reconnect in UNBI");
            }
            if (expired(token)) throw auth("Codex did not return a usable renewed session");
            return session(replacement, token.accountId(), current.session().path("refreshToken").asString(""));
        });
    }

    /** A takeover is held only in the pending login; external name is not shadowed until success. */
    public ManagedCredentialStore.Entry takeover(String name, ObjectNode configuration) {
        if (!REF.equals(name) || entry(name).isPresent() || external().isEmpty()) {
            throw auth("Only an existing external Codex connection can be taken over");
        }
        var draft = new ManagedCredentialStore.Entry(name, ManagedCredentialStore.Type.CODEX,
                UUID.randomUUID().toString(), configuration, JSON.createObjectNode());
        var resource = URI.create(configuration.path("resourceBaseUrl").asString(""));
        rejectPlatform(resource);
        ManagedCredentialStore.validateResource(draft, resource);
        return draft;
    }

    /** Establishes a managed native OAuth session and publishes only a safe pending projection. */
    public ManagedCredentialStore.Entry login(ManagedCredentialStore.Entry entry, boolean create, String mode,
            Consumer<ObjectNode> started, BooleanSupplier cancelled) {
        if (nativeOAuth == null) throw auth("ChatGPT sign-in is unavailable; Connect in UNBI");
        if (entry.type() != ManagedCredentialStore.Type.CODEX) throw auth("Not a ChatGPT connection");
        if (!"browser".equals(mode) && !"device".equals(mode)) throw auth("Unsupported ChatGPT sign-in mode");
        return managed.establish(entry, create, Duration.ofMinutes(10), cancelled, (current, ignoredRoot) -> {
            rejectPlatform(URI.create(current.configuration().path("resourceBaseUrl").asString("")));
            var session = nativeOAuth.login(mode, visible -> {
                var safe = visible == null ? JSON.createObjectNode() : visible.deepCopy();
                safe.put("status", "pending");
                started.accept(safe);
            }, cancelled);
            var token = tokenFromSession(current.name(), session);
            if (expired(token)) throw auth("ChatGPT returned an expired session; reconnect");
            return session(session, token.accountId(), "");
        });
    }

    /** Clears the managed session only; external CLI auth is never modified. */
    public void logout(String name) {
        var entry = entry(name).orElseThrow(() -> auth("External Codex credentials are read-only"));
        if (entry.type() != ManagedCredentialStore.Type.CODEX) throw auth("Not a managed ChatGPT credential");
        managed.logout(name);
    }

    public ObjectNode status(String name) {
        var entry = entry(name);
        var result = JSON.createObjectNode().put("type", entry.isPresent() ? "codex" : "codex_external")
                .put("renewable", entry.isPresent());
        if (entry.isEmpty()) {
            var token = external();
            result.put("status", token.isEmpty() || expired(token.get()) ? "reauth_required" : "ready")
                    .put("message", "Externally managed: renew with your Codex CLI, or Connect in UNBI for automatic renewal");
            if (token.isPresent() && token.get().expiresAt() != null) result.put("expiresAt", token.get().expiresAt().toString());
            else result.putNull("expiresAt");
            return result;
        }
        var state = entry.get().session();
        var token = nativeToken(entry.get());
        if (token.isPresent()) {
            result.put("status", "ready");
            if (token.get().expiresAt() != null) result.put("expiresAt", token.get().expiresAt().toString());
            else result.putNull("expiresAt");
            return result;
        }
        var persistedStatus = state.path("status").asString("unconfigured");
        result.put("status", "ready".equals(persistedStatus) ? "reauth_required" : persistedStatus);
        if (state.has("expiresAt") && !state.path("expiresAt").isNull()) result.set("expiresAt", state.path("expiresAt"));
        else result.putNull("expiresAt");
        return result;
    }

    public String describeLocation() {
        return inlineJson != null && !inlineJson.isBlank() ? "CODEX_AUTH_JSON (externally managed)"
                : String.valueOf(externalFile) + " (externally managed)";
    }

    private Optional<ManagedCredentialStore.Entry> entry(String ref) {
        if (managed == null || ref == null || !ref.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,60}")) return Optional.empty();
        var exact = managed.find(ref);
        if (exact.isPresent() || ref.equals(REF) || !ref.equalsIgnoreCase(REF)) return exact;
        return managed.find(REF);
    }

    private Optional<Token> nativeToken(ManagedCredentialStore.Entry entry) {
        if (entry.type() != ManagedCredentialStore.Type.CODEX) return Optional.empty();
        try {
            var state = entry.session();
            if (!"ready".equals(state.path("status").asString())) return Optional.empty();
            return Optional.of(tokenFromSession(entry.name(), state));
        } catch (RuntimeException invalid) {
            return Optional.empty();
        }
    }

    private static Token tokenFromSession(String name, JsonNode session) {
        if (session == null || !session.isObject() || !"ready".equals(session.path("status").asString())) {
            throw auth("ChatGPT login did not return a ready session");
        }
        var access = session.path("accessToken").asString("");
        var account = session.path("accountId").asString("");
        var refresh = session.path("refreshToken").asString("");
        if (access.isBlank() || account.isBlank() || refresh.isBlank()
                || !safe(access) || !safe(refresh) || !safe(account)) {
            throw auth("ChatGPT login did not return a usable session");
        }
        Instant expires = null;
        var expiresNode = session.path("expiresAt");
        if (!expiresNode.isMissingNode() && !expiresNode.isNull()) {
            try { expires = Instant.parse(expiresNode.asString()); }
            catch (RuntimeException invalid) { throw auth("ChatGPT login returned an invalid expiry"); }
        }
        var headers = new LinkedHashMap<String, String>();
        headers.put(ACCOUNT_HEADER, account);
        headers.put("originator", "codex_cli_rs");
        headers.put("OpenAI-Beta", "responses=experimental");
        return new Token(new Credential(name, access, headers), account, expires);
    }

    private ObjectNode session(JsonNode source, String accountId, String previousRefreshToken) {
        var result = JSON.createObjectNode().put("status", "ready");
        result.put("accessToken", source.path("accessToken").asString());
        if (source.has("refreshToken") && !source.path("refreshToken").isNull()
                && !source.path("refreshToken").asString("").isBlank()) result.put("refreshToken", source.path("refreshToken").asString());
        else if (!previousRefreshToken.isBlank()) result.put("refreshToken", previousRefreshToken);
        result.put("accountId", accountId);
        var issued = source.path("issuedAt").asString("");
        result.put("issuedAt", issued.isBlank() ? clock.instant().toString() : issued);
        if (source.has("expiresAt") && !source.path("expiresAt").isNull()) result.put("expiresAt", source.path("expiresAt").asString());
        else result.putNull("expiresAt");
        return result;
    }

    private Optional<Token> external() {
        try {
            String json = inlineJson;
            if (json == null || json.isBlank()) {
                if (externalFile == null || !Files.isRegularFile(externalFile) || Files.size(externalFile) > 1024 * 1024) return Optional.empty();
                json = Files.readString(externalFile, StandardCharsets.UTF_8);
            }
            return Optional.of(parse(REF, JSON.readTree(json)));
        } catch (IOException | RuntimeException invalid) { return Optional.empty(); }
    }

    private static Token parse(String name, JsonNode root) {
        var token = root.path("tokens").path("access_token").asString("");
        if (token.isBlank() || !safe(token)) throw auth("Codex access token is unusable");
        var account = root.path("tokens").path("account_id").asString("");
        if (account.isBlank()) account = root.path("account_id").asString("");
        if (!account.isBlank() && !safe(account)) throw auth("Codex account identifier is unusable");
        var headers = new LinkedHashMap<String, String>();
        if (!account.isBlank()) headers.put(ACCOUNT_HEADER, account);
        headers.put("originator", "codex_cli_rs");
        headers.put("OpenAI-Beta", "responses=experimental");
        Instant expires = null;
        try {
            var parts = token.split("\\.");
            if (parts.length == 3) {
                var payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
                if (payload.path("exp").isNumber()) expires = Instant.ofEpochSecond(payload.path("exp").asLong());
            }
        } catch (RuntimeException opaque) { /* JWT expiry is only a hint, not identity or authorization proof. */ }
        return new Token(new Credential(name, token, headers), account, expires);
    }

    private boolean due(ManagedCredentialStore.Entry entry, Token token) {
        if (token.expiresAt() == null) return false;
        Duration lead = Duration.ofSeconds(60);
        try {
            var issued = Instant.parse(entry.session().path("issuedAt").asString());
            var lifetime = Duration.between(issued, token.expiresAt());
            if (lifetime.isNegative() || lifetime.isZero()) return true;
            if (lifetime.dividedBy(10).compareTo(lead) < 0) lead = lifetime.dividedBy(10);
        } catch (RuntimeException unknown) { /* Older snapshots use the bounded default window. */ }
        return !clock.instant().isBefore(token.expiresAt().minus(lead));
    }
    private static boolean transientFailure(LlmFailure failure) {
        return failure.kind() == LlmFailure.Kind.NETWORK
                || failure.kind() == LlmFailure.Kind.TIMEOUT
                || failure.kind() == LlmFailure.Kind.SERVER
                || failure.kind() == LlmFailure.Kind.RATE_LIMIT;
    }

    private boolean expired(Token token) { return token.expiresAt() != null && !clock.instant().isBefore(token.expiresAt()); }
    private static boolean safe(String value) { return value != null && value.chars().allMatch(c -> c > 0x20 && c <= 0x7e); }
    private static void rejectPlatform(URI resource) {
        if (resource != null && "api.openai.com".equalsIgnoreCase(resource.getHost())) {
            throw auth("ChatGPT credentials cannot authenticate the OpenAI Platform API; use an API key");
        }
    }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new LlmFailure(LlmFailure.Kind.CANCELLED, "Cancelled");
        }
    }
    private static LlmFailure auth(String message) { return new LlmFailure(LlmFailure.Kind.AUTH, message); }
    private record Token(Credential credential, String accountId, Instant expiresAt) {
        @Override public String toString() { return "CodexToken[<redacted>]"; }
    }
    private static Path defaultAuthFile() {
        var home = System.getenv("CODEX_HOME");
        return (home == null || home.isBlank() ? Path.of(System.getProperty("user.home"), ".codex") : Path.of(home)).resolve("auth.json");
    }
}
