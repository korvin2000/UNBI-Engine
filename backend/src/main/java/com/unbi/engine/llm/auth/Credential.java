package com.unbi.engine.llm.auth;

import java.util.Map;

/**
 * A resolved secret, on its way to exactly one request.
 *
 * <p>Never serialised, never logged, never returned over HTTP. {@link #toString()} redacts, because
 * the one thing that reliably leaks a key is something incidental printing an object that happened
 * to contain one — an exception message, a debug log, a map dumped into a report.
 *
 * @param ref     the name this was found under
 * @param token   the bearer value
 * @param headers additional headers this credential requires, e.g. an account id
 */
public record Credential(String ref, String token, Map<String, String> headers) {

    public Credential {
        ref = ref == null ? "" : ref;
        token = token == null ? "" : token;
        headers = Map.copyOf(headers == null ? Map.of() : headers);
    }

    public static Credential bearer(String ref, String token) {
        return new Credential(ref, token, Map.of());
    }

    public boolean isEmpty() {
        return token.isBlank();
    }

    @Override
    public String toString() {
        return "Credential[ref=%s, token=<redacted %d chars>, headers=%s]"
                .formatted(ref, token.length(), headers.keySet());
    }
}
