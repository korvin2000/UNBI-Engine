package com.unbi.engine.llm.discovery;

import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.Reasoning;
import java.util.List;
import java.util.Set;

/**
 * What a gateway says about one of its models, in this engine's vocabulary.
 *
 * <p>The three gateways this pack targets each describe a model differently — OpenRouter has
 * {@code supported_parameters} and per-token prices as strings, OmniRoute has a {@code capabilities}
 * object and an {@code api_format}, llama.cpp has {@code meta.n_ctx} and nothing else. Normalising
 * them here, once, is what lets the Model node fill itself in without learning a gateway's dialect,
 * and it is the same decision as "quirks are data" applied to discovery rather than to requests.
 *
 * <p>Every field is <em>optional</em> in the honest sense: a gateway that says nothing about pricing
 * leaves it null, and null means "not discovered" rather than "free". A discovery that invents a
 * number is worse than one that leaves the field alone, because the invented number is the one the
 * capability check will later hold the request to.
 *
 * @param label       what to show in a dropdown; the id when the gateway offers no better name
 * @param efforts     reasoning effort tiers this model accepts, in the gateway's own words
 * @param reasoningOn whether this model reasons unless told otherwise; null when nothing was said.
 *     Discovered alongside the dialect and never separately, because the two together are one fact:
 *     a dialect on its own means "say something about reasoning", and saying <em>off</em> to a model
 *     that reasons mandatorily is a 400 on every call. Measured, on a live gateway, as
 *     "Reasoning is mandatory for this endpoint and cannot be disabled".
 * @param defaultEffort the tier the gateway says it uses by default, or blank
 */
public record DiscoveredModel(
        String id,
        String label,
        ApiFormat apiFormat,
        Integer contextWindow,
        Integer maxOutputTokens,
        Set<Capability> capabilities,
        Reasoning.Dialect reasoningDialect,
        Boolean reasoningOn,
        List<String> efforts,
        String defaultEffort,
        ModelSpec.MaxTokensParam maxTokensParam,
        Double inputPer1M,
        Double outputPer1M) {

    public DiscoveredModel {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A discovered model needs an id");
        }
        id = id.trim();
        label = label == null || label.isBlank() ? id : label.trim();
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        efforts = List.copyOf(efforts == null ? List.of() : efforts);
        defaultEffort = defaultEffort == null ? "" : defaultEffort.trim();
    }

    /**
     * The effort tier to use, given what is already set.
     *
     * <p>The gateway's own default wins when it states one. Otherwise a tier the user already chose
     * is kept if this model accepts it, and only an unsupported one is replaced — with the middle of
     * the published list, which is the only choice that is neither the cheapest nor the most
     * expensive thing to pick on someone else's behalf.
     *
     * @return empty when nothing needs changing
     */
    public java.util.Optional<String> effortFor(String current) {
        if (!defaultEffort.isBlank()) {
            return java.util.Optional.of(defaultEffort);
        }
        if (efforts.isEmpty() || efforts.contains(current)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(efforts.get(efforts.size() / 2));
    }

    /** The dropdown entry: the name, and the id underneath it when they differ. */
    public String describe() {
        return label.equals(id) ? id : "%s — %s".formatted(label, id);
    }

    /** One line of evidence for the editor: what this model can do and what it costs. */
    public String summary() {
        var parts = new java.util.ArrayList<String>();
        if (contextWindow != null) {
            parts.add("%,d ctx".formatted(contextWindow));
        }
        if (maxOutputTokens != null) {
            parts.add("%,d out".formatted(maxOutputTokens));
        }
        if (!capabilities.isEmpty()) {
            parts.add(capabilities.stream().map(Capability::wireName).sorted().reduce((a, b) -> a + ", " + b).orElse(""));
        }
        if (inputPer1M != null && outputPer1M != null && (inputPer1M > 0 || outputPer1M > 0)) {
            parts.add("$%.3f in / $%.3f out per 1M".formatted(inputPer1M, outputPer1M));
        }
        return String.join(" · ", parts);
    }
}
