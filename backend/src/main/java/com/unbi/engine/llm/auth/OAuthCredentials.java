package com.unbi.engine.llm.auth;

import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Engine-owned Basic and generic OAuth credentials.
 *
 * <p>Inspection is deliberately local: a disconnected managed record continues to claim its name,
 * while token acquisition happens only when a request resolves it. That keeps a disconnected OAuth
 * definition from being silently replaced by an API key with the same name.
 */
@Component
public final class OAuthCredentials implements CredentialSource {

    private static final Duration MAX_TOKEN_TIMEOUT = Duration.ofSeconds(30);

    private final ManagedCredentialStore store;
    private final OAuthTokenClient tokens;
    private final Clock clock;

    @Autowired
    public OAuthCredentials(ManagedCredentialStore store, OAuthTokenClient tokens) {
        this(store, tokens, Clock.systemUTC());
    }

    /** Test seam for expiry boundaries without wall-clock sleeps. */
    public OAuthCredentials(ManagedCredentialStore store, OAuthTokenClient tokens, Clock clock) {
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.tokens = java.util.Objects.requireNonNull(tokens, "tokens");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String id() {
        return "managed";
    }

    @Override
    public int order() {
        return 15;
    }

    @Override
    public Optional<Credential> find(String ref) {
        return managed(ref).flatMap(this::localCredential);
    }

    @Override
    public SequencedSet<String> names() {
        var names = new LinkedHashSet<String>();
        for (var name : store.names()) {
            try {
                if (store.find(name).map(ManagedCredentialStore.Entry::type)
                        .filter(type -> type != ManagedCredentialStore.Type.CODEX)
                        .isPresent()) {
                    names.add(name);
                }
            } catch (LlmFailure malformed) {
                names.add(name);
            }
        }
        return names;
    }

    @Override
    public void validate(String ref, EndpointSpec endpoint) {
        var entry = required(ref);
        var expected = switch (entry.type()) {
            case BASIC -> EndpointSpec.AuthScheme.BASIC;
            case OAUTH2 -> EndpointSpec.AuthScheme.OAUTH2;
            case CODEX -> throw unavailable();
        };
        if (endpoint == null || endpoint.authScheme() != expected) {
            throw new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential type does not match endpoint authentication");
        }
        ManagedCredentialStore.validateResource(entry, URI.create(endpoint.baseUrl()));
    }

    @Override
    public Optional<Credential> resolve(String ref, URI resource, Duration timeout, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        var entry = required(ref);
        ManagedCredentialStore.validateResource(entry, resource);
        if (entry.type() == ManagedCredentialStore.Type.BASIC) {
            return localCredential(entry);
        }
        if (entry.type() != ManagedCredentialStore.Type.OAUTH2) {
            throw unavailable();
        }
        if ("reauth_required".equals(entry.session().path("status").asString())) throw reauthRequired();

        var present = localCredential(entry);
        if (present.isEmpty()) {
            if ("client_credentials".equals(entry.configuration().path("grantType").asString())) {
                return Optional.of(connect(entry, timeout, cancelled));
            }
            throw reauthRequired();
        }

        var expiry = expiry(entry.session());
        if (expiry.isEmpty() || !due(entry.session(), expiry.orElseThrow())) {
            return present;
        }
        if (!canRenew(entry)) {
            if (stillUnexpired(entry.session())) return present;
            persistReauth(entry);
            throw reauthRequired();
        }
        return Optional.of(renew(entry, timeout, cancelled, false));
    }

    @Override
    public Optional<Credential> refresh(
            String ref, Credential rejected, URI resource, Duration timeout, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        var initial = required(ref);
        ManagedCredentialStore.validateResource(initial, resource);
        if (initial.type() != ManagedCredentialStore.Type.OAUTH2) return Optional.empty();
        var latest = required(ref);
        ManagedCredentialStore.validateResource(latest, resource);
        if (latest.type() != ManagedCredentialStore.Type.OAUTH2) return Optional.empty();

        var current = localCredential(latest);
        if (current.isEmpty()) {
            if (!"reauth_required".equals(latest.session().path("status").asString())) {
                persistReauth(latest);
            }
            return Optional.empty();
        }
        // Another process/session may already have persisted a rotation between dispatch and 401.
        // Adopt its token instead of exchanging a second refresh token.
        if (rejected == null || !current.orElseThrow().token().equals(rejected.token())) return current;

        if (!canRenew(latest)) {
            persistReauth(latest);
            return Optional.empty();
        }
        return Optional.of(renew(latest, timeout, cancelled, true));
    }

    /** Public catalog detail. It intentionally contains neither credential material nor account data. */
    public ObjectNode status(String name) {
        var entry = required(name);
        var result = JsonNodeFactory.instance.objectNode();
        result.put("type", entry.type().name().toLowerCase(java.util.Locale.ROOT));
        if (entry.type() == ManagedCredentialStore.Type.BASIC) {
            result.put("status", "ready");
            result.putNull("expiresAt");
            result.put("renewable", false);
            return result;
        }
        if (entry.type() != ManagedCredentialStore.Type.OAUTH2) throw unavailable();

        var session = entry.session();
        var token = localCredential(entry);
        var expires = expiry(session);
        var renewable = canRenew(entry);
        var state = session.path("status").asString("");
        if (session.isEmpty()) {
            state = "unconfigured";
        } else if ("reauth_required".equals(state) || token.isEmpty()
                || (expires.isPresent() && !clock.instant().isBefore(expires.orElseThrow()) && !renewable)) {
            state = "reauth_required";
        } else if (!"pending".equals(state) && !"refreshing".equals(state) && !"error".equals(state)) {
            state = "ready";
        }
        result.put("status", state);
        if (expires.isPresent()) result.put("expiresAt", expires.orElseThrow().toString());
        else result.putNull("expiresAt");
        result.put("renewable", renewable);
        return result;
    }

    /** Explicitly obtains a fresh client-credentials session; browser grants are committed by login sessions. */
    public Credential connect(String name, Duration timeout, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        var entry = required(name);
        if (entry.type() != ManagedCredentialStore.Type.OAUTH2
                || !"client_credentials".equals(entry.configuration().path("grantType").asString())) {
            throw new LlmFailure(LlmFailure.Kind.AUTH, "This credential requires an interactive OAuth login");
        }
        return connect(entry, timeout, cancelled);
    }

    private Credential connect(ManagedCredentialStore.Entry entry, Duration timeout, BooleanSupplier cancelled) {
        var renewed = store.renew(entry, tokenTimeout(timeout), cancelled,
                (current, root) -> acquire(current.configuration(), tokenTimeout(timeout)));
        return credential(renewed);
    }

    private Credential renew(
            ManagedCredentialStore.Entry entry, Duration timeout, BooleanSupplier cancelled, boolean forced) {
        try {
            var renewed = store.renew(entry, tokenTimeout(timeout), cancelled, (current, root) -> {
                var configuration = current.configuration();
                try {
                    if ("client_credentials".equals(configuration.path("grantType").asString())) {
                        return acquire(configuration, tokenTimeout(timeout));
                    }
                    var previous = current.session();
                    if (!hasRefreshToken(previous)) return reauthSession();
                    return tokens.refresh(configuration, previous, tokenTimeout(timeout));
                } catch (OAuthTokenClient.Failure failed) {
                    if (!failed.transientFailure()) return reauthSession();
                    throw failed;
                }
            });
            return credential(renewed);
        } catch (LlmFailure failed) {
            // A due (not rejected) token can continue only while it is actually unexpired. Forced
            // 401 recovery must never send the rejected token again.
            if (!forced && failed.isRetryable() && stillUnexpired(entry.session())) {
                return credential(entry);
            }
            throw failed;
        }
    }

    private ObjectNode acquire(ObjectNode configuration, Duration timeout) {
        try { return tokens.clientCredentials(configuration, timeout); }
        catch (OAuthTokenClient.Failure failed) {
            if (!failed.transientFailure()) return reauthSession();
            throw failed;
        }
    }


    private void persistReauth(ManagedCredentialStore.Entry entry) {
        store.commitSession(entry.name(), entry.revision(), reauthSession());
    }

    private Optional<ManagedCredentialStore.Entry> managed(String ref) {
        if (ref == null || ref.isBlank()) return Optional.empty();
        try {
            ManagedCredentialStore.validateName(ref);
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
        return store.find(ref).filter(entry -> entry.type() != ManagedCredentialStore.Type.CODEX);
    }

    private ManagedCredentialStore.Entry required(String ref) {
        return managed(ref).orElseThrow(OAuthCredentials::unavailable);
    }

    private Optional<Credential> localCredential(ManagedCredentialStore.Entry entry) {
        return switch (entry.type()) {
            case BASIC -> Optional.of(new Credential(entry.name(), entry.configuration().path("username").asString()
                    + ":" + entry.configuration().path("password").asString(), java.util.Map.of()));
            case OAUTH2 -> accessToken(entry).map(token -> Credential.bearer(entry.name(), token));
            case CODEX -> Optional.empty();
        };
    }

    private Credential credential(ManagedCredentialStore.Entry entry) {
        return localCredential(entry).orElseThrow(OAuthCredentials::reauthRequired);
    }

    private Optional<String> accessToken(ManagedCredentialStore.Entry entry) {
        var session = entry.session();
        if ("reauth_required".equals(session.path("status").asString())) return Optional.empty();
        var token = session.path("accessToken");
        if (!token.isString() || token.asString().isBlank()) return Optional.empty();
        var type = session.path("tokenType").asString("Bearer");
        if (!"Bearer".equalsIgnoreCase(type)) {
            throw new LlmFailure(LlmFailure.Kind.UNSUPPORTED, "OAuth token type is not supported");
        }
        return Optional.of(token.asString());
    }

    private boolean canRenew(ManagedCredentialStore.Entry entry) {
        var grant = entry.configuration().path("grantType").asString();
        return "client_credentials".equals(grant) || hasRefreshToken(entry.session());
    }

    private static boolean hasRefreshToken(ObjectNode session) {
        var refresh = session.path("refreshToken");
        return refresh.isString() && !refresh.asString().isBlank();
    }

    private Optional<Instant> expiry(ObjectNode session) {
        var raw = session.path("expiresAt");
        if (raw.isMissingNode() || raw.isNull() || raw.asString().isBlank()) return Optional.empty();
        try {
            return Optional.of(Instant.parse(raw.asString()));
        } catch (DateTimeParseException invalid) {
            throw new LlmFailure(LlmFailure.Kind.AUTH, "OAuth session expiry is invalid; reconnect it");
        }
    }

    private boolean due(ObjectNode session, Instant expiresAt) {
        var issued = session.path("issuedAt");
        if (!issued.isString()) return !clock.instant().isBefore(expiresAt);
        try {
            var lifetime = Duration.between(Instant.parse(issued.asString()), expiresAt);
            if (lifetime.isNegative() || lifetime.isZero()) return true;
            var lead = lifetime.dividedBy(10);
            if (lead.compareTo(Duration.ofSeconds(60)) > 0) lead = Duration.ofSeconds(60);
            return !clock.instant().isBefore(expiresAt.minus(lead));
        } catch (DateTimeParseException invalid) {
            throw new LlmFailure(LlmFailure.Kind.AUTH, "OAuth session expiry is invalid; reconnect it");
        }
    }

    private boolean stillUnexpired(ObjectNode session) {
        return expiry(session).map(expiry -> clock.instant().isBefore(expiry)).orElse(false);
    }

    private static Duration tokenTimeout(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) return MAX_TOKEN_TIMEOUT;
        return timeout.compareTo(MAX_TOKEN_TIMEOUT) > 0 ? MAX_TOKEN_TIMEOUT : timeout;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
            throw new LlmFailure(LlmFailure.Kind.CANCELLED, "Cancelled");
        }
    }

    private static ObjectNode reauthSession() {
        return JsonNodeFactory.instance.objectNode().put("status", "reauth_required");
    }

    private static LlmFailure reauthRequired() {
        return new LlmFailure(LlmFailure.Kind.AUTH, "OAuth credential requires reconnect");
    }

    private static LlmFailure unavailable() {
        return new LlmFailure(LlmFailure.Kind.AUTH, "Managed credential is unavailable; reconnect it");
    }
}
