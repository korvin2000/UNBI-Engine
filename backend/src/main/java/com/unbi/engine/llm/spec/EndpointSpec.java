package com.unbi.engine.llm.spec;

import java.util.Locale;
import java.util.Map;

/**
 * A place to send requests, and the terms of sending them.
 *
 * <p>Deliberately holds a credential <em>reference</em> rather than a key. The reference is a name
 * resolved at call time against the credential store, which is what lets an endpoint be saved in a
 * workflow, exported as a preset and pasted into a ticket without leaking anything. Nothing in this
 * record is a secret, and {@link #toString()} therefore needs no special care.
 *
 * @param id              stable identity used for pacing and logs
 * @param profile         which built-in gateway profile this was configured from; informational
 * @param baseUrl         OpenAI-compatible base, e.g. {@code https://openrouter.ai/api/v1}
 * @param credentialRef   name looked up in the credential store; blank means unauthenticated
 * @param headers         extra headers sent with every request
 * @param stream          ask for a streamed response and reassemble it here
 * @param cachedTokenMode how this gateway counts cached prompt tokens
 * @param responsesPromptCache send Responses prompt-cache keys and breakpoints
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
        boolean responsesPromptCache) {

    public EndpointSpec {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("An endpoint needs a base URL");
        }
        baseUrl = stripTrailingSlash(baseUrl.trim());
        id = id == null || id.isBlank() ? baseUrl : id.trim();
        profile = profile == null ? "custom" : profile;
        authScheme = authScheme == null ? AuthScheme.NONE : authScheme;
        credentialRef = credentialRef == null ? "" : credentialRef.trim();
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        rate = rate == null ? RatePolicy.UNLIMITED : rate;
        timeoutMillis = timeoutMillis <= 0 ? 120_000 : timeoutMillis;
        cachedTokenMode = cachedTokenMode == null ? TokenUsage.CachedTokenMode.INCLUDED : cachedTokenMode;
    }

    /** The full URL for one wire format. */
    public String urlFor(ApiFormat format) {
        return baseUrl + format.path();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * How this endpoint proves who is asking.
     *
     * <p>Three, because three genuinely differ on the wire: llama.cpp takes nothing, most gateways
     * take a bearer key, and the Codex endpoint takes a bearer token plus an account id and two
     * fixed headers. A fourth scheme is a new case here and a new credential source beside it —
     * which is the seam a device-code or PKCE flow would use.
     */
    public enum AuthScheme {
        NONE("none", "No authentication"),
        BEARER("bearer", "API key"),
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
            if (raw == null) {
                return NONE;
            }
            var trimmed = raw.trim().toLowerCase(Locale.ROOT);
            for (var value : values()) {
                if (value.wireName.equals(trimmed)) {
                    return value;
                }
            }
            return NONE;
        }
    }
}
