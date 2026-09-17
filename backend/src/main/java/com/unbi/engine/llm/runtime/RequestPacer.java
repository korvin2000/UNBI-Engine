package com.unbi.engine.llm.runtime;

import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.RatePolicy;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Client-side throttle for one endpoint: a token bucket, a concurrency ceiling, and a floor on the
 * gap between two dispatches.
 *
 * <p>Being polite locally is cheaper than being rate limited remotely — a 429 costs a full round
 * trip and a backoff. And the three limits are not interchangeable: a bucket starts full, so sixty
 * per minute lets sixty requests leave in the same millisecond, which is exactly the arrival pattern
 * a gateway that mishandles overlap cannot survive. Only the spacing floor prevents it.
 *
 * <p>The spacing slot is reserved <em>synchronously</em>, before any sleeping. Two callers that each
 * read a shared "last dispatch" clock and then decide to wait both compute the same answer and both
 * leave together; claiming first means the second queues behind the first's reservation rather than
 * behind its own reading of the clock.
 */
public final class RequestPacer {

    private final RatePolicy policy;
    private final Clock clock;
    private final Semaphore concurrency;

    private double tokens;
    private long lastRefill;
    private long nextDispatchAt;

    public RequestPacer(RatePolicy policy) {
        this(policy, Clock.SYSTEM);
    }

    public RequestPacer(RatePolicy policy, Clock clock) {
        this.policy = policy == null ? RatePolicy.UNLIMITED : policy;
        this.clock = clock;
        this.concurrency = this.policy.maxConcurrent() > 0 ? new Semaphore(this.policy.maxConcurrent(), true) : null;
        this.tokens = this.policy.requestsPerMinute();
        this.lastRefill = clock.nowMillis();
    }

    /**
     * Waits until this request may be dispatched.
     *
     * @return a lease that must be closed, which is why the call site uses try-with-resources
     */
    public Lease acquire(BooleanSupplier cancelled) throws InterruptedException {
        Objects.requireNonNull(cancelled, "cancelled");
        var acquired = false;
        try {
            awaitPermit(cancelled);
            acquired = concurrency != null;
            awaitToken(cancelled);
            awaitSpacing(cancelled);
            return this::release;
        } catch (InterruptedException | RuntimeException failure) {
            // A cancellation can arrive after acquiring the concurrency permit but before dispatch.
            // Return it here rather than reducing the endpoint's ceiling for the process lifetime.
            if (acquired) {
                release();
            }
            throw failure;
        }
    }

    private void awaitPermit(BooleanSupplier cancelled) throws InterruptedException {
        if (concurrency == null) {
            checkCancelled(cancelled);
            return;
        }
        while (true) {
            checkCancelled(cancelled);
            if (concurrency.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
    }

    private void release() {
        if (concurrency != null) {
            concurrency.release();
        }
    }

    private void awaitToken(BooleanSupplier cancelled) throws InterruptedException {
        var perMinute = policy.requestsPerMinute();
        if (perMinute <= 0) {
            checkCancelled(cancelled);
            return;
        }
        while (true) {
            checkCancelled(cancelled);
            long waitMillis;
            synchronized (this) {
                refill(perMinute);
                if (tokens >= 1) {
                    tokens -= 1;
                    return;
                }
                waitMillis = (long) Math.ceil((1 - tokens) * (60_000d / perMinute));
            }
            sleepCancellable(waitMillis, cancelled);
        }
    }

    private void awaitSpacing(BooleanSupplier cancelled) throws InterruptedException {
        checkCancelled(cancelled);
        var spacing = policy.minRequestSpacingMillis();
        if (spacing <= 0) {
            return;
        }
        long waitMillis;
        synchronized (this) {
            var now = clock.nowMillis();
            var slot = Math.max(now, nextDispatchAt);
            nextDispatchAt = slot + spacing;
            waitMillis = slot - now;
        }
        sleepCancellable(waitMillis, cancelled);
    }

    private void sleepCancellable(long waitMillis, BooleanSupplier cancelled) throws InterruptedException {
        for (var remaining = waitMillis; remaining > 0; ) {
            checkCancelled(cancelled);
            var slice = Math.min(100, remaining);
            clock.sleep(slice);
            remaining -= slice;
        }
        checkCancelled(cancelled);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new LlmFailure(LlmFailure.Kind.CANCELLED, "Cancelled while waiting for a request slot");
        }
    }

    private void refill(int perMinute) {
        var now = clock.nowMillis();
        var elapsed = now - lastRefill;
        if (elapsed <= 0) {
            return;
        }
        lastRefill = now;
        tokens = Math.min(perMinute, tokens + (elapsed * perMinute) / 60_000d);
    }

    /** Released in a {@code finally}, always. */
    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    /** The one dependency that makes pacing testable without waiting for real seconds to pass. */
    public interface Clock {

        Clock SYSTEM = new Clock() {
            @Override
            public long nowMillis() {
                return System.currentTimeMillis();
            }

            @Override
            public void sleep(long millis) throws InterruptedException {
                Thread.sleep(millis);
            }
        };

        long nowMillis();

        void sleep(long millis) throws InterruptedException;
    }
}
