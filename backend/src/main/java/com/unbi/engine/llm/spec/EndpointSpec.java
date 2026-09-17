package com.unbi.engine.llm.spec;

import java.net.URI;
import java.util.Map;

/**
 * A place to send requests, and the terms of sending them.
 *
 * <p>Deliberately holds a credential <em>reference</em> rather than a key. The reference is a name
 * resolved at call time against the credential store, which is what lets an endpoint be saved in a
 * workflow, exported as a preset and pasted into a ticket without leaking anything. Nothing in this
 * record is a secret, and {@link #toString()} therefore needs no special care.
 *
 * @param id                    stable identity used for pacing and logs
 * @param profile               selected built-in gateway profile; constrains its supported wire format
 * @param baseUrl               OpenAI-compatible base, e.g. {@code https://openrouter.ai/api/v1}
 * @param credentialRef         name looked up in the credential store; blank means unauthenticated
 * @param headers               extra headers sent with every request
 * @param stream                ask for a streamed response and reassemble it here
 * @param cachedTokenMode       how this gateway counts cached prompt tokens
 * @param responsesPromptCache  send Responses prompt-cache keys and breakpoints
 * @param defaultApiFormat      resolved endpoint protocol, or null for a direct legacy spec
 * @param responsesDialect      resolved Responses wire dialect
 * @param apiKeyLocation        API-key placement: header, query, or cookie
 * @param apiKeyName            API-key field name, blank until configured
 */
public record EndpointSpec(
        String id,
        String profile,
        String baseUrl,
        AuthScheme authScheme,
        String credentialRef,
        Map<String, String> headers,
        RatePolicy rate,
        int timeoutMillis,
        boolean stream,
        TokenUsage.CachedTokenMode cachedTokenMode,
        boolean responsesPromptCache,
        ApiFormat defaultApiFormat,
        ResponsesDialect responsesDialect,
        String apiKeyLocation,
        String apiKeyName) {

    public EndpointSpec {
        baseUrl = canonicalBaseUrl(baseUrl);
        id = id == null || id.isBlank() ? baseUrl : id.trim();
        profile = profile == null ? "custom" : profile;
        authScheme = authScheme == null ? AuthScheme.NONE : authScheme;
        credentialRef = credentialRef == null ? "" : credentialRef.trim();
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        rate = rate == null ? RatePolicy.UNLIMITED : rate;
        timeoutMillis = timeoutMillis <= 0 ? 120_000 : timeoutMillis;
        cachedTokenMode = cachedTokenMode == null ? TokenUsage.CachedTokenMode.INCLUDED : cachedTokenMode;
        responsesDialect = responsesDialect == null ? ResponsesDialect.STANDARD : responsesDialect;
        apiKeyLocation = apiKeyLocation == null ? "header" : apiKeyLocation.trim();
        apiKeyName = apiKeyName == null ? "" : apiKeyName.trim();
    }

    /** The full URL for one wire format. */
    public String urlFor(ApiFormat format) {
        return baseUrl + format.path();
    }

    /** Refuses protocol combinations the selected gateway cannot carry. */
    public void validateApiFormat(ApiFormat format) {
        var gateway = ProviderProfile.resolve(profile);
        if (gateway.responsesDialect() == ResponsesDialect.CODEX) {
            if (format != gateway.defaultApiFormat()) {
                throw new IllegalArgumentException("The Codex gateway requires the Responses API format");
            }
            if (responsesDialect != gateway.responsesDialect()) {
                throw new IllegalArgumentException("The Codex gateway requires the Codex Responses dialect");
            }
            return;
        }
        if (responsesDialect == ResponsesDialect.CODEX && format != ApiFormat.RESPONSES) {
            throw new IllegalArgumentException("The Codex Responses dialect requires the Responses API format");
        }
    }

    private static String canonicalBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("An endpoint needs a base URL");
        }
        var baseUrl = raw.trim();
        final URI parsed;
        try {
            parsed = URI.create(baseUrl);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("The endpoint base URL is not valid");
        }
        if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("The endpoint base URL needs an http:// or https:// host");
        }
        if (parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("The endpoint base URL cannot contain userinfo, a query, or a fragment");
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    /** The specific Responses request shape this endpoint expects. */
    public enum ResponsesDialect {
        STANDARD("standard"),
        CODEX("codex");

        private final String wireName;

        ResponsesDialect(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static ResponsesDialect of(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Responses dialect must name a dialect");
            }
            var trimmed = raw.trim();
            for (var value : values()) {
                if (value.wireName.equalsIgnoreCase(trimmed)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown Responses dialect: " + trimmed);
        }
    }

    /**
     * How this endpoint proves who is asking.
     *
     * <p>Each scheme has distinct placement and credential semantics. Sources remain responsible for
     * producing a credential; request placement is centralized at the transport boundary.
     */
    public enum AuthScheme {
        NONE("none", "No authentication"),
        BEARER("bearer", "API key"),
        API_KEY("api_key", "Named API key"),
        BASIC("basic", "Username and password"),
        OAUTH2("oauth2", "OAuth 2.0"),
        CODEX("codex", "Codex account");

        private final String wireName;
        private final String label;

        AuthScheme(String wireName, String label) {
            this.wireName = wireName;
            this.label = label;
        }

        public String wireName() {
            return wireName;
        }

        public String label() {
            return label;
        }

        public static AuthScheme of(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("Authentication must name a scheme");
            }
            var trimmed = raw.trim();
            for (var value : values()) {
                if (value.wireName.equalsIgnoreCase(trimmed)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown authentication scheme: " + trimmed);
        }
    }
}
