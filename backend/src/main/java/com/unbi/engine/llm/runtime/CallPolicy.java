package com.unbi.engine.llm.runtime;

/**
 * The decisions a request node takes about how hard to try and how fussy to be.
 *
 * @param strictCapabilities  fail on an incompatibility rather than degrading loudly
 * @param maxAttempts         total attempts including the first; 1 disables retrying
 * @param initialBackoffMillis first backoff; doubles per attempt, and a server's own
 *                            {@code Retry-After} wins over both
 * @param failOnTruncation    treat an answer cut off by the output limit as a failure. On by
 *                            default: a half-written JSON document that flows downstream as a
 *                            success is worse than a node that goes red
 * @param requireSearchEvidence reject an answer that claims a search it cannot evidence
 */
public record CallPolicy(
        boolean strictCapabilities,
        int maxAttempts,
        long initialBackoffMillis,
        boolean failOnTruncation,
        boolean requireSearchEvidence) {

    public static final CallPolicy DEFAULT = new CallPolicy(true, 3, 1000, true, false);

    public CallPolicy {
        maxAttempts = Math.max(1, maxAttempts);
        initialBackoffMillis = Math.max(0, initialBackoffMillis);
    }

    /** Backoff before the given attempt, doubling and capped so a run cannot stall for minutes. */
    public long backoffFor(int attempt) {
        return Math.min(30_000, initialBackoffMillis * (1L << Math.min(attempt - 1, 5)));
    }
}
