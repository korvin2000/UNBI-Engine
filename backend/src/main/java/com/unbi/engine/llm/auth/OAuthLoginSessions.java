package com.unbi.engine.llm.auth;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.llm.spec.LlmFailure;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns the short-lived, browser-bound ceremonies used to establish a managed OAuth session.
 *
 * <p>Only deliberately small public projections leave this service. State, verifier, authorization
 * code, device code and token sessions remain in a pending worker until they are consumed and
 * cleared. A worker holds its {@link DataDirectory.Lease} from startup through commit so data
 * relocation cannot split an interactive ceremony across homes.
 */
@Service
public final class OAuthLoginSessions implements AutoCloseable {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Duration LOGIN_LIFETIME = Duration.ofMinutes(10);
    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_PENDING = 100;
    private static final int MAX_RETAINED = 100;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ManagedCredentialStore store;
    private final OAuthTokenClient tokenClient;
    private final OAuthCredentials credentials;
    private final CodexCredentials codex;
    private final DataDirectory data;
    private final Clock clock;
    private final URI callbackBase;
    private final ExecutorService workers;
    private final Map<String, Pending> logins = new LinkedHashMap<>();
    private final Map<String, Pending> states = new LinkedHashMap<>();

    @Autowired
    public OAuthLoginSessions(
            ManagedCredentialStore store,
            OAuthTokenClient tokenClient,
            OAuthCredentials credentials,
            DataDirectory data,
            CodexCredentials codex,
            @Value("${unbi.llm.oauth.callback-base-url:http://localhost:${server.port:8081}}") String callbackBaseUrl) {
        this(store, tokenClient, credentials, data, codex, callbackBaseUrl, Clock.systemUTC());
    }

    /** Test seam for deterministic expiry and callback assertions. */
    public OAuthLoginSessions(
            ManagedCredentialStore store,
            OAuthTokenClient tokenClient,
            OAuthCredentials credentials,
            DataDirectory data,
            CodexCredentials codex,
            String callbackBaseUrl,
            Clock clock) {
        this(store, tokenClient, credentials, data, codex, callbackBaseUrl, clock,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    OAuthLoginSessions(
            ManagedCredentialStore store,
            OAuthTokenClient tokenClient,
            OAuthCredentials credentials,
            DataDirectory data,
            CodexCredentials codex,
            String callbackBaseUrl,
            Clock clock,
            ExecutorService workers) {
        this.store = Objects.requireNonNull(store, "store");
        this.tokenClient = Objects.requireNonNull(tokenClient, "tokenClient");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.data = Objects.requireNonNull(data, "data");
        this.codex = codex;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.callbackBase = callbackBase(callbackBaseUrl);
        this.workers = Objects.requireNonNull(workers, "workers");
    }

    /** Starts one explicit login ceremony and returns only its safe public state. */
    public ObjectNode start(String name, String mode, HttpServletRequest request, HttpServletResponse response) {
        ManagedCredentialStore.validateName(name);
        var entry = store.find(name).orElseThrow(() -> unavailable());
        return start(entry, false, mode, request, response);
    }

    /** The external CLI stays selected until this explicit takeover has completed successfully. */
    public ObjectNode takeover(String name, ObjectNode configuration, String mode,
            HttpServletRequest request, HttpServletResponse response) {
        return start(codex.takeover(name, configuration), true, mode, request, response);
    }

    private ObjectNode start(ManagedCredentialStore.Entry entry, boolean create, String mode,
            HttpServletRequest request, HttpServletResponse response) {
        var name = entry.name();
        if (entry.type() != ManagedCredentialStore.Type.OAUTH2 && entry.type() != ManagedCredentialStore.Type.CODEX) throw unavailable();
        var grant = entry.type() == ManagedCredentialStore.Type.CODEX ? "codex" : entry.configuration().path("grantType").asString("");
        var selected = loginMode(mode, grant);
        if (selected == Mode.CLIENT_CREDENTIALS) {
            credentials.connect(name, TOKEN_TIMEOUT, () -> Thread.currentThread().isInterrupted());
            return ready();
        }

        var session = initiatingSession(request);
        var pending = new Pending(entry, create, selected, session, clock.instant().plus(LOGIN_LIFETIME));
        synchronized (this) {
            pruneTerminalLocked();
            if (activeCountLocked() >= MAX_PENDING) {
                throw new LlmFailure(LlmFailure.Kind.AUTH, "Too many OAuth sign-ins are in progress");
            }
            logins.put(pending.id, pending);
            if (selected == Mode.BROWSER && entry.type() == ManagedCredentialStore.Type.OAUTH2) states.put(pending.state, pending);
        }
        pending.worker = CompletableFuture.runAsync(() -> run(pending), workers);
        try {
            // The worker completes this only after it owns the thread-bound data lease.
            var started = pending.started.get(TOKEN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (selected == Mode.BROWSER && entry.type() == ManagedCredentialStore.Type.OAUTH2 && response != null) {
                // This cookie is deliberately per-login: two browser ceremonies may coexist without
                // one callback overwriting the other's binding.
                response.addHeader("Set-Cookie", cookieHeader(pending, (int) LOGIN_LIFETIME.toSeconds()));
            }
            return started;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancelPending(pending);
            throw cancelled();
        } catch (java.util.concurrent.TimeoutException timedOut) {
            cancelPending(pending);
            throw new LlmFailure(LlmFailure.Kind.TIMEOUT, "OAuth sign-in did not start in time");
        } catch (ExecutionException failed) {
            throw safeFailure(failed.getCause());
        }
    }

    /** Returns a session-bound status projection; it never performs token I/O. */
    public ObjectNode status(String name, String loginId, HttpServletRequest request) {
        var pending = owned(name, loginId, request);
        synchronized (pending) {
            expireIfNeeded(pending);
            return publicStatus(pending);
        }
    }

    /** Cancels an initiating-session-owned ceremony and interrupts its worker promptly. */
    public void cancel(String name, String loginId, HttpServletRequest request) {
        cancelPending(owned(name, loginId, request));
    }

    /** Administrative cancellation used before configuration changes, logout, or removal. */
    public void cancelAll(String name) {
        ManagedCredentialStore.validateName(name);
        var matches = new ArrayList<Pending>();
        synchronized (this) {
            for (var pending : logins.values()) if (pending.name.equals(name) && !pending.terminal()) matches.add(pending);
        }
        matches.forEach(this::cancelPending);
    }

    /**
     * Accepts an authorization response after proving it belongs to the exact initiating browser
     * session, callback URI, credential revision, state cookie and unconsumed ceremony.
     */
    public void callback(
            String name,
            String state,
            String code,
            String error,
            HttpServletRequest request,
            HttpServletResponse response) {
        Pending pending;
        synchronized (this) {
            pending = states.get(state);
        }
        if (pending == null || !pending.name.equals(name) || !callbackOwnershipMatches(pending, request)) {
            throw invalidCallback();
        }
        synchronized (pending) {
            if (pending.consumed || pending.terminal() || !clock.instant().isBefore(pending.expiresAt)
                    || !pending.revisionMatches(store)) {
                consume(pending);
                if (!pending.terminal()) finish(pending, Status.REAUTH_REQUIRED, "Sign-in is no longer valid");
                throw invalidCallback();
            }
            consume(pending);
            if (error != null && !error.isBlank()) {
                pending.denied = true;
            } else if (code == null || code.isBlank()) {
                pending.denied = true;
            } else {
                pending.code = code;
            }
            pending.notifyAll();
        }
        clearCookie(response, pending);
    }

    /** The registered redirect URI. It is derived only from configured callback-base-url. */
    public String callbackUri(String name) {
        ManagedCredentialStore.validateName(name);
        return callbackBase.resolve("api/credentials/oauth/callback/" + name).toString();
    }

    private void run(Pending pending) {
        pending.workerThread = Thread.currentThread();
        try (var lease = data.acquireLease()) {
            synchronized (pending) {
                if (pending.cancelled) throw cancelled();
            }
            if (pending.type == ManagedCredentialStore.Type.CODEX) runCodex(pending);
            else if (pending.mode == Mode.BROWSER) runBrowser(pending);
            else runDevice(pending);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            finish(pending, Status.ERROR, "Sign-in was cancelled");
            pending.started.completeExceptionally(cancelled());
        } catch (Throwable failure) {
            finish(pending, terminalStatus(failure), safeMessage(failure));
            pending.started.completeExceptionally(safeFailure(failure));
        } finally {
            pending.clearPrivate();
            pending.workerThread = null;
        }
    }

    private void runCodex(Pending pending) {
        var definition = new ManagedCredentialStore.Entry(pending.name, pending.type, pending.revision,
                pending.configuration, JSON.objectNode());
        codex.login(definition, pending.create, pending.mode == Mode.DEVICE ? "device" : "browser", started -> {
            synchronized (pending) {
                if (pending.cancelled()) throw cancelled();
                started.put("loginId", pending.id).put("expiresAt", pending.expiresAt.toString());
                pending.started.complete(started);
            }
        }, pending::cancelled);
        finish(pending, Status.READY, "");
    }

    private void runBrowser(Pending pending) throws InterruptedException {
        synchronized (pending) {
            if (pending.cancelled) throw new InterruptedException();
            pending.started.complete(browserStart(pending));
            while (!pending.cancelled && !pending.terminal() && !pending.denied && pending.code == null && clock.instant().isBefore(pending.expiresAt)) {
                pending.wait(100);
            }
            if (pending.terminal()) return;
            if (pending.cancelled) throw new InterruptedException();
            if (!clock.instant().isBefore(pending.expiresAt)) {
                finish(pending, Status.ERROR, "Sign-in expired");
                return;
            }
            if (pending.denied) {
                finish(pending, Status.ERROR, "Sign-in was denied");
                return;
            }
            if (!pending.revisionMatches(store)) {
                finish(pending, Status.REAUTH_REQUIRED, "Sign-in is no longer valid");
                return;
            }
        }
        ObjectNode session;
        try {
            session = tokenClient.authorizationCode(
                    pending.configuration, pending.takeCode(), callbackUri(pending.name), pending.takeVerifier(), TOKEN_TIMEOUT);
        } finally {
            pending.clearCodeAndVerifier();
        }
        synchronized (pending) {
            if (pending.cancelled()) throw cancelled();
            store.commitSession(pending.name, pending.revision, session);
            finish(pending, Status.READY, "");
        }
    }

    private void runDevice(Pending pending) {
        var challenge = tokenClient.deviceAuthorization(pending.configuration, TOKEN_TIMEOUT);
        synchronized (pending) {
            pending.expiresAt = earlier(pending.expiresAt, challenge.expiresAt());
            if (pending.cancelled) throw cancelled();
            pending.started.complete(deviceStart(pending, challenge));
        }
        var session = tokenClient.pollDevice(pending.configuration, challenge, pending::cancelled);
        synchronized (pending) {
            if (pending.cancelled()) throw cancelled();
            store.commitSession(pending.name, pending.revision, session);
            finish(pending, Status.READY, "");
        }
    }

    private ObjectNode browserStart(Pending pending) {
        var result = publicStatus(pending);
        result.put("authorizationUrl", authorizationUrl(pending));
        return result;
    }

    private static ObjectNode deviceStart(Pending pending, OAuthTokenClient.DeviceAuthorization challenge) {
        var result = publicStatus(pending);
        result.put("verificationUrl", challenge.verificationUrl());
        result.put("userCode", challenge.userCode());
        result.put("expiresAt", pending.expiresAt.toString());
        return result;
    }

    private String authorizationUrl(Pending pending) {
        var configuration = pending.configuration;
        var endpoint = ManagedCredentialStore.oauthEndpoint(configuration.path("authorizationUrl").asString());
        var query = new StringBuilder("response_type=code")
                .append("&client_id=").append(percent(configuration.path("clientId").asString()))
                .append("&redirect_uri=").append(percent(callbackUri(pending.name)))
                .append("&state=").append(percent(pending.state))
                .append("&code_challenge=").append(percent(challenge(pending.verifier)))
                .append("&code_challenge_method=S256");
        var scopes = configuration.path("scopes");
        if (scopes.isArray() && scopes.size() > 0) {
            var values = new ArrayList<String>();
            scopes.forEach(value -> values.add(value.asString()));
            query.append("&scope=").append(percent(String.join(" ", values)));
        }
        var reserved = java.util.Set.of("response_type", "client_id", "redirect_uri", "state",
                "code_challenge", "code_challenge_method", "scope");
        var extras = new ArrayList<String>();
        if (endpoint.getRawQuery() != null) {
            for (var parameter : endpoint.getRawQuery().split("&")) {
                var key = java.net.URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!reserved.contains(key)) extras.add(parameter);
            }
        }
        var base = endpoint.toString().split("\\?", 2)[0];
        return base + "?" + (extras.isEmpty() ? "" : String.join("&", extras) + "&") + query;
    }

    private Pending owned(String name, String loginId, HttpServletRequest request) {
        ManagedCredentialStore.validateName(name);
        if (loginId == null || loginId.isBlank()) throw unavailable();
        Pending pending;
        synchronized (this) {
            pending = logins.get(loginId);
        }
        if (pending == null || !pending.name.equals(name) || !pending.sessionId.equals(sessionId(request))) throw unavailable();
        return pending;
    }

    private boolean callbackOwnershipMatches(Pending pending, HttpServletRequest request) {
        return pending.mode == Mode.BROWSER && !pending.sessionId.isBlank() && pending.sessionId.equals(sessionId(request))
                && URI.create(callbackUri(pending.name)).getRawPath().equals(request.getRequestURI())
                && pending.state.equals(cookie(request, pending.cookieName()));
    }

    private void consume(Pending pending) {
        pending.consumed = true;
        synchronized (this) {
            states.remove(pending.state, pending);
        }
    }

    private void cancelPending(Pending pending) {
        synchronized (pending) {
            if (pending.terminal()) return;
            pending.cancelled = true;
            pending.notifyAll();
        }
        pending.started.completeExceptionally(cancelled());
        var thread = pending.workerThread;
        if (thread != null) thread.interrupt();
        var worker = pending.worker;
        if (worker != null) worker.cancel(true);
        finish(pending, Status.ERROR, "Sign-in was cancelled");
    }


    private void expireIfNeeded(Pending pending) {
        if (!pending.terminal() && !clock.instant().isBefore(pending.expiresAt)) {
            pending.cancelled = true;
            var worker = pending.workerThread;
            if (worker != null) worker.interrupt();
            consume(pending);
            finish(pending, Status.ERROR, "Sign-in expired");
        }
    }

    private void finish(Pending pending, Status status, String error) {
        synchronized (pending) {
            if (pending.terminal()) return;
            if (status == Status.READY && pending.cancelled) {
                status = Status.ERROR;
                error = "Sign-in was cancelled";
            }
            pending.status = status;
            pending.error = error;
            pending.completedAt = clock.instant();
            pending.clearCodeAndVerifier();
            pending.notifyAll();
        }
        synchronized (this) {
            states.remove(pending.state, pending);
            pruneTerminalLocked();
        }
    }

    private static ObjectNode publicStatus(Pending pending) {
        var result = JSON.objectNode();
        result.put("loginId", pending.id);
        result.put("status", pending.status.wire);
        if (pending.expiresAt != null) result.put("expiresAt", pending.expiresAt.toString());
        if (!pending.error.isBlank()) result.put("error", pending.error);
        return result;
    }

    private ObjectNode ready() {
        var result = JSON.objectNode();
        result.put("status", Status.READY.wire);
        return result;
    }

    private void pruneTerminalLocked() {
        var finished = logins.values().stream().filter(Pending::terminal).sorted(Comparator.comparing(pending -> pending.completedAt))
                .toList();
        var excess = finished.size() - MAX_RETAINED;
        for (var i = 0; i < excess; i++) logins.remove(finished.get(i).id);
    }

    private int activeCountLocked() {
        return (int) logins.values().stream().filter(pending -> !pending.terminal()).count();
    }

    private static Mode loginMode(String value, String grant) {
        if ("codex".equals(grant)) {
            if (value == null || value.isBlank() || value.equals("browser")) return Mode.BROWSER;
            if (value.equals("device")) return Mode.DEVICE;
        }
        if ("client_credentials".equals(grant)) return Mode.CLIENT_CREDENTIALS;
        if ("authorization_code".equals(grant) && (value == null || value.isBlank() || value.equals("browser"))) return Mode.BROWSER;
        if ("device_authorization".equals(grant) && (value == null || value.isBlank() || value.equals("device"))) return Mode.DEVICE;
        throw new LlmFailure(LlmFailure.Kind.AUTH, "This credential does not support that sign-in method");
    }

    private static URI callbackBase(String value) {
        try {
            var base = ManagedCredentialStore.oauthEndpoint(value == null ? "" : value.trim());
            if (!base.isAbsolute() || base.getHost() == null || base.getRawQuery() != null || base.getRawFragment() != null
                    || base.getRawUserInfo() != null || (!base.getScheme().equals("https") && !base.getScheme().equals("http"))) {
                throw new IllegalArgumentException();
            }
            var text = base.toString();
            return URI.create(text.endsWith("/") ? text : text + "/");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid OAuth callback base URL");
        }
    }

    private static String initiatingSession(HttpServletRequest request) {
        if (request == null || request.getSession(true) == null) throw unavailable();
        return request.getSession().getId();
    }

    private static String sessionId(HttpServletRequest request) {
        return request == null || request.getSession(false) == null ? "" : request.getSession(false).getId();
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) return "";
        for (Cookie cookie : request.getCookies()) if (name.equals(cookie.getName())) return cookie.getValue();
        return "";
    }


    private static LlmFailure invalidCallback() {
        return new LlmFailure(LlmFailure.Kind.AUTH, "OAuth callback is invalid or expired");
    }

    private void clearCookie(HttpServletResponse response, Pending pending) {
        if (response == null) return;
        response.addHeader("Set-Cookie", cookieHeader(pending, 0));
    }

    private static String cookieHeader(Pending pending, int maxAge) {
        var header = pending.cookieName() + "=" + pending.state + "; Path=" + pending.callbackPath()
                + "; Max-Age=" + maxAge + "; HttpOnly; SameSite=Lax";
        return pending.secureCallback ? header + "; Secure" : header;
    }

    private static String random(int bytes) {
        var value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String percent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~");
    }

    private static Instant earlier(Instant first, Instant second) {
        if (second == null) return first;
        return second.isBefore(first) ? second : first;
    }

    private static Status terminalStatus(Throwable failure) {
        if (failure instanceof OAuthTokenClient.Failure token && "invalid_grant".equals(token.code())) return Status.REAUTH_REQUIRED;
        return Status.ERROR;
    }

    private static String safeMessage(Throwable failure) {
        if (failure instanceof LlmFailure known && known.kind() == LlmFailure.Kind.CANCELLED) return "Sign-in was cancelled";
        if (failure instanceof LlmFailure known) return known.getMessage();
        return "Sign-in could not be completed";
    }

    private static LlmFailure safeFailure(Throwable failure) {
        if (failure instanceof LlmFailure known) return new LlmFailure(known.kind(), safeMessage(known), "", 0,
                known.retryAfterMillis(), null);
        return new LlmFailure(LlmFailure.Kind.AUTH, "Sign-in could not be completed");
    }

    private static LlmFailure unavailable() {
        return new LlmFailure(LlmFailure.Kind.AUTH, "OAuth sign-in is unavailable");
    }

    private static LlmFailure cancelled() {
        return new LlmFailure(LlmFailure.Kind.CANCELLED, "Cancelled");
    }

    @Override
    @PreDestroy
    public void close() {
        java.util.List<Pending> active;
        synchronized (this) { active = new ArrayList<>(logins.values()); }
        active.forEach(this::cancelPending);
        workers.shutdownNow();
    }

    private enum Mode { BROWSER, DEVICE, CLIENT_CREDENTIALS }

    private enum Status {
        PENDING("pending"), READY("ready"), REAUTH_REQUIRED("reauth_required"), ERROR("error");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }
    }

    private final class Pending {
        private final String id = random(24);
        private String state = random(32);
        private String verifier = random(32);
        private String code;
        private boolean denied;
        private boolean consumed;
        private volatile boolean cancelled;
        private final String name;
        private final String revision;
        private final ManagedCredentialStore.Type type;
        private final boolean create;
        private final ObjectNode configuration;
        private final Mode mode;
        private final String sessionId;
        private volatile Instant expiresAt;
        private final boolean secureCallback;
        private volatile Status status = Status.PENDING;
        private String error = "";
        private Instant completedAt;
        private final CompletableFuture<ObjectNode> started = new CompletableFuture<>();
        private volatile CompletableFuture<?> worker;
        private volatile Thread workerThread;

        private Pending(ManagedCredentialStore.Entry entry, boolean create, Mode mode, String sessionId, Instant expiresAt) {
            this.name = entry.name();
            this.revision = entry.revision();
            this.type = entry.type();
            this.create = create;
            this.configuration = entry.configuration();
            this.mode = mode;
            this.sessionId = sessionId;
            this.expiresAt = expiresAt;
            this.secureCallback = callbackBase.getScheme().equalsIgnoreCase("https");
        }

        private boolean terminal() {
            return status != Status.PENDING;
        }

        private boolean cancelled() {
            return cancelled || terminal() || !clock.instant().isBefore(expiresAt) || Thread.currentThread().isInterrupted();
        }

        private boolean revisionMatches(ManagedCredentialStore records) {
            return records.find(name).map(entry -> entry.revision().equals(revision)).orElse(false);
        }

        private String takeCode() {
            var value = code;
            code = null;
            if (value == null || value.isBlank()) throw new LlmFailure(LlmFailure.Kind.AUTH, "OAuth sign-in response is unavailable");
            return value;
        }

        private String takeVerifier() {
            var value = verifier;
            verifier = null;
            if (value == null || value.isBlank()) throw new LlmFailure(LlmFailure.Kind.AUTH, "OAuth sign-in response is unavailable");
            return value;
        }

        private void clearCodeAndVerifier() {
            code = null;
            verifier = null;
        }
        private void clearPrivate() {
            clearCodeAndVerifier();
            configuration.removeAll();
        }

        private String cookieName() {
            return "oauth_login_" + id;
        }

        private String callbackPath() {
            return callbackBase.getPath() + "api/credentials/oauth/callback/" + name;
        }
    }
}
