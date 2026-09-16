package com.unbi.engine.llm.discovery;

import com.unbi.engine.llm.spec.EndpointSpec;
import java.time.Duration;

/**
 * One time allowance for a whole probe, spent across the several calls it makes.
 *
 * <p>A budget rather than a timeout per call, because a timeout per call multiplies. An info node
 * asks a gateway three questions; three 120-second timeouts is a button that can hold the editor for
 * six minutes, and the user pressed it to find out whether the endpoint works — which they already
 * know by then.
 *
 * <p>Spending it in priority order is the caller's job and the interesting half of the design: the
 * call the verdict depends on goes first, so a slow gateway costs the optional extras rather than the
 * answer. What is left when the budget runs out is still worth showing, with a row saying which call
 * did not get its turn.
 */
public final class TimeBudget {

    /**
     * The ceiling on a probe however patient the endpoint profile is.
     *
     * <p>An endpoint's timeout is sized for a model writing an essay. A probe reads a JSON document.
     */
    private static final long CEILING_MILLIS = 30_000;

    /** Below this there is no point starting a call: a TLS handshake alone can spend it. */
    private static final Duration FLOOR = Duration.ofMillis(250);

    private final long deadlineNanos;

    public TimeBudget(Duration total) {
        this.deadlineNanos = System.nanoTime() + Math.max(0, total.toNanos());
    }

    /** The allowance for one probe or one run of an info node. */
    public static TimeBudget forProbe(EndpointSpec endpoint) {
        return new TimeBudget(Duration.ofMillis(Math.min(endpoint.timeoutMillis(), CEILING_MILLIS)));
    }

    /** What is left, never negative. */
    public Duration remaining() {
        var left = deadlineNanos - System.nanoTime();
        return left <= 0 ? Duration.ZERO : Duration.ofNanos(left);
    }

    public boolean isExhausted() {
        return remaining().compareTo(FLOOR) < 0;
    }
}
