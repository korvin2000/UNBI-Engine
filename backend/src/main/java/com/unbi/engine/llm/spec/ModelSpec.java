package com.unbi.engine.llm.spec;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One model, on one endpoint, with everything a call needs already resolved.
 *
 * <p>The split from {@link EndpointSpec} follows what changes together: several models share one
 * connection, and a model's capabilities, price and reasoning habits travel with the model rather
 * than with the wire it arrives on.
 *
 * @param name           the name the endpoint knows, e.g. {@code openai/gpt-5.6-luna}
 * @param maxOutputTokens the ceiling to ask for, or <b>0 for none at all</b>. Zero is a real answer
 *                       rather than a missing one: a gateway asked for no ceiling returns whatever
 *                       the model is willing to write, and forcing everyone to name a number means
 *                       every truncated answer traces back to a figure somebody guessed once.
 * @param maxTokensParam reasoning-era models reject {@code max_tokens}; some gateways reject the
 *                       newer name — so which one to send is a property of the target, not a guess
 * @param routing        provider preference for a gateway that serves one model from many hosts
 * @param extraBody      fields spread onto the request verbatim; the escape hatch that keeps this
 *                       record from growing a field per gateway
 */
public record ModelSpec(
        EndpointSpec endpoint,
        String name,
        ApiFormat apiFormat,
        Set<Capability> capabilities,
        Reasoning reasoning,
        WebSearchMode webSearchMode,
        Pricing pricing,
        int contextWindow,
        int maxOutputTokens,
        MaxTokensParam maxTokensParam,
        SamplingParams sampling,
        ProviderRouting routing,
        List<String> tags,
        Map<String, Object> extraBody) {

    public ModelSpec {
        if (endpoint == null) {
            throw new IllegalArgumentException("A model needs an endpoint");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A model needs a name the endpoint will recognise");
        }
        name = name.trim();
        apiFormat = apiFormat == null ? ApiFormat.CHAT_COMPLETIONS : apiFormat;
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        reasoning = reasoning == null ? Reasoning.UNSPECIFIED : reasoning;
        webSearchMode = webSearchMode == null ? WebSearchMode.NONE : webSearchMode;
        pricing = pricing == null ? Pricing.FREE : pricing;
        contextWindow = contextWindow <= 0 ? 128_000 : contextWindow;
        maxOutputTokens = Math.max(0, maxOutputTokens);
        maxTokensParam = maxTokensParam == null ? MaxTokensParam.AUTO : maxTokensParam;
        sampling = sampling == null ? SamplingParams.UNSET : sampling;
        routing = routing == null ? ProviderRouting.NONE : routing;
        tags = List.copyOf(tags == null ? List.of() : tags);
        extraBody = Map.copyOf(extraBody == null ? Map.of() : extraBody);
    }

    /** {@code <endpointId>:<name>} — the key pacing, logs and errors use. */
    public String key() {
        return endpoint.id() + ":" + name;
    }

    public boolean can(Capability capability) {
        return capabilities.contains(capability);
    }

    /** True when this model was given no output ceiling of its own. */
    public boolean hasOutputCeiling() {
        return maxOutputTokens > 0;
    }

    /**
     * The request field that carries the output ceiling, or empty when none should be sent.
     *
     * <p>{@link MaxTokensParam#AUTO} is the default because the right answer is derivable and
     * asking the user to know it is asking them to memorise which vendor renamed the field in which
     * generation. A model that declares reasoning wants the newer name; everything else wants the
     * older one, which is what every gateway in this pack's world accepts.
     */
    public java.util.Optional<String> outputLimitField() {
        return switch (maxTokensParam) {
            case NONE -> java.util.Optional.empty();
            case AUTO -> java.util.Optional.of(can(Capability.REASONING)
                    ? MaxTokensParam.MAX_COMPLETION_TOKENS.wireName()
                    : MaxTokensParam.MAX_TOKENS.wireName());
            default -> java.util.Optional.of(maxTokensParam.wireName());
        };
    }

    public ModelSpec withSampling(SamplingParams replacement) {
        return new ModelSpec(
                endpoint, name, apiFormat, capabilities, reasoning, webSearchMode, pricing,
                contextWindow, maxOutputTokens, maxTokensParam, replacement, routing, tags, extraBody);
    }

    /**
     * Which field carries the output ceiling — including the two answers that are not a field name.
     *
     * <p>{@code AUTO} derives it from the model, and {@code NONE} sends no ceiling at all. Both
     * exist because "how do I ask for no limit?" had no answer while this was a choice between two
     * spellings of the same mandatory number.
     */
    public enum MaxTokensParam {
        AUTO("auto"),
        MAX_TOKENS("max_tokens"),
        MAX_COMPLETION_TOKENS("max_completion_tokens"),
        NONE("none");

        private final String wireName;

        MaxTokensParam(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static MaxTokensParam of(String raw) {
            if (raw == null) {
                return AUTO;
            }
            var trimmed = raw.trim().toLowerCase(java.util.Locale.ROOT);
            for (var value : values()) {
                if (value.wireName.equals(trimmed)) {
                    return value;
                }
            }
            return AUTO;
        }
    }
}
