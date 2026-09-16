package com.unbi.engine.llm.spec;

import java.util.List;
import java.util.Optional;

/**
 * How the model should sample, with every field genuinely optional.
 *
 * <p>Optional matters here: "temperature 0" and "do not send a temperature" are different requests,
 * and on at least one gateway the second is the correct one because the first is validated and then
 * ignored. {@code Double}/{@code Integer} rather than primitives is what lets the encoder tell them
 * apart, and every field is dropped from the wire when it is null.
 *
 * <p>{@code topK} and {@code minP} are first-class rather than extras because they are the two a
 * gateway is most likely to accept and not apply — which is exactly what
 * {@link ProviderRouting#requireParameters()} exists to make audible.
 */
public record SamplingParams(
        Double temperature,
        Double topP,
        Integer topK,
        Double minP,
        Double frequencyPenalty,
        Double presencePenalty,
        Integer seed,
        List<String> stop,
        Integer maxOutputTokens) {

    /** Say nothing about sampling at all. */
    public static final SamplingParams UNSET =
            new SamplingParams(null, null, null, null, null, null, null, List.of(), null);

    public SamplingParams {
        stop = List.copyOf(stop == null ? List.of() : stop);
    }

    public boolean isUnset() {
        return temperature == null
                && topP == null
                && topK == null
                && minP == null
                && frequencyPenalty == null
                && presencePenalty == null
                && seed == null
                && stop.isEmpty()
                && maxOutputTokens == null;
    }

    /**
     * This overlaid on {@code base}: anything set here wins, anything unset keeps the base value.
     *
     * <p>The precedence a request node needs — model defaults underneath, the call's own override on
     * top — expressed once rather than as a merge at each call site.
     */
    public SamplingParams over(SamplingParams base) {
        if (base == null || base.isUnset()) {
            return this;
        }
        return new SamplingParams(
                temperature != null ? temperature : base.temperature,
                topP != null ? topP : base.topP,
                topK != null ? topK : base.topK,
                minP != null ? minP : base.minP,
                frequencyPenalty != null ? frequencyPenalty : base.frequencyPenalty,
                presencePenalty != null ? presencePenalty : base.presencePenalty,
                seed != null ? seed : base.seed,
                stop.isEmpty() ? base.stop : stop,
                maxOutputTokens != null ? maxOutputTokens : base.maxOutputTokens);
    }

    public Optional<Integer> outputTokenLimit() {
        return Optional.ofNullable(maxOutputTokens);
    }

    /** Builder-free construction for the common case of a node reading a handful of widgets. */
    public static SamplingParams of(Double temperature, Double topP, Integer maxOutputTokens) {
        return new SamplingParams(
                temperature, topP, null, null, null, null, null, List.of(), maxOutputTokens);
    }
}
