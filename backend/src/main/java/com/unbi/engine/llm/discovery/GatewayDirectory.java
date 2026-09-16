package com.unbi.engine.llm.discovery;

import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.provider.HttpTransport;
import com.unbi.engine.llm.provider.LlmProvider;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.ProviderProfile;
import java.time.Duration;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Asking a gateway about itself: is it reachable, does the credential work, what does it serve?
 *
 * <p>This is what the editor's test and discover buttons are made of. Every question it asks is a
 * {@code GET} that changes nothing, which is what makes pressing the button safe — and why it is a
 * separate object from {@link com.unbi.engine.llm.runtime.LlmCaller} rather than a mode of it: a
 * call is paced, retried, billed and cancellable, and none of that belongs in "does this URL
 * answer?".
 *
 * <p>The credential is resolved the same way a real call resolves it, through the same store. A test
 * that skipped that step would be green for an endpoint whose key the engine cannot actually find,
 * which is the single most common reason a first run fails.
 */
@org.springframework.stereotype.Component
public class GatewayDirectory {

    /** A test the user is waiting on. Longer than this and the answer is "it is not working". */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    private final HttpTransport transport;
    private final CredentialStore credentials;

    public GatewayDirectory(HttpTransport transport, CredentialStore credentials) {
        this.transport = transport;
        this.credentials = credentials;
    }

    /**
     * Does this endpoint answer, and does its credential work?
     *
     * <p>Probes whatever the profile nominates rather than always the model listing, because on at
     * least one gateway the listing is public: testing against it would answer "healthy" for a key
     * that was revoked an hour ago.
     */
    public Reachability check(EndpointSpec endpoint) {
        var profile = ProviderProfile.resolve(endpoint.profile());
        var url = endpoint.baseUrl() + profile.probePath();
        try {
            var body = transport.get(url, headers(endpoint), PROBE_TIMEOUT);
            return new Reachability(true, describe(url, body), url, List.copyOf(evidence(body)));
        } catch (LlmFailure failure) {
            return new Reachability(false, failure.describe(), url, List.of());
        } catch (RuntimeException unexpected) {
            return new Reachability(false, message(unexpected), url, List.of());
        }
    }

    /** Every model this endpoint admits to serving, normalised. */
    public List<DiscoveredModel> models(EndpointSpec endpoint) {
        var profile = ProviderProfile.resolve(endpoint.profile());
        var url = endpoint.baseUrl() + profile.modelsPath();
        return ModelListingReader.read(transport.get(url, headers(endpoint), PROBE_TIMEOUT));
    }

    /**
     * The headers a real call would carry.
     *
     * <p>A missing credential is not fatal here. "No credential named 'openrouter'" is a far more
     * useful thing for a test to report than a connection it refused to attempt, and an endpoint
     * that needs no key must still be testable.
     */
    private java.util.Map<String, String> headers(EndpointSpec endpoint) {
        if (endpoint.authScheme() == EndpointSpec.AuthScheme.NONE || endpoint.credentialRef().isBlank()) {
            return LlmProvider.headers(endpoint, null);
        }
        // Resolved through the store exactly as a run resolves it, and failing the same way: a
        // gateway's 401 says nothing about *which* name the engine could not find, and that name
        // is the whole of what the user needs to fix.
        return LlmProvider.headers(endpoint, credentials.require(endpoint.credentialRef()));
    }

    /** What a healthy answer is worth saying. A model count is the most informative thing there is. */
    private static String describe(String url, JsonNode body) {
        var models = ModelListingReader.read(body);
        if (!models.isEmpty()) {
            return "Reachable — %d model%s offered.".formatted(models.size(), models.size() == 1 ? "" : "s");
        }
        return "Reachable — " + url.substring(url.lastIndexOf('/') + 1) + " answered.";
    }

    /**
     * The interesting parts of a key introspection, when that is what answered.
     *
     * <p>Read by key name rather than by gateway, so a body that happens to say what is left of a
     * quota reports it and one that does not stays silent.
     */
    private static List<String> evidence(JsonNode body) {
        var found = new java.util.ArrayList<String>();
        var data = body.path("data").isObject() ? body.path("data") : body;
        for (var field : List.of("label", "usage", "limit", "limit_remaining", "is_free_tier", "expires_at")) {
            var value = data.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                found.add("%s: %s".formatted(field.replace('_', ' '), value.asString(value.toString())));
            }
        }
        return found;
    }

    private static String message(RuntimeException failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /**
     * @param detail the gateway's own words about the credential, when it offered any
     */
    public record Reachability(boolean ok, String message, String url, List<String> detail) {}
}
