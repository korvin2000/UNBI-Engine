package com.unbi.engine.llm.auth;

import com.unbi.engine.llm.spec.LlmFailure;
import jakarta.annotation.PreDestroy;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The single adapter for generic OAuth token endpoints.
 *
 * <p>Authorization-code, refresh-token, and client-credentials exchanges deliberately use Spring
 * Security's programmatic grant clients. Device authorization is not supplied by Spring Security,
 * so its two RFC 8628 form requests use the same {@link RestClient}, OAuth error handler, and token
 * response converter. All results are engine-private session objects; this class never returns an
 * access, refresh, client, or device secret in an exception message.
 */
@org.springframework.stereotype.Component
public final class OAuthTokenClient implements AutoCloseable {
    private static final Duration MAX_REQUEST = Duration.ofSeconds(30);
    private static final int MAX_TOKEN_BODY = 64 * 1024;
    private static final String DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Clock clock;
    private final ExecutorService requests;
    private final HttpClient http;
    private final RestClient rest;
    private final ResponseCapture capture = new ResponseCapture();
    private final Sleeper sleeper;

    /** Production constructor. */
    public OAuthTokenClient() {
        this(Clock.systemUTC());
    }

    /** Deterministic timestamp seam for OAuth lifecycle tests. */
    public OAuthTokenClient(Clock clock) {
        this(clock, new InterruptibleSleeper());
    }

    OAuthTokenClient(Clock clock, Sleeper sleeper) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.requests = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .connectTimeout(MAX_REQUEST)
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(requests)
                .build();
        var tokenConverter = new OAuth2AccessTokenResponseHttpMessageConverter();
        var springConverter = new org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter();
        tokenConverter.setAccessTokenResponseConverter(parameters -> {
            capture.set(parameters);
            return springConverter.convert(parameters);
        });
        this.rest = RestClient.builder()
                .requestFactory((uri, method) -> {
                    var factory = new JdkClientHttpRequestFactory(http, requests);
                    factory.setReadTimeout(capture.remaining());
                    return factory.createRequest(uri, method);
                })
                .requestInterceptor((request, body, execution) -> bounded(execution.execute(request, body)))
                .configureMessageConverters(converters -> {
                    converters.registerDefaults();
                    converters.addCustomConverter(new org.springframework.http.converter.FormHttpMessageConverter());
                    converters.addCustomConverter(tokenConverter);
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    /** Exchanges a client-credentials grant using Spring Security's standard grant client. */
    public ObjectNode clientCredentials(ObjectNode configuration, Duration timeout) {
        var registration = registration(configuration, AuthorizationGrantType.CLIENT_CREDENTIALS, tokenUrl(configuration), null);
        var client = new RestClientClientCredentialsTokenResponseClient();
        client.setRestClient(rest);
        var result = execute(timeout, () -> client.getTokenResponse(new OAuth2ClientCredentialsGrantRequest(registration)));
        return token(result.value(), result.fields(), null);
    }

    /** Exchanges an authorization code with the mandatory PKCE verifier using Spring Security. */
    public ObjectNode authorizationCode(ObjectNode configuration, String code, String redirectUri, String verifier, Duration timeout) {
        if (blank(code) || blank(redirectUri) || blank(verifier)) throw malformed();
        var registration = registration(configuration, AuthorizationGrantType.AUTHORIZATION_CODE, tokenUrl(configuration), redirectUri);
        var request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(registration.getProviderDetails().getAuthorizationUri())
                .clientId(registration.getClientId())
                .redirectUri(redirectUri)
                .state("engine")
                .attributes(Map.of(PkceParameterNames.CODE_VERIFIER, verifier))
                .build();
        var response = OAuth2AuthorizationResponse.success(code).redirectUri(redirectUri).state("engine").build();
        var grant = new OAuth2AuthorizationCodeGrantRequest(registration, new OAuth2AuthorizationExchange(request, response));
        var client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(rest);
        var result = execute(timeout, () -> client.getTokenResponse(grant));
        return token(result.value(), result.fields(), null);
    }

    /** Refreshes a prior session using Spring Security's refresh-token client. */
    public ObjectNode refresh(ObjectNode configuration, ObjectNode previous, Duration timeout) {
        if (previous == null || blank(previous.path("accessToken").asString()) || blank(previous.path("refreshToken").asString())) {
            throw new Failure("invalid_grant", LlmFailure.Kind.AUTH, false, -1);
        }
        var registration = registration(configuration, AuthorizationGrantType.REFRESH_TOKEN, refreshUrl(configuration), null);
        var issuedAt = parseInstant(previous.path("issuedAt").asString());
        var expiresAt = parseInstant(previous.path("expiresAt").asString());
        var access = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, previous.path("accessToken").asString(), issuedAt, expiresAt);
        var refresh = new OAuth2RefreshToken(previous.path("refreshToken").asString(), issuedAt);
        var client = new RestClientRefreshTokenTokenResponseClient();
        client.setRestClient(rest);
        var result = execute(timeout, () -> client.getTokenResponse(new OAuth2RefreshTokenGrantRequest(registration, access, refresh)));
        return token(result.value(), result.fields(), previous);
    }

    /** Starts RFC 8628 device authorization; the returned record's device code remains private. */
    public DeviceAuthorization deviceAuthorization(ObjectNode configuration, Duration timeout) {
        var endpoint = endpoint(configuration, "deviceAuthorizationUrl");
        var request = form("client_id", text(configuration, "clientId"));
        var scopes = new java.util.ArrayList<String>();
        configuration.path("scopes").forEach(scope -> scopes.add(scope.asString()));
        if (!scopes.isEmpty()) request.set("scope", String.join(" ", scopes));
        var response = deviceForm(endpoint, configuration, request, timeout);
        var deviceCode = required(response, "device_code");
        var userCode = required(response, "user_code");
        var verification = required(response, "verification_uri");
        var verificationComplete = optional(response, "verification_uri_complete");
        long expires = positive(response, "expires_in");
        long interval = optionalPositive(response, "interval", 5);
        return new DeviceAuthorization(deviceCode, userCode, verification, verificationComplete,
                clock.instant().plusSeconds(expires), Duration.ofSeconds(interval));
    }

    /** Polls a private device challenge until it has a token, is cancelled, or reaches expiry. */
    public ObjectNode pollDevice(ObjectNode configuration, DeviceAuthorization challenge, BooleanSupplier cancelled) {
        Objects.requireNonNull(challenge, "challenge");
        BooleanSupplier stop = cancelled == null ? () -> false : cancelled;
        var interval = challenge.interval();
        while (clock.instant().isBefore(challenge.expiresAt())) {
            if (stop.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancelled();
            try {
                var result = deviceToken(configuration, challenge.deviceCode(), Duration.between(clock.instant(), challenge.expiresAt()));
                return token(result.value(), result.fields(), null);
            } catch (Failure failure) {
                if (failure.code().equals("authorization_pending")) {
                    sleep(interval, stop);
                } else if (failure.code().equals("slow_down")) {
                    interval = interval.plusSeconds(5);
                    sleep(interval, stop);
                } else if (failure.transientFailure()) {
                    interval = interval.multipliedBy(2);
                    sleep(interval, stop);
                } else {
                    throw failure;
                }
            }
        }
        throw new Failure("expired_token", LlmFailure.Kind.AUTH, false, -1);
    }

    private Captured<OAuth2AccessTokenResponse> deviceToken(ObjectNode configuration, String deviceCode, Duration remaining) {
        var endpoint = tokenUrl(configuration);
        var form = form("grant_type", DEVICE_GRANT, "device_code", deviceCode);
        return execute(remaining.compareTo(MAX_REQUEST) < 0 ? remaining : MAX_REQUEST, () -> rest.post().uri(endpoint).headers(headers -> clientAuthentication(headers, form, configuration))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form)
                .retrieve().body(OAuth2AccessTokenResponse.class));
    }

    private Map<?, ?> deviceForm(String endpoint, ObjectNode configuration, LinkedMultiValueMap<String, String> form, Duration timeout) {
        var result = execute(timeout, () -> rest.post().uri(endpoint).headers(headers -> clientAuthentication(headers, form, configuration))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON).body(form)
                .retrieve().body(Map.class));
        return result.value();
    }

    private void clientAuthentication(HttpHeaders headers, LinkedMultiValueMap<String, String> form, ObjectNode configuration) {
        form.set("client_id", text(configuration, "clientId"));
        switch (text(configuration, "clientAuthentication")) {
            case "none" -> { }
            case "client_secret_post" -> form.add("client_secret", text(configuration, "clientSecret"));
            case "client_secret_basic" -> headers.setBasicAuth(
                    java.net.URLEncoder.encode(text(configuration, "clientId"), StandardCharsets.UTF_8),
                    java.net.URLEncoder.encode(text(configuration, "clientSecret"), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            default -> throw malformed();
        }
    }

    private ObjectNode token(OAuth2AccessTokenResponse response, Map<String, Object> fields, ObjectNode previous) {
        if (response == null || fields == null) throw malformed();
        var access = response.getAccessToken();
        if (access == null || blank(access.getTokenValue())) throw malformed();
        var type = fields.get("token_type");
        if (!(type instanceof String tokenType) || !tokenType.equalsIgnoreCase("bearer")) {
            throw new Failure("unsupported_token_type", LlmFailure.Kind.UNSUPPORTED, false, -1);
        }
        var issuedAt = clock.instant();
        Instant expiresAt = null;
        if (fields.containsKey("expires_in")) {
            long seconds = positive(fields, "expires_in");
            try { expiresAt = issuedAt.plusSeconds(seconds); }
            catch (RuntimeException invalid) { throw malformed(); }
        }
        var session = JSON.createObjectNode().put("accessToken", access.getTokenValue()).put("tokenType", "Bearer")
                .put("issuedAt", issuedAt.toString()).put("status", "ready");
        if (expiresAt == null) session.putNull("expiresAt");
        else session.put("expiresAt", expiresAt.toString());
        if (fields.containsKey("refresh_token")) {
            var refresh = fields.get("refresh_token");
            if (!(refresh instanceof String value) || value.isBlank()) throw malformed();
            session.put("refreshToken", value);
        } else if (previous != null && previous.hasNonNull("refreshToken")) {
            var refresh = previous.path("refreshToken").asString();
            if (refresh.isBlank()) throw malformed();
            session.put("refreshToken", refresh);
        }
        return session;
    }

    private ClientRegistration registration(ObjectNode configuration, AuthorizationGrantType grant, String tokenUrl, String redirectUri) {
        var builder = ClientRegistration.withRegistrationId("managed")
                .authorizationGrantType(grant)
                .clientId(text(configuration, "clientId"))
                .clientSecret(configuration.path("clientSecret").asString())
                .clientAuthenticationMethod(authentication(configuration))
                .tokenUri(tokenUrl);
        if (redirectUri != null) builder.redirectUri(redirectUri);
        if (!configuration.path("authorizationUrl").asString("").isBlank())
            builder.authorizationUri(endpoint(configuration, "authorizationUrl"));
        var scopes = new LinkedHashSet<String>();
        for (JsonNode scope : configuration.path("scopes")) {
            if (!scope.isString() || scope.asString().isBlank()) throw malformed();
            scopes.add(scope.asString());
        }
        if (!scopes.isEmpty()) builder.scope(scopes);
        return builder.build();
    }

    private static ClientAuthenticationMethod authentication(ObjectNode configuration) {
        return switch (text(configuration, "clientAuthentication")) {
            case "none" -> ClientAuthenticationMethod.NONE;
            case "client_secret_post" -> ClientAuthenticationMethod.CLIENT_SECRET_POST;
            case "client_secret_basic" -> ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
            default -> throw malformed();
        };
    }
    private <T> Captured<T> execute(Duration requested, ThrowingSupplier<T> action) {
        var timeout = bounded(requested);
        var state = new RequestState(System.nanoTime() + timeout.toNanos());
        Future<Captured<T>> future = requests.submit(() -> {
            capture.begin(state);
            try {
                var value = action.get();
                return new Captured<>(value, capture.takeOrEmpty());
            } catch (OAuth2AuthorizationException oauth) {
                throw translate(oauth, capture.status());
            } catch (IllegalArgumentException invalid) {
                throw malformed();
            } finally { capture.clear(); }
        });
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cancelled();
        } catch (TimeoutException timedOut) {
            throw new Failure("temporarily_unavailable", LlmFailure.Kind.TIMEOUT, true, -1);
        } catch (ExecutionException failed) {
            if (System.nanoTime() >= state.deadline)
                throw new Failure("temporarily_unavailable", LlmFailure.Kind.TIMEOUT, true, -1);
            var cause = failed.getCause();
            if (cause instanceof Failure failure) throw failure;
            if (cause instanceof LlmFailure failure)
                throw new Failure("temporarily_unavailable", failure.kind(), failure.isRetryable(), failure.retryAfterMillis());
            if (cause instanceof org.springframework.web.client.ResourceAccessException)
                throw new Failure("temporarily_unavailable", LlmFailure.Kind.NETWORK, true, -1);
            throw malformed();
        } finally {
            state.stopped = true;
            var active = state.active;
            if (active != null) closeResponse(active);
            future.cancel(true);
        }
    }

    private Failure translate(OAuth2AuthorizationException oauth, int status) {
        var code = oauth.getError() == null ? "invalid_token_response" : oauth.getError().getErrorCode();
        if (code == null || code.isBlank()) code = "invalid_token_response";
        var retryAfter = capture.retryAfter();
        if (status == 0 && oauth.getCause() != null) {
            var causeName = oauth.getCause().getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            if (causeName.contains("resourceaccess") || causeName.contains("timeout")
                    || causeName.contains("connect") || causeName.contains("socket")) {
                return new Failure(code, causeName.contains("timeout") ? LlmFailure.Kind.TIMEOUT : LlmFailure.Kind.NETWORK, true, retryAfter);
            }
            return new Failure(code, LlmFailure.Kind.RESPONSE_FORMAT, false, retryAfter);
        }
        if (status == 429) return new Failure(code, LlmFailure.Kind.RATE_LIMIT, true, retryAfter);
        if (status >= 500) return new Failure(code, LlmFailure.Kind.SERVER, true, retryAfter);
        return switch (code) {
            case "invalid_grant", "invalid_client", "unauthorized_client", "access_denied" -> new Failure(code, LlmFailure.Kind.AUTH, false, retryAfter);
            case "invalid_scope", "invalid_request", "unsupported_grant_type", "invalid_token_response" -> new Failure(code, LlmFailure.Kind.INVALID_REQUEST, false, retryAfter);
            case "authorization_pending", "slow_down", "expired_token" -> new Failure(code, LlmFailure.Kind.AUTH, false, retryAfter);
            case "temporarily_unavailable", "server_error" -> new Failure(code, LlmFailure.Kind.SERVER, true, retryAfter);
            default -> new Failure("oauth_error", LlmFailure.Kind.AUTH, false, retryAfter);
        };
    }
    private ClientHttpResponse bounded(ClientHttpResponse response) throws IOException {
        var state = capture.current();
        state.active = response;
        if (state.stopped) { closeResponse(response); throw cancelled(); }
        var status = response.getStatusCode().value();
        capture.setStatus(status);
        if (status < 200 || (status >= 300 && status < 400)) {
            closeResponse(response);
            throw new Failure("invalid_token_response", LlmFailure.Kind.RESPONSE_FORMAT, false, -1);
        }
        if (status < 300) {
            var contentType = response.getHeaders().getContentType();
            if (contentType == null || !(contentType.isCompatibleWith(MediaType.APPLICATION_JSON)
                    || contentType.getSubtype().endsWith("+json"))) {
                closeResponse(response);
                throw malformed();
            }
        }
        var body = new LimitedInputStream(response.getBody());
        return new ClientHttpResponse() {
            @Override public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
                var status = response.getStatusCode();
                capture.setStatus(status.value());
                return status;
            }
            @Override public String getStatusText() throws IOException { return response.getStatusText(); }
            @Override public HttpHeaders getHeaders() {
                var headers = response.getHeaders();
                capture.setRetryAfter(headers.getFirst("Retry-After"));
                return headers;
            }
            @Override public InputStream getBody() { return body; }
            @Override public void close() {
                try { closeResponse(response); }
                finally { state.active = null; }
            }
        };
    }

    private static void closeResponse(ClientHttpResponse response) {
        // Spring's response.close() drains unread bytes. Close the body first so cancellation
        // never waits for an unbounded or stalled token response.
        try { response.getBody().close(); }
        catch (IOException ignored) { }
        response.close();
    }

    private static String endpoint(ObjectNode configuration, String field) {
        return ManagedCredentialStore.oauthEndpoint(text(configuration, field)).toString();
    }

    private static String tokenUrl(ObjectNode configuration) { return endpoint(configuration, "tokenUrl"); }
    private static String refreshUrl(ObjectNode configuration) {
        var configured = configuration.path("refreshUrl").asString();
        return configured.isBlank() ? tokenUrl(configuration) : ManagedCredentialStore.oauthEndpoint(configured).toString();
    }
    private static String text(ObjectNode configuration, String field) {
        if (configuration == null) throw malformed();
        var value = configuration.path(field);
        if (!value.isString() || value.asString().isBlank()) throw malformed();
        return value.asString();
    }
    private static String required(Map<?, ?> value, String field) {
        var result = optional(value, field);
        if (result == null) throw malformed();
        return result;
    }
    private static String optional(Map<?, ?> value, String field) {
        if (value == null) throw malformed();
        var result = value.get(field);
        return result instanceof String text && !text.isBlank() ? text : null;
    }
    private static long positive(Map<?, ?> value, String field) {
        var raw = value.get(field);
        try {
            long seconds = new java.math.BigDecimal(String.valueOf(raw)).longValueExact();
            if (seconds > 0) return seconds;
        } catch (NumberFormatException | ArithmeticException invalid) { /* Invalid protocol field. */ }
        throw malformed();
    }
    private static long optionalPositive(Map<?, ?> value, String field, long fallback) {
        return value.containsKey(field) ? positive(value, field) : fallback;
    }
    private static Instant parseInstant(String value) { try { return value == null || value.isBlank() ? null : Instant.parse(value); } catch (RuntimeException invalid) { return null; } }
    private static LinkedMultiValueMap<String, String> form(String... pairs) {
        var result = new LinkedMultiValueMap<String, String>();
        for (int index = 0; index < pairs.length; index += 2) result.add(pairs[index], pairs[index + 1]);
        return result;
    }
    private static Duration bounded(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw malformed();
        return timeout.compareTo(MAX_REQUEST) > 0 ? MAX_REQUEST : timeout;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void validateVerificationUrl(String value) {
        try { ManagedCredentialStore.oauthEndpoint(value); }
        catch (IllegalArgumentException invalid) { throw malformed(); }
    }
    private static Failure malformed() { return new Failure("invalid_token_response", LlmFailure.Kind.RESPONSE_FORMAT, false, -1); }
    private static Failure cancelled() { return new Failure("cancelled", LlmFailure.Kind.CANCELLED, false, -1); }
    private void sleep(Duration duration, BooleanSupplier cancelled) {
        try { sleeper.sleep(duration, cancelled); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw cancelled(); }
    }

    @Override
    @PreDestroy
    public void close() {
        requests.shutdownNow();
        http.shutdownNow();
    }

    /** Private device code is intentionally redacted even in incidental logging. */
    public record DeviceAuthorization(String deviceCode, String userCode, String verificationUrl,
                                      String verificationUrlComplete, Instant expiresAt, Duration interval) {
        public DeviceAuthorization {
            if (blank(deviceCode) || blank(userCode) || blank(verificationUrl) || expiresAt == null || interval == null || interval.isNegative() || interval.isZero()) throw malformed();
            validateVerificationUrl(verificationUrl);
            if (verificationUrlComplete != null && !verificationUrlComplete.isBlank()) validateVerificationUrl(verificationUrlComplete);
        }
        @Override public String toString() {
            return "DeviceAuthorization[userCode=" + userCode + ", verificationUrl=" + verificationUrl
                    + ", verificationUrlComplete=" + (verificationUrlComplete == null ? "" : verificationUrlComplete)
                    + ", expiresAt=" + expiresAt + ", interval=" + interval + ", deviceCode=<redacted>]";
        }
    }

    /** A safe OAuth failure: code is allow-listed and upstream descriptions never escape. */
    public static final class Failure extends LlmFailure {
        private static final long serialVersionUID = 1L;
        private static final Set<String> SAFE_CODES = Set.of("invalid_grant", "invalid_client", "unauthorized_client", "access_denied", "invalid_scope", "invalid_request", "unsupported_grant_type", "invalid_token_response", "authorization_pending", "slow_down", "expired_token", "temporarily_unavailable", "server_error", "unsupported_token_type", "oauth_error", "cancelled");
        private final String code;
        private final boolean transientFailure;
        private Failure(String code, Kind kind, boolean transientFailure, long retryAfterMillis) {
            super(kind, "OAuth token request failed", "", 0, retryAfterMillis, null);
            this.code = SAFE_CODES.contains(code) ? code : "oauth_error";
            this.transientFailure = transientFailure;
        }
        public String code() { return code; }
        public boolean transientFailure() { return transientFailure; }
    }

    @FunctionalInterface interface Sleeper { void sleep(Duration duration, BooleanSupplier cancelled) throws InterruptedException; }
    private record Captured<T>(T value, Map<String, Object> fields) {}
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }
    private static final class InterruptibleSleeper implements Sleeper {
        @Override public void sleep(Duration duration, BooleanSupplier cancelled) throws InterruptedException {
            long deadline = System.nanoTime() + duration.toNanos();
            while (true) {
                if (cancelled.getAsBoolean()) throw new InterruptedException();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
            }
        }
    }
    private static final class RequestState {
        final long deadline;
        Map<String, Object> fields = Map.of();
        int status;
        long retryAfter = -1;
        volatile ClientHttpResponse active;
        volatile boolean stopped;
        RequestState(long deadline) { this.deadline = deadline; }
    }
    private static final class ResponseCapture {
        private final ThreadLocal<RequestState> state = new ThreadLocal<>();
        void begin(RequestState value) { state.set(value); }
        RequestState current() { return Objects.requireNonNull(state.get()); }
        Duration remaining() {
            long nanos = current().deadline - System.nanoTime();
            if (nanos <= 0) throw new Failure("temporarily_unavailable", LlmFailure.Kind.TIMEOUT, true, -1);
            return Duration.ofMillis((nanos + 999_999) / 1_000_000);
        }
        void set(Map<String, Object> values) { current().fields = new LinkedHashMap<>(values); }
        void setStatus(int status) { current().status = status; }
        void setRetryAfter(String value) {
            if (value == null || value.isBlank()) return;
            try { current().retryAfter = Math.max(0L, Long.parseLong(value.trim()) * 1000L); }
            catch (NumberFormatException ignored) { }
        }
        int status() { return current().status; }
        long retryAfter() { return current().retryAfter; }
        void clear() { state.remove(); }
        Map<String, Object> takeOrEmpty() { return current().fields; }
    }
    private static final class LimitedInputStream extends FilterInputStream {
        private int read;
        LimitedInputStream(InputStream stream) { super(stream); }
        @Override public int read() throws IOException {
            int next = super.read();
            if (next != -1 && ++read > MAX_TOKEN_BODY) throw new IOException("OAuth token response too large");
            return next;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, Math.min(length, MAX_TOKEN_BODY - read + 1));
            if (count > 0 && (read += count) > MAX_TOKEN_BODY) throw new IOException("OAuth token response too large");
            return count;
        }
    }
}
