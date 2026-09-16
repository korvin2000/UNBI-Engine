package com.unbi.engine.llm.spec;

/**
 * What a call consumed.
 *
 * @param cachedPromptTokens subset of {@code promptTokens} served from the prompt cache
 * @param reasoningTokens    subset of {@code completionTokens} spent thinking
 */
public record TokenUsage(
        long promptTokens,
        long completionTokens,
        long cachedPromptTokens,
        long reasoningTokens,
        long totalTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                cachedPromptTokens + other.cachedPromptTokens,
                reasoningTokens + other.reasoningTokens,
                totalTokens + other.totalTokens);
    }

    /**
     * How a gateway counts cached tokens.
     *
     * <p>{@link #ADDITIONAL} exists because some gateways report cached tokens a second time on top
     * of {@code prompt_tokens}. Normalising here is what stops a cache hit — the thing that is
     * supposed to make a call cheaper — from inflating both the token count and the estimated bill.
     */
    public enum CachedTokenMode {
        INCLUDED,
        ADDITIONAL;

        public static CachedTokenMode of(String raw) {
            return raw != null && raw.trim().equalsIgnoreCase("additional") ? ADDITIONAL : INCLUDED;
        }
    }
}
