package com.unbi.engine.llm.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.unbi.engine.llm.spec.LlmFailure;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Native ChatGPT OAuth ceremony for managed Codex credentials.
 *
 * <p>This client owns only the provider-side protocol: a short-lived loopback callback listener,
 * token exchange/refresh, and the provider's non-RFC device-code exchange. The caller owns its
 * browser HTTP-session binding and persists the returned private session. It intentionally never
 * starts a process or reads another application's credential store.
 */
@Component
public final class ChatGptOAuthClient implements AutoCloseable {
    public static final URI DEFAULT_ISSUER = URI.create("https://auth.openai.com");
    public static final URI DEFAULT_CALLBACK = URI.create("http://localhost:1455/auth/callback");
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String SCOPES = "openid profile email offline_access";

    private static final Duration MAX_HTTP = Duration.ofSeconds(30);
    private static final Duration BROWSER_LIFETIME = Duration.ofMinutes(10);
    // The current official Codex device-code implementation bounds this ceremony to 15 minutes.
    private static final Duration DEVICE_LIFETIME = Duration.ofMinutes(15);
    private static final Duration POLL_FALLBACK = Duration.ofSeconds(5);
    private static final Duration WAIT_SLICE = Duration.ofMillis(100);
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_CALLBACK_VALUE = 16 * 1024;
    private static final int MAX_ACCOUNT_ID = 512;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final URI issuer;
    private final URI callback;
    private final Clock clock;
    private final Sleeper sleeper;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService workers;
    private final HttpClient http;

    /** Spring constructor using ChatGPT's published Codex OAuth endpoints. */
    public ChatGptOAuthClient() {
        this(DEFAULT_ISSUER, DEFAULT_CALLBACK, Clock.systemUTC(), new InterruptibleSleeper(), BROWSER_LIFETIME, DEVICE_LIFETIME);
    }

    /** Package-local fixture seam; endpoint URLs may be a loopback HTTP server. */
    ChatGptOAuthClient(URI issuer, Clock clock, Sleeper sleeper) {
        this(issuer, DEFAULT_CALLBACK, clock, sleeper, BROWSER_LIFETIME, DEVICE_LIFETIME);
    }

    /** Package-local fixture seam for callback-port and lifetime boundaries. */
    ChatGptOAuthClient(URI issuer, URI callback, Clock clock, Sleeper sleeper,
            Duration browserLifetime, Duration deviceLifetime) {
        this.issuer = issuer(issuer);
        this.callback = callback(callback);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        requirePositive(browserLifetime);
        requirePositive(deviceLifetime);
        this.workers = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .executor(workers)
                .connectTimeout(MAX_HTTP)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.browserLifetime = browserLifetime;
        this.deviceLifetime = deviceLifetime;
    }

    private final Duration browserLifetime;
    private final Duration deviceLifetime;

    /**
     * Completes browser or native device-code login and returns an engine-private ready session.
     * The callback passed to {@code started} sees only a pending browser URL or device user code.
     */
    public ObjectNode login(String mode, Consumer<ObjectNode> started, BooleanSupplier cancelled) {
        var stop = cancellation(cancelled);
        Objects.requireNonNull(started, "started");
        checkCancelled(stop);
        if ("browser".equals(mode)) return browserLogin(started, stop);
        if ("device".equals(mode)) return deviceLogin(started, stop);
        throw auth("Unsupported ChatGPT sign-in mode");
    }

    /** Refreshes a same-account native session without exposing either old or new tokens in errors. */
    public ObjectNode refresh(ObjectNode session, BooleanSupplier cancelled) {
        var stop = cancellation(cancelled);
        checkCancelled(stop);
        if (session == null || !"ready".equals(session.path("status").asString())) {
            throw auth("ChatGPT session must be reconnected");
        }
        var refresh = requiredSecret(session, "refreshToken");
        var account = requiredAccount(session.path("accountId").asString());
        var deadline = Deadline.after(MAX_HTTP);
        var reply = postJson(tokenEndpoint(), form(Map.of(
                "grant_type", "refresh_token",
                "refresh_token", refresh,
                "client_id", CLIENT_ID)), "application/x-www-form-urlencoded", deadline, stop);
        if (!reply.success()) throw statusFailure(reply.status(), "ChatGPT token refresh was rejected");
        return session(reply.body(), refresh, account);
    }

    private ObjectNode browserLogin(Consumer<ObjectNode> started, BooleanSupplier cancelled) {
        var state = random(32);
        var verifier = random(32);
        var deadline = Deadline.after(browserLifetime);
        HttpServer server = null;
        try {
            var received = new CompletableFuture<Callback>();
            var consumed = new AtomicBoolean();
            server = callbackServer(state, received, consumed);
            try {
                server.start();
            } catch (RuntimeException unavailable) {
                throw auth("ChatGPT browser sign-in could not start its local callback");
            }
            var visible = NODES.objectNode().put("status", "pending")
                    .put("authorizationUrl", authorizationUrl(state, verifier));
            started.accept(visible);
            var response = awaitCallback(received, deadline, cancelled);
            if (response.denied()) throw auth("ChatGPT sign-in was denied");
            return exchangeCode(response.code(), verifier, callback.toString(), deadline, cancelled, null, null);
        } finally {
            if (server != null) {
                try { server.stop(0); } catch (RuntimeException ignored) { }
            }
        }
    }

    /**
     * ChatGPT's current Codex device protocol is intentionally not RFC 8628: it requests
     * {@code /api/accounts/deviceauth/usercode}, polls {@code /deviceauth/token}, receives an
     * authorization code plus PKCE verifier, and only then exchanges that code at {@code /oauth/token}.
     */
    private ObjectNode deviceLogin(Consumer<ObjectNode> started, BooleanSupplier cancelled) {
        var deadline = Deadline.after(deviceLifetime);
        var request = NODES.objectNode().put("client_id", CLIENT_ID);
        var challenge = postJson(deviceEndpoint("/api/accounts/deviceauth/usercode"), json(request), "application/json", deadline, cancelled);
        if (!challenge.success()) throw statusFailure(challenge.status(), "ChatGPT device sign-in is unavailable");
        var deviceAuthId = requiredSecret(challenge.body(), "device_auth_id");
        var userCode = requiredVisible(challenge.body(), "user_code", "usercode");
        var interval = deviceInterval(challenge.body());
        var visible = NODES.objectNode().put("status", "pending")
                .put("verificationUrl", deviceEndpoint("/codex/device").toString())
                .put("userCode", userCode);
        started.accept(visible);

        while (true) {
            checkCancelled(cancelled);
            if (deadline.expired()) throw timeout("ChatGPT device sign-in timed out");
            var poll = NODES.objectNode().put("device_auth_id", deviceAuthId).put("user_code", userCode);
            var reply = postJson(deviceEndpoint("/api/accounts/deviceauth/token"), json(poll), "application/json", deadline, cancelled);
            if (reply.success()) {
                var authorizationCode = requiredSecret(reply.body(), "authorization_code");
                var verifier = requiredSecret(reply.body(), "code_verifier");
                var challengeValue = requiredSecret(reply.body(), "code_challenge");
                if (!constantTime(challenge(verifier), challengeValue)) throw malformed();
                return exchangeCode(authorizationCode, verifier, deviceEndpoint("/deviceauth/callback").toString(),
                        deadline, cancelled, null, null);
            }
            // The official Codex client represents an unapproved/unknown device authorization as 403/404.
            if (reply.status() != 403 && reply.status() != 404) throw statusFailure(reply.status(), "ChatGPT device sign-in was rejected");
            sleep(interval, deadline, cancelled);
        }
    }
    private ObjectNode exchangeCode(String code, String verifier, String redirectUri, Deadline deadline,
            BooleanSupplier cancelled, String priorRefresh, String expectedAccount) {
        var reply = postJson(tokenEndpoint(), form(Map.of(
                "grant_type", "authorization_code",
                "client_id", CLIENT_ID,
                "code", code,
                "code_verifier", verifier,
                "redirect_uri", redirectUri)), "application/x-www-form-urlencoded", deadline, cancelled);
        if (!reply.success()) throw statusFailure(reply.status(), "ChatGPT sign-in was rejected");
        return session(reply.body(), priorRefresh, expectedAccount);
    }

    private ObjectNode session(JsonNode response, String priorRefresh, String expectedAccount) {
        if (response == null || !response.isObject()) throw malformed();
        var tokenType = response.path("token_type");
        if (!tokenType.isMissingNode() && (!tokenType.isString() || !"bearer".equalsIgnoreCase(tokenType.asString()))) {
            throw unsupported("ChatGPT returned a non-bearer access token");
        }
        var access = requiredSecret(response, "access_token");
        String refresh;
        if (response.has("refresh_token")) refresh = requiredSecret(response, "refresh_token");
        else {
            if (priorRefresh == null || priorRefresh.isBlank()) throw malformed();
            refresh = priorRefresh;
        }
        var account = accountId(access, optionalSecret(response, "id_token"));
        if (account == null) throw auth("ChatGPT did not return an account identifier");
        if (expectedAccount != null && !constantTime(expectedAccount, account)) {
            throw auth("The ChatGPT account changed; reconnect it in UNBI");
        }
        var expiresIn = positiveSeconds(response.path("expires_in"));
        var issued = clock.instant();
        final Instant expires;
        try { expires = issued.plusSeconds(expiresIn); }
        catch (RuntimeException invalid) { throw malformed(); }
        return NODES.objectNode().put("status", "ready")
                .put("accessToken", access)
                .put("refreshToken", refresh)
                .put("accountId", account)
                .put("issuedAt", issued.toString())
                .put("expiresAt", expires.toString());
    }

    private HttpServer callbackServer(String state, CompletableFuture<Callback> received, AtomicBoolean consumed) {
        try {
            var address = InetAddress.getByName(callback.getHost());
            if (!address.isLoopbackAddress()) throw new IllegalArgumentException("Callback host is not loopback");
            var server = HttpServer.create(new InetSocketAddress(address, callback.getPort()), 0);
            server.createContext(callback.getRawPath(), new CallbackHandler(callback.getRawPath(), state, consumed, received));
            return server;
        } catch (IOException | RuntimeException unavailable) {
            throw auth("ChatGPT browser sign-in could not start its local callback");
        }
    }

    private Callback awaitCallback(CompletableFuture<Callback> callback, Deadline deadline, BooleanSupplier cancelled) {
        return await(callback, deadline, cancelled);
    }

    private JsonReply postJson(URI endpoint, byte[] payload, String contentType, Deadline deadline, BooleanSupplier cancelled) {
        checkCancelled(cancelled);
        var remaining = deadline.remaining(MAX_HTTP);
        if (remaining == null) throw timeout("ChatGPT OAuth request timed out");
        // A ceremony may last minutes, but each header/body exchange is independently bounded.
        var requestDeadline = Deadline.after(remaining);
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(remaining)
                .header("Accept", "application/json")
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        Future<HttpResponse<InputStream>> headers = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response = null;
        try {
            response = await(headers, requestDeadline, cancelled);
            if (response.statusCode() < 200 || response.statusCode() >= 300) return new JsonReply(response.statusCode(), null);
            if (!jsonContentType(response.headers().firstValue("Content-Type").orElse(""))) throw malformed();
            var body = response.body();
            Future<byte[]> read = workers.submit(() -> readBody(body));
            var bytes = await(read, requestDeadline, cancelled);
            try {
                var parsed = JSON.readTree(bytes);
                if (parsed == null || !parsed.isObject()) throw malformed();
                return new JsonReply(response.statusCode(), parsed);
            } catch (RuntimeException invalid) {
                if (invalid instanceof LlmFailure failure) throw failure;
                throw malformed();
            }
        } finally {
            headers.cancel(true);
            if (response != null) close(response.body());
        }
    }

    private <T> T await(Future<T> future, Deadline deadline, BooleanSupplier cancelled) {
        try {
            while (true) {
                checkCancelled(cancelled);
                var remaining = deadline.remaining(null);
                if (remaining == null) throw timeout("ChatGPT OAuth request timed out");
                try {
                    return future.get(Math.min(remaining.toNanos(), WAIT_SLICE.toNanos()), TimeUnit.NANOSECONDS);
                } catch (TimeoutException waiting) {
                    // Poll cancellation and the one monotonic deadline while headers/body are blocked.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cancelled();
        } catch (ExecutionException failed) {
            var cause = failed.getCause();
            while (cause instanceof ExecutionException || cause instanceof java.util.concurrent.CompletionException) {
                cause = cause.getCause();
            }
            if (cause instanceof LlmFailure known) throw known;
            if (cause instanceof ResponseTooLarge) throw malformed();
            if (cause instanceof java.util.concurrent.CancellationException) throw cancelled();
            if (cause instanceof IOException || cause instanceof java.net.http.HttpTimeoutException) {
                throw network("ChatGPT OAuth request failed");
            }
            throw malformed();
        } finally {
            future.cancel(true);
        }
    }

    private void sleep(Duration interval, Deadline deadline, BooleanSupplier cancelled) {
        var remaining = deadline.remaining(null);
        if (remaining == null) throw timeout("ChatGPT device sign-in timed out");
        var duration = interval.compareTo(remaining) < 0 ? interval : remaining;
        try {
            sleeper.sleep(duration, cancelled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cancelled();
        }
        checkCancelled(cancelled);
    }

    private URI tokenEndpoint() { return deviceEndpoint("/oauth/token"); }

    private URI deviceEndpoint(String path) {
        return URI.create(issuer.toString() + path);
    }

    private String authorizationUrl(String state, String verifier) {
        var values = new LinkedHashMap<String, String>();
        values.put("response_type", "code");
        values.put("client_id", CLIENT_ID);
        values.put("redirect_uri", callback.toString());
        values.put("scope", SCOPES);
        values.put("code_challenge", challenge(verifier));
        values.put("code_challenge_method", "S256");
        values.put("state", state);
        values.put("id_token_add_organizations", "true");
        values.put("codex_cli_simplified_flow", "true");
        values.put("originator", "unbi_engine");
        return deviceEndpoint("/oauth/authorize").toString() + "?" + query(values);
    }

    private static String query(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encoded(entry.getKey()) + "=" + encoded(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~");
    }

    private static byte[] form(Map<String, String> values) { return query(values).getBytes(StandardCharsets.UTF_8); }

    private static byte[] json(ObjectNode value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (RuntimeException impossible) { throw malformed(); }
    }

    private static byte[] readBody(InputStream input) throws IOException {
        try (input) {
            var bytes = new java.io.ByteArrayOutputStream();
            var buffer = new byte[4096];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) return bytes.toByteArray();
                if (bytes.size() > MAX_BODY - read) throw new ResponseTooLarge();
                bytes.write(buffer, 0, read);
            }
        }
    }

    private static String requiredSecret(JsonNode object, String field) {
        var value = optionalSecret(object, field);
        if (value == null) throw malformed();
        return value;
    }

    private static String optionalSecret(JsonNode object, String field) {
        if (object == null || !object.isObject()) throw malformed();
        var value = object.path(field);
        if (!value.isString() || !secret(value.asString())) return null;
        return value.asString();
    }

    private static String requiredVisible(JsonNode object, String... fields) {
        for (String field : fields) {
            var value = optionalSecret(object, field);
            if (value != null && value.length() <= MAX_CALLBACK_VALUE) return value;
        }
        throw malformed();
    }

    private static String requiredAccount(String account) {
        if (account == null || account.isBlank() || account.length() > MAX_ACCOUNT_ID || hasControl(account)) {
            throw auth("ChatGPT session must be reconnected");
        }
        return account;
    }

    private static String accountId(String access, String idToken) {
        var accessAccount = accountClaim(access);
        var idAccount = idToken == null ? null : accountClaim(idToken);
        if (accessAccount != null && idAccount != null && !constantTime(accessAccount, idAccount)) {
            throw auth("ChatGPT returned inconsistent account identifiers");
        }
        return accessAccount == null ? idAccount : accessAccount;
    }

    private static String accountClaim(String token) {
        try {
            var parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[1].isEmpty()) return null;
            var payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            var account = payload.path("https://api.openai.com/auth").path("chatgpt_account_id");
            if (!account.isString()) return null;
            var value = account.asString();
            return value.isBlank() || value.length() > MAX_ACCOUNT_ID || hasControl(value) ? null : value;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static long positiveSeconds(JsonNode value) {
        if (value == null || !(value.isNumber() || value.isString())) throw malformed();
        try {
            long seconds = Long.parseLong(value.asString());
            if (seconds > 0) return seconds;
        } catch (NumberFormatException invalid) { /* invalid provider field */ }
        throw malformed();
    }

    private static Duration deviceInterval(JsonNode value) {
        var interval = value.path("interval");
        if (interval.isMissingNode() || interval.isNull()) return POLL_FALLBACK;
        try {
            long seconds = Long.parseLong(interval.asString());
            if (seconds <= 0) return POLL_FALLBACK;
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException | ArithmeticException invalid) {
            throw malformed();
        }
    }

    private String random(int bytes) {
        var value = new byte[bytes];
        random.nextBytes(value);
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

    private static boolean jsonContentType(String value) {
        var mediaType = value == null ? "" : value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return mediaType.equals("application/json") || mediaType.endsWith("+json");
    }

    private static boolean secret(String value) { return value != null && !value.isBlank() && !hasControl(value); }
    private static boolean hasControl(String value) { return value.chars().anyMatch(character -> character <= 0x20 || character == 0x7f); }
    private static boolean constantTime(String first, String second) {
        return first != null && second != null && MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
    }
    private static BooleanSupplier cancellation(BooleanSupplier value) { return value == null ? () -> false : value; }
    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancelled();
    }
    private static LlmFailure statusFailure(int status, String message) {
        var kind = status == 429 ? LlmFailure.Kind.RATE_LIMIT
                : status >= 500 ? LlmFailure.Kind.SERVER
                : LlmFailure.Kind.AUTH;
        return new LlmFailure(kind, message, "", status, -1, null);
    }
    private static void requirePositive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) throw new IllegalArgumentException("OAuth lifetime must be positive");
    }
    private static LlmFailure unsupported(String message) {
        return new LlmFailure(LlmFailure.Kind.UNSUPPORTED, message);
    }
    private static void close(InputStream input) { try { input.close(); } catch (IOException ignored) { } }
    private static LlmFailure cancelled() { return new LlmFailure(LlmFailure.Kind.CANCELLED, "Cancelled"); }
    private static LlmFailure timeout(String message) { return new LlmFailure(LlmFailure.Kind.TIMEOUT, message); }
    private static LlmFailure network(String message) { return new LlmFailure(LlmFailure.Kind.NETWORK, message); }
    private static LlmFailure auth(String message) { return new LlmFailure(LlmFailure.Kind.AUTH, message); }
    private static LlmFailure malformed() { return new LlmFailure(LlmFailure.Kind.RESPONSE_FORMAT, "ChatGPT OAuth response is invalid"); }

    private static URI issuer(URI value) {
        var path = value == null ? null : value.getPath();
        if (value == null || value.getRawQuery() != null || value.getRawFragment() != null || value.getUserInfo() != null
                || value.getHost() == null || (path != null && !path.isEmpty() && !"/".equals(path))) {
            throw new IllegalArgumentException("Invalid ChatGPT OAuth issuer");
        }
        var scheme = value.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && loopback(value.getHost()))) {
            throw new IllegalArgumentException("ChatGPT OAuth issuer must use HTTPS");
        }
        return URI.create(value.toString().replaceAll("/$", ""));
    }

    private static URI callback(URI value) {
        if (value == null || !"http".equalsIgnoreCase(value.getScheme()) || value.getHost() == null || !loopback(value.getHost())
                || value.getPort() < 1 || value.getRawQuery() != null || value.getRawFragment() != null
                || !"/auth/callback".equals(value.getRawPath())) {
            throw new IllegalArgumentException("ChatGPT callback must be a loopback /auth/callback URI");
        }
        return value;
    }

    private static boolean loopback(String host) {
        if (host == null) return false;
        var lower = host.toLowerCase(java.util.Locale.ROOT);
        if (!lower.equals("localhost") && !lower.equals("127.0.0.1") && !lower.equals("::1")) return false;
        try { return InetAddress.getByName(host).isLoopbackAddress(); }
        catch (IOException unresolved) { return false; }
    }

    @Override
    @PreDestroy
    public void close() {
        workers.shutdownNow();
        http.shutdownNow();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration, BooleanSupplier cancelled) throws InterruptedException;
    }

    private record JsonReply(int status, JsonNode body) {
        boolean success() { return status >= 200 && status < 300; }
    }
    private record Callback(String code, boolean denied) {}
    private record Deadline(long atNanos) {
        static Deadline after(Duration duration) {
            requirePositive(duration);
            long now = System.nanoTime();
            long nanos;
            try { nanos = duration.toNanos(); }
            catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE; }
            return new Deadline(nanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + nanos);
        }
        boolean expired() { return System.nanoTime() >= atNanos; }
        Duration remaining(Duration maximum) {
            long left = atNanos - System.nanoTime();
            if (left <= 0) return null;
            var result = Duration.ofNanos(left);
            return maximum == null || result.compareTo(maximum) <= 0 ? result : maximum;
        }
    }

    private static final class ResponseTooLarge extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private static final class InterruptibleSleeper implements Sleeper {
        @Override
        public void sleep(Duration duration, BooleanSupplier cancelled) throws InterruptedException {
            var deadline = Deadline.after(duration);
            while (!deadline.expired()) {
                checkCancelled(cancelled);
                var remaining = deadline.remaining(null);
                if (remaining == null) return;
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining.toNanos(), WAIT_SLICE.toNanos()));
            }
            checkCancelled(cancelled);
        }
    }

    private static final class CallbackHandler implements HttpHandler {
        private final String path;
        private final String state;
        private final AtomicBoolean consumed;
        private final CompletableFuture<Callback> received;

        private CallbackHandler(String path, String state, AtomicBoolean consumed, CompletableFuture<Callback> received) {
            this.path = path;
            this.state = state;
            this.consumed = consumed;
            this.received = received;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equals(exchange.getRequestMethod()) || !path.equals(exchange.getRequestURI().getRawPath())) {
                    reply(exchange, 404, "Callback route not found.");
                    return;
                }
                var parameters = parameters(exchange.getRequestURI().getRawQuery());
                if (!constantTime(state, parameters.get("state"))) {
                    reply(exchange, 400, "Sign-in callback did not match this request.");
                    return;
                }
                if (!consumed.compareAndSet(false, true)) {
                    reply(exchange, 400, "Sign-in callback was already used.");
                    return;
                }
                var error = parameters.get("error");
                var code = parameters.get("code");
                if (error != null && !error.isBlank()) {
                    try {
                        reply(exchange, 200, "Sign-in was not completed. You can close this window.");
                    } finally {
                        received.complete(new Callback(null, true));
                    }
                } else if (!secret(code) || code.length() > MAX_CALLBACK_VALUE) {
                    try {
                        reply(exchange, 400, "Sign-in callback was incomplete.");
                    } finally {
                        received.complete(new Callback(null, true));
                    }
                } else {
                    try {
                        reply(exchange, 200, "Authorization received. Return to UNBI to check sign-in.");
                    } finally {
                        received.complete(new Callback(code, false));
                    }
                }
            } catch (RuntimeException malformed) {
                reply(exchange, 400, "Sign-in callback was invalid.");
            }
        }

        private static Map<String, String> parameters(String rawQuery) {
            if (rawQuery == null || rawQuery.length() > MAX_CALLBACK_VALUE) throw new IllegalArgumentException();
            var values = new LinkedHashMap<String, String>();
            if (rawQuery.isEmpty()) return values;
            for (String pair : rawQuery.split("&", -1)) {
                var split = pair.split("=", 2);
                var key = URLDecoder.decode(split[0], StandardCharsets.UTF_8);
                var value = split.length == 2 ? URLDecoder.decode(split[1], StandardCharsets.UTF_8) : "";
                if (key.isEmpty() || value.length() > MAX_CALLBACK_VALUE || values.putIfAbsent(key, value) != null) {
                    throw new IllegalArgumentException();
                }
            }
            return values;
        }

        private static void reply(HttpExchange exchange, int status, String text) throws IOException {
            var body = ("<!doctype html><html><body>" + text + "</body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }
    }
}
