package com.unbi.engine.llm.spec;

/**
 * How fast this client is allowed to talk to one endpoint.
 *
 * <p>Three limits, because they are three different statements and a gateway may need any of them:
 *
 * <ul>
 *   <li>{@code requestsPerMinute} is a <em>budget</em>.
 *   <li>{@code minRequestSpacingMillis} is an <em>interval</em>. Not the same guarantee: a token
 *       bucket starts full, so sixty per minute happily lets sixty requests leave in the same
 *       millisecond and then waits. A gateway that mishandles simultaneous arrivals needs them
 *       spread out, which only an interval says.
 *   <li>{@code maxConcurrent} is a <em>ceiling</em>, and for a single-slot llama.cpp server it is a
 *       fact rather than a preference.
 * </ul>
 *
 * <p>Zero means unlimited in each case. Being polite locally is cheaper than being rate limited
 * remotely: a 429 costs a full round trip and a backoff.
 */
public record RatePolicy(int requestsPerMinute, int minRequestSpacingMillis, int maxConcurrent) {

    public static final RatePolicy UNLIMITED = new RatePolicy(0, 0, 0);

    public RatePolicy {
        requestsPerMinute = Math.max(0, requestsPerMinute);
        minRequestSpacingMillis = Math.max(0, minRequestSpacingMillis);
        maxConcurrent = Math.max(0, maxConcurrent);
    }

    public boolean isUnlimited() {
        return requestsPerMinute == 0 && minRequestSpacingMillis == 0 && maxConcurrent == 0;
    }
}
