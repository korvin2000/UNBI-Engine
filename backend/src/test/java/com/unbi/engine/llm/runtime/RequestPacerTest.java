package com.unbi.engine.llm.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.RatePolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pacing, on a clock that does not make the suite slow.
 *
 * <p>The fake clock is the whole reason these properties are testable at all: asserting that sixty
 * requests a minute really means sixty would otherwise take a minute, so nobody would assert it.
 */
class RequestPacerTest {

    /**
     * Time moves when something sleeps — the shape a token bucket needs, since a bucket only
     * refills as the clock advances.
     */
    private static final class AdvancingClock implements RequestPacer.Clock {

        private final List<Long> sleeps = new ArrayList<>();
        private long now;

        @Override
        public synchronized long nowMillis() {
            return now;
        }

        @Override
        public synchronized void sleep(long millis) {
            sleeps.add(millis);
            now += millis;
        }

        synchronized long total() {
            return sleeps.stream().mapToLong(Long::longValue).sum();
        }
    }

    /**
     * Time stands still and a sleep is only recorded.
     *
     * <p>This is the clock the reservation tests need, and the reason is the thing being tested: two
     * callers that sleep concurrently do not spend their waits one after another, so a clock that
     * advanced on every sleep would make the very property under test unobservable.
     */
    private static final class FrozenClock implements RequestPacer.Clock {

        private final List<Long> sleeps = java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile long now;

        @Override
        public long nowMillis() {
            return now;
        }

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }

        List<Long> waits() {
            synchronized (sleeps) {
                return List.copyOf(sleeps);
            }
        }
    }

    @Nested
    class NoLimits {

        @Test
        void anUnlimitedPacerNeverWaits() throws InterruptedException {
            var clock = new AdvancingClock();
            var pacer = new RequestPacer(RatePolicy.UNLIMITED, clock);
            for (int i = 0; i < 100; i++) {
                pacer.acquire(() -> false).close();
            }
            assertThat(clock.sleeps).isEmpty();
        }
    }

    @Nested
    class TokenBucket {

        @Test
        @DisplayName("the bucket starts full, which is exactly why spacing is a separate setting")
        void theFirstRequestsGoStraightOut() throws InterruptedException {
            var clock = new AdvancingClock();
            var pacer = new RequestPacer(new RatePolicy(6, 0, 0), clock);
            for (int i = 0; i < 6; i++) {
                pacer.acquire(() -> false).close();
            }
            assertThat(clock.sleeps).isEmpty();
        }

        @Test
        void theSeventhRequestWaitsForARefill() throws InterruptedException {
            var clock = new AdvancingClock();
            var pacer = new RequestPacer(new RatePolicy(6, 0, 0), clock);
            for (int i = 0; i < 7; i++) {
                pacer.acquire(() -> false).close();
            }
            // Six a minute is one every ten seconds; the seventh waits about that long.
            assertThat(clock.total()).isBetween(9_000L, 11_000L);
        }
    }

    @Nested
    class DispatchSpacing {

        @Test
        @DisplayName("spacing is between dispatches, so each request is reserved an interval later")
        void requestsAreSpreadOut() throws InterruptedException {
            var clock = new FrozenClock();
            var pacer = new RequestPacer(new RatePolicy(0, 100, 0), clock);

            pacer.acquire(() -> false).close();
            pacer.acquire(() -> false).close();
            pacer.acquire(() -> false).close();

            assertThat(clock.waits().stream().mapToLong(Long::longValue).sum()).isEqualTo(300);
        }

        @Test
        @DisplayName("a request arriving after the interval has passed is not delayed at all")
        void aLateRequestWaitsForNothing() throws InterruptedException {
            var clock = new AdvancingClock();
            var pacer = new RequestPacer(new RatePolicy(0, 100, 0), clock);

            pacer.acquire(() -> false).close();
            clock.sleep(500);
            var before = clock.total();
            pacer.acquire(() -> false).close();

            assertThat(clock.total()).isEqualTo(before);
        }

        @Test
        @DisplayName("a slot is claimed before sleeping, so two callers queue instead of leaving together")
        void concurrentCallersDoNotShareASlot() throws Exception {
            var clock = new FrozenClock();
            var pacer = new RequestPacer(new RatePolicy(0, 100, 0), clock);
            pacer.acquire(() -> false).close();

            // Concurrent reservations must consume distinct spacing slots.
            var ready = new CountDownLatch(2);
            var done = new CountDownLatch(2);
            var failures = new AtomicInteger();
            Runnable ask = () -> {
                try {
                    ready.countDown();
                    ready.await();
                    pacer.acquire(() -> false).close();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            };
            Thread.ofVirtual().start(ask);
            Thread.ofVirtual().start(ask);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(failures).hasValue(0);

            assertThat(clock.waits().stream().mapToLong(Long::longValue).sum()).isEqualTo(300);
        }
    }

    @Nested
    class Concurrency {

        @Test
        @DisplayName("a single-slot server really is one lane, however many callers there are")
        void oneAtATimeMeansOneAtATime() throws Exception {
            var pacer = new RequestPacer(new RatePolicy(0, 0, 1));
            var inFlight = new AtomicInteger();
            var peak = new AtomicInteger();
            var done = new CountDownLatch(8);

            for (int i = 0; i < 8; i++) {
                Thread.ofVirtual().start(() -> {
                    try (var lease = pacer.acquire(() -> false)) {
                        assertThat(lease).isNotNull();
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        Thread.sleep(5);
                        inFlight.decrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(peak).hasValue(1);
        }

        @Test
        void aReleasedLeaseIsReusable() throws InterruptedException {
            var pacer = new RequestPacer(new RatePolicy(0, 0, 1));
            pacer.acquire(() -> false).close();
            pacer.acquire(() -> false).close();
            // Reaching here at all is the assertion: a lease that failed to release would deadlock.
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("cancellation after taking a permit returns it for the next caller")
        void cancellationReleasesPermitAfterAcquisition() throws Exception {
            var cancelled = new AtomicBoolean();
            var enteredWait = new CountDownLatch(1);
            var releaseWait = new CountDownLatch(1);
            var firstWait = new AtomicBoolean(true);
            var clock = new RequestPacer.Clock() {
                private long now;

                @Override
                public synchronized long nowMillis() {
                    return now;
                }

                @Override
                public synchronized void sleep(long millis) throws InterruptedException {
                    if (firstWait.getAndSet(false)) {
                        enteredWait.countDown();
                        releaseWait.await();
                        now += 60_000;
                    } else {
                        now += millis;
                    }
                }
            };
            var pacer = new RequestPacer(new RatePolicy(1, 0, 1), clock);
            pacer.acquire(() -> false).close();

            var failure = new AtomicReference<LlmFailure>();
            var done = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                try {
                    pacer.acquire(cancelled::get).close();
                } catch (LlmFailure expected) {
                    failure.set(expected);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            assertThat(enteredWait.await(1, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            releaseWait.countDown();
            assertThat(done.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(failure).hasValueSatisfying(error ->
                    assertThat(error.kind()).isEqualTo(LlmFailure.Kind.CANCELLED));

            // If the cancelled waiter leaked the one permit, this call would never complete.
            pacer.acquire(() -> false).close();
        }
    }

    @Nested
    class Sharing {

        @Test
        @DisplayName("one endpoint gets one pacer, because a rate limit belongs to the gateway")
        void theRegistryReusesAPacerPerKey() {
            var registry = new PacerRegistry();
            var first = registry.forKey("gateway", new RatePolicy(0, 0, 1));
            var second = registry.forKey("gateway", new RatePolicy(0, 0, 8));

            assertThat(second).isSameAs(first);
            assertThat(registry.forKey("other", RatePolicy.UNLIMITED)).isNotSameAs(first);
        }
    }
}
