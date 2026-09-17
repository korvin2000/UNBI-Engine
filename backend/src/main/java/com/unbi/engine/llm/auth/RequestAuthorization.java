package com.unbi.engine.llm.auth;

import com.unbi.engine.llm.spec.EndpointSpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The last-hop authorization for one endpoint request.
 *
 * <p>This value is deliberately the only place where a resolved credential meets an endpoint URL.
 * Its safe representation and {@link #toString()} never include a credential value.
 */
public final class RequestAuthorization {
    private static final String REDACTED = "<redacted>";

    private final String url;
    private final String safeUrl;
    private final Map<String, String> headers;
    private final String token;
    private final String authorizationValue;
    private final String encodedToken;

    private RequestAuthorization(
            String url,
            String safeUrl,
            Map<String, String> headers,
            String token,
            String authorizationValue,
            String encodedToken) {
        this.url = url;
        this.safeUrl = safeUrl;
        this.headers = Map.copyOf(headers);
        this.token = token;
        this.authorizationValue = authorizationValue;
        this.encodedToken = encodedToken;
    }

    /**
     * Builds the authorization for a relative endpoint path. A null credential is useful while an
     * endpoint is being inspected locally and therefore adds no credential-owned fields.
     */
    public static RequestAuthorization forEndpoint(EndpointSpec endpoint, Credential credential, String path) {
        Objects.requireNonNull(endpoint, "endpoint");
        validateEndpoint(endpoint);
        var target = endpointUrl(endpoint.baseUrl(), path);
        var headers = new LinkedHashMap<String, String>();
        endpoint.headers().forEach((name, value) -> mergeHeader(headers, name, value));

        if (endpoint.authScheme() == EndpointSpec.AuthScheme.NONE || credential == null || credential.isEmpty()) {
            return new RequestAuthorization(target, target, headers, "", "", "");
        }

        var token = credential.token();
        validateToken(token);
        credential.headers().forEach((name, value) -> {
            validateCredentialHeader(name, value);
            mergeHeader(headers, name, value);
        });

        return switch (endpoint.authScheme()) {
            case BEARER, CODEX, OAUTH2 -> withHeader(target, headers, "Authorization", "Bearer " + token, token, "Bearer " + token);
            case BASIC -> basic(target, headers, token);
            case API_KEY -> apiKey(endpoint, target, headers, token);
            case NONE -> new RequestAuthorization(target, target, headers, "", "", "");
        };
    }

    /** Builds a safe unauthenticated authorization for metadata requests. */
    public static RequestAuthorization unauthenticated(String url) {
        var safeUrl = validateAbsoluteUrl(url, "URL");
        return new RequestAuthorization(safeUrl, safeUrl, Map.of(), "", "", "");
    }

    /** Validates endpoint-owned authentication configuration and persisted extra headers. */
    public static void validateEndpoint(EndpointSpec endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        var seen = new LinkedHashMap<String, String>();
        for (var entry : endpoint.headers().entrySet()) {
            var name = entry.getKey();
            var value = entry.getValue();
            validatePersistedHeader(name, value);
            var previous = seen.putIfAbsent(normalizeHeader(name), name);
            if (previous != null) {
                throw invalid("Extra headers duplicate '%s' case-insensitively".formatted(name));
            }
        }
        var location = apiKeyLocation(endpoint.apiKeyLocation());
        var name = endpoint.apiKeyName();
        if (!location.equals("header") && !location.equals("query") && !location.equals("cookie")) {
            throw invalid("Unsupported API-key location '%s'".formatted(location));
        }
        if (name != null && !name.isBlank()) {
            switch (location) {
                case "header" -> {
                    validateHeaderName(name);
                    if (isRestrictedHeader(name) || isSecretBearingHeader(name)) {
                        throw invalid("The API-key header '%s' is restricted by HTTP transport".formatted(name));
                    }
                }
                case "query" -> validateQueryName(name);
                case "cookie" -> validateCookieName(name);
                default -> throw invalid("Unsupported API-key location '%s'".formatted(location));
            }
        }
        if (endpoint.authScheme() != EndpointSpec.AuthScheme.API_KEY) {
            return;
        }
        if (name == null || name.isBlank()) {
            throw invalid("API-key authentication needs an API-key name");
        }
        for (var header : endpoint.headers().keySet()) {
            if (location.equals("header") && header.equalsIgnoreCase(name)) {
                throw invalid("Extra headers must not set the configured API-key header '%s'".formatted(name));
            }
        }
    }

    /** URL used for the request. This is the only accessor that may contain a credential. */
    public String url() {
        return url;
    }

    /** Log-safe URL; query API-key values are redacted. */
    public String safeUrl() {
        return safeUrl;
    }

    /** Immutable request headers. */
    public Map<String, String> headers() {
        return headers;
    }

    /** Removes this request's raw and derived credential forms from a diagnostic. */
    public String redact(String message) {
        if (message == null || token.isEmpty()) {
            return message;
        }
        var redacted = message;
        if (!authorizationValue.isEmpty()) {
            redacted = redacted.replace(authorizationValue, REDACTED);
            if (authorizationValue.startsWith("Basic ")) {
                redacted = redacted.replace(authorizationValue.substring("Basic ".length()), REDACTED);
            }
        }
        if (!encodedToken.isEmpty()) {
            redacted = redacted.replace(encodedToken, REDACTED);
        }
        return redacted.replace(token, REDACTED);
    }

    @Override
    public String toString() {
        return "RequestAuthorization[url=%s, headers=%s]".formatted(safeUrl, headers.keySet());
    }

    private static RequestAuthorization apiKey(
            EndpointSpec endpoint, String target, Map<String, String> headers, String token) {
        return switch (apiKeyLocation(endpoint.apiKeyLocation())) {
            case "header" -> withHeader(target, headers, endpoint.apiKeyName(), token, token, token);
            case "query" -> withQuery(target, endpoint.apiKeyName(), token, headers);
            case "cookie" -> withCookie(target, headers, endpoint.apiKeyName(), token);
            default -> throw invalid("Unsupported API-key location '%s'".formatted(endpoint.apiKeyLocation()));
        };
    }

    private static RequestAuthorization basic(String target, Map<String, String> headers, String token) {
        var delimiter = token.indexOf(':');
        if (delimiter <= 0) {
            throw invalid("Basic credentials must contain a username and password");
        }
        var value = "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        return withHeader(target, headers, "Authorization", value, token, value);
    }

    private static RequestAuthorization withHeader(
            String target,
            Map<String, String> headers,
            String name,
            String value,
            String token,
            String authorizationValue) {
        validateHeaderName(name);
        validateHeaderValue(value);
        mergeHeader(headers, name, value);
        return new RequestAuthorization(target, target, headers, token, authorizationValue, percentEncode(token));
    }

    private static RequestAuthorization withQuery(
            String target, String name, String token, Map<String, String> headers) {
        var separator = target.contains("?") ? "&" : "?";
        var encodedName = percentEncode(name);
        var encodedToken = percentEncode(token);
        return new RequestAuthorization(
                target + separator + encodedName + "=" + encodedToken,
                target + separator + encodedName + "=" + REDACTED,
                headers,
                token,
                "",
                encodedToken);
    }

    private static RequestAuthorization withCookie(
            String target, Map<String, String> headers, String name, String token) {
        validateCookieValue(token);
        mergeHeader(headers, "Cookie", name + "=" + token);
        return new RequestAuthorization(target, target, headers, token, "", percentEncode(token));
    }

    private static String endpointUrl(String baseUrl, String path) {
        if (path == null || path.isBlank() || containsLineBreak(path)) {
            throw invalid("A request path is required");
        }
        var relative = URI.create(path);
        if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getRawUserInfo() != null
                || relative.getRawFragment() != null || !path.startsWith("/")) {
            throw invalid("Request paths must be relative absolute paths without authority or fragments");
        }
        if (relative.getPath().contains("\\"))
            throw invalid("Request paths must stay inside the endpoint base");
        for (var segment : relative.getPath().split("/")) {
            if (segment.equals(".") || segment.equals(".."))
                throw invalid("Request paths must stay inside the endpoint base");
        }
        return validateAbsoluteUrl(baseUrl + path, "request URL");
    }

    private static String validateAbsoluteUrl(String raw, String label) {
        if (raw == null || raw.isBlank() || containsLineBreak(raw)) {
            throw invalid("%s is required".formatted(label));
        }
        final URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException malformed) {
            throw invalid("%s is not a valid absolute HTTP URL".formatted(label));
        }
        if (uri.getScheme() == null || uri.getHost() == null
                || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw invalid("%s must be an absolute HTTP URL without userinfo or fragments".formatted(label));
        }
        return uri.toASCIIString();
    }

    private static String apiKeyLocation(String location) {
        return location == null || location.isBlank() ? "header" : location.trim().toLowerCase(Locale.ROOT);
    }

    private static void validatePersistedHeader(String name, String value) {
        validateHeaderName(name);
        validateHeaderValue(value);
        if (isRestrictedHeader(name) || isSecretBearingHeader(name)) {
            throw invalid("Extra header '%s' is restricted by HTTP transport".formatted(name));
        }
    }

    private static boolean isSecretBearingHeader(String name) {
        return name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Proxy-Authorization")
                || name.equalsIgnoreCase("Cookie") || name.equalsIgnoreCase("Set-Cookie");
    }

    private static void validateCredentialHeader(String name, String value) {
        validateHeaderName(name);
        validateHeaderValue(value);
        if (isRestrictedHeader(name) || isSecretBearingHeader(name)) {
            throw invalid("Credential header '%s' is restricted by HTTP transport".formatted(name));
        }
    }

    private static void validateHeaderName(String name) {
        if (name == null || name.isBlank() || containsLineBreak(name) || !isToken(name)) {
            throw invalid("Header names must use HTTP token syntax");
        }
    }

    private static void validateHeaderValue(String value) {
        if (value == null) {
            throw invalid("Header values contain unsupported characters");
        }
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f || c > 0xff) {
                throw invalid("Header values contain unsupported characters");
            }
        }
    }

    private static void validateToken(String token) {
        if (containsLineBreak(token)) {
            throw invalid("Credential values must not contain line breaks");
        }
    }

    private static void validateQueryName(String name) {
        if (name == null || name.isBlank() || containsLineBreak(name)) {
            throw invalid("An API-key query name is required");
        }
    }

    private static void validateCookieName(String name) {
        if (name == null || name.isBlank() || !isToken(name)) {
            throw invalid("Cookie names must use HTTP token syntax");
        }
    }

    private static void validateCookieValue(String value) {
        if (value.isEmpty()) {
            throw invalid("An API-key cookie value is required");
        }
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == '"' || c == ',' || c == ';' || c == '\\') {
                throw invalid("Cookie values contain unsupported characters");
            }
        }
    }

    private static boolean isRestrictedHeader(String name) {
        return switch (normalizeHeader(name)) {
            case "connection", "content-length", "expect", "host", "transfer-encoding", "upgrade" -> true;
            default -> false;
        };
    }

    private static boolean isToken(String value) {
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7f || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static String normalizeHeader(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static void mergeHeader(Map<String, String> headers, String name, String value) {
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        headers.put(name, value);
    }

    private static String percentEncode(String value) {
        var encoded = new StringBuilder(value.length());
        for (var b : value.getBytes(StandardCharsets.UTF_8)) {
            var c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append("0123456789ABCDEF".charAt(c >>> 4))
                        .append("0123456789ABCDEF".charAt(c & 0x0f));
            }
        }
        return encoded.toString();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
