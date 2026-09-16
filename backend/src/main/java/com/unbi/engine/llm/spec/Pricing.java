package com.unbi.engine.llm.spec;

/**
 * What a call costs, per million tokens.
 *
 * <p>Carried so a graph can show a number rather than a shrug. A gateway that reports its own cost
 * is trusted over this estimate, because pinning providers changes the bill and only the gateway
 * knows which host actually answered.
 *
 * @param cachedInputPer1M price of a cache hit; negative means "same as input"
 * @param reasoningPer1M   price of thinking tokens; negative means "same as output"
 */
public record Pricing(
        double inputPer1M, double outputPer1M, double cachedInputPer1M, double reasoningPer1M) {

    public static final Pricing FREE = new Pricing(0, 0, -1, -1);

    public static Pricing of(double inputPer1M, double outputPer1M) {
        return new Pricing(inputPer1M, outputPer1M, -1, -1);
    }

    public boolean isFree() {
        return inputPer1M == 0 && outputPer1M == 0;
    }

    /**
     * Estimated cost of one call.
     *
     * <p>Cached input is billed at its own rate and the rest at the input rate. Reasoning tokens are
     * already inside {@code completionTokens} on every gateway this pack talks to, so they are only
     * repriced when a separate reasoning rate was given.
     */
    public double estimate(TokenUsage usage) {
        var cachedRate = cachedInputPer1M < 0 ? inputPer1M : cachedInputPer1M;
        var reasoningRate = reasoningPer1M < 0 ? outputPer1M : reasoningPer1M;
        var freshInput = Math.max(0, usage.promptTokens() - usage.cachedPromptTokens());
        var plainOutput = Math.max(0, usage.completionTokens() - usage.reasoningTokens());
        return (freshInput * inputPer1M
                        + usage.cachedPromptTokens() * cachedRate
                        + plainOutput * outputPer1M
                        + usage.reasoningTokens() * reasoningRate)
                / 1_000_000d;
    }
}
