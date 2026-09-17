package com.unbi.engine.llm.spec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What is known about one <em>kind</em> of gateway: how it authenticates, which wire format it
 * prefers, how it counts cached tokens, what to GET to prove a key works.
 *
 * <p>Deliberately holds <b>no address</b>. Where a gateway lives differs between a laptop, a LAN
 * box and a hosted account, and a default URL baked in here was measured producing exactly one
 * outcome: "Connection refused" against a localhost nobody was running, on every machine that was
 * not the author's. The base URL is a field of the endpoint profile the user saves, and nothing
 * else.
 *
 * <p>Everything here is a default the profile may override, and every override is a field the
 * user can see. A gateway that starts behaving differently is then a value to edit rather than a
 * branch to find.
 *
 * @param credentialRef the credential name to look for when the profile names none — a convention
 *     ({@code openrouter}, {@code openai}) that lets a shell already holding
 *     {@code OPENROUTER_API_KEY} work without a second setting
 * @param probePath     what to GET to prove the credential works, relative to the base URL.
 *     {@code /models} for almost everyone, and a field rather than a constant because OpenRouter
 *     serves its model catalogue <em>unauthenticated</em>: a listing there answers 200 for a key
 *     revoked an hour ago, which is the exact failure a test button exists to prevent
 * @param creditsPath   where an account balance lives, blank when this gateway publishes none.
 *     Blank is the honest default: llama.cpp has no balance to report, and a path guessed for it
 *     would turn "this gateway does not do that" into a 404 the user has to interpret
 * @param modelEndpointsPath which hosts serve one model, as a template containing {@code {slug}};
 *     blank when the gateway does not publish it. A template rather than a prefix and a suffix,
 *     because the id goes in whole — several OpenRouter ids contain a {@code /} and a {@code :},
 *     and splitting one to build a path is how {@code deepseek/deepseek-r1:free} becomes a 404
 */
public record ProviderProfile(
        String id,
        String label,
        EndpointSpec.AuthScheme authScheme,
        String credentialRef,
        Map<String, String> headers,
        boolean stream,
        TokenUsage.CachedTokenMode cachedTokenMode,
        boolean responsesPromptCache,
        ApiFormat defaultApiFormat,
        EndpointSpec.ResponsesDialect responsesDialect,
        RatePolicy rate,
        String notes,
        String probePath,
        String creditsPath,
        String modelEndpointsPath) {

    public ProviderProfile {
        credentialRef = credentialRef == null ? "" : credentialRef;
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        rate = rate == null ? RatePolicy.UNLIMITED : rate;
        responsesDialect = responsesDialect == null ? EndpointSpec.ResponsesDialect.STANDARD : responsesDialect;
        notes = notes == null ? "" : notes;
        probePath = probePath == null || probePath.isBlank() ? "/models" : probePath.trim();
        creditsPath = creditsPath == null ? "" : creditsPath.trim();
        modelEndpointsPath = modelEndpointsPath == null ? "" : modelEndpointsPath.trim();
    }

    /** Where a model listing lives. Always {@code /models}; the credential probe may differ. */
    public String modelsPath() {
        return "/models";
    }

    /** The built-ins, in the order the editor should offer them. */
    public static List<ProviderProfile> builtIn() {
        return List.of(
                new ProviderProfile(
                        "openrouter",
                        "OpenRouter",
                        EndpointSpec.AuthScheme.BEARER,
                        "openrouter",
                        Map.of("X-Title", "UNBI-Engine"),
                        true,
                        TokenUsage.CachedTokenMode.INCLUDED,
                        false,
                        ApiFormat.CHAT_COMPLETIONS,
                        EndpointSpec.ResponsesDialect.STANDARD,
                        new RatePolicy(0, 0, 4),
                        "One model id can be served by many hosts with different sampler support. "
                                + "Set provider order and Require parameter support on the model node "
                                + "for any call carrying more than a temperature.",
                        "/key",
                        "/credits",
                        "/models/{slug}/endpoints"),
                new ProviderProfile(
                        "llamacpp",
                        "llama.cpp (llama-server)",
                        EndpointSpec.AuthScheme.NONE,
                        "",
                        Map.of(),
                        false,
                        TokenUsage.CachedTokenMode.INCLUDED,
                        false,
                        ApiFormat.CHAT_COMPLETIONS,
                        EndpointSpec.ResponsesDialect.STANDARD,
                        new RatePolicy(0, 0, 1),
                        "A single-slot server is one lane no matter what this says, so max "
                                + "concurrent stays at 1 unless the server was started with more "
                                + "slots. top_k and min_p are native here.",
                        "/models", "", ""),
                new ProviderProfile(
                        "omniroute",
                        "OmniRoute",
                        EndpointSpec.AuthScheme.BEARER,
                        "omniroute",
                        Map.of(),
                        true,
                        TokenUsage.CachedTokenMode.ADDITIONAL,
                        false,
                        ApiFormat.CHAT_COMPLETIONS,
                        EndpointSpec.ResponsesDialect.STANDARD,
                        new RatePolicy(60, 50, 3),
                        "Streaming is on for a reason: two overlapping buffered requests have been "
                                + "measured returning each other's completions. A models listing is "
                                + "not a health check, and an identical body can come back from a "
                                + "response cache.",
                        "/models", "", ""),
                new ProviderProfile(
                        "openai",
                        "OpenAI",
                        EndpointSpec.AuthScheme.BEARER,
                        "openai",
                        Map.of(),
                        true,
                        TokenUsage.CachedTokenMode.INCLUDED,
                        true,
                        ApiFormat.RESPONSES,
                        EndpointSpec.ResponsesDialect.STANDARD,
                        RatePolicy.UNLIMITED,
                        "Reasoning models want max_completion_tokens rather than max_tokens; set it "
                                + "on the model node.",
                        "/models", "", ""),
                new ProviderProfile(
                        "codex",
                        "Codex (ChatGPT account)",
                        EndpointSpec.AuthScheme.CODEX,
                        "codex",
                        Map.of(),
                        true,
                        TokenUsage.CachedTokenMode.INCLUDED,
                        false,
                        ApiFormat.RESPONSES,
                        EndpointSpec.ResponsesDialect.CODEX,
                        new RatePolicy(0, 200, 1),
                        "Use Connect in UNBI for a native managed ChatGPT login and renewal. External "
                                + "Codex CLI credentials remain read-only. Requires Codex Responses.",
                        "/models", "", ""),
                new ProviderProfile(
                        "custom",
                        "Custom OpenAI-compatible",
                        EndpointSpec.AuthScheme.BEARER,
                        "",
                        Map.of(),
                        false,
                        TokenUsage.CachedTokenMode.INCLUDED,
                        false,
                        ApiFormat.CHAT_COMPLETIONS,
                        EndpointSpec.ResponsesDialect.STANDARD,
                        RatePolicy.UNLIMITED,
                        "Anything that speaks the OpenAI shape. Fill in the fields the gateway needs.",
                        "/models", "", ""));
    }

    public static Optional<ProviderProfile> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        var trimmed = id.trim();
        return builtIn().stream().filter(profile -> profile.id.equals(trimmed)).findFirst();
    }

    /** The kind named, or the neutral one — a node should never fail over an unknown kind. */
    public static ProviderProfile resolve(String id) {
        return byId(id).orElseGet(() -> byId("custom").orElseThrow());
    }
}
