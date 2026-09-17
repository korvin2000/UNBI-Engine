package com.unbi.engine.llm.runtime;

import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.auth.CredentialStore;
import java.time.Duration;
import com.unbi.engine.llm.provider.ProviderRegistry;
import com.unbi.engine.llm.provider.StreamSink;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatResult;
import com.unbi.engine.llm.spec.LlmFailure;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * The single door every LLM call goes through.
 *
 * <p>In order: check compatibility, wait for a dispatch slot, resolve the credential, send, retry
 * what is worth retrying, and refuse an answer that only looks like one. Nothing else in this
 * codebase talks to a provider, which is what makes pacing, error classification and the treatment
 * of a truncated answer uniform instead of re-decided in each node.
 *
 * <p>What is deliberately absent: routing pools, model fallback chains, budget guards. In a visual
 * graph the user picks the model by wiring one, and a fallback is a second branch they can see.
 * Hiding either inside this class would make the picture on the canvas stop being the truth.
 */
@Component
public class LlmCaller {

    private final ProviderRegistry providers;
    private final CredentialStore credentials;
    private final PacerRegistry pacers;

    public LlmCaller(ProviderRegistry providers, CredentialStore credentials, PacerRegistry pacers) {
        this.providers = providers;
        this.credentials = credentials;
        this.pacers = pacers;
    }

    public ChatResult call(ChatCall call, CallPolicy policy, StreamSink sink, Consumer<String> log) {
        CapabilityCheck.enforce(CapabilityCheck.inspect(call), policy.strictCapabilities(), log);

        var endpoint = call.model().endpoint();
        var provider = providers.forFormat(call.model().apiFormat());
        var pacer = pacers.forEndpoint(endpoint);
        var emitted = new AtomicBoolean();
        var trackedSink = tracking(sink, emitted);
        var session = credentials.session(
                endpoint,
                Duration.ofMillis(endpoint.timeoutMillis()),
                trackedSink::cancelled);

        LlmFailure last = null;
        int attempt = 1;
        while (attempt <= policy.maxAttempts()) {
            checkCancelled(trackedSink);
            Credential credential = null;
            boolean dispatched = false;
            try (var lease = pacer.acquire(trackedSink::cancelled)) {
                assert lease != null;
                checkCancelled(trackedSink);
                // Resolve after pacing: queued requests use the credential current at dispatch time.
                credential = session.resolve();
                checkCancelled(trackedSink);
                dispatched = true;
                var result = provider.complete(call, credential, trackedSink);
                checkCancelled(trackedSink);
                verify(result, call, policy);
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw cancelled("Cancelled while waiting for a request slot", interrupted);
            } catch (LlmFailure failure) {
                if (trackedSink.cancelled()) {
                    throw cancelled("Cancelled while the request was in flight", failure);
                }
                // Token acquisition/renewal is not a generation attempt. In particular an ambiguous
                // timed-out refresh POST must not be replayed by the provider retry policy.
                if (!dispatched) throw failure;
                last = failure;
                // A rejected credential gets one source-owned refresh and replay. This is not a
                // generation retry: it acquires a fresh pacer slot but leaves attempt unchanged.
                if (!emitted.get() && failure.status() == 401 && session.recover(failure, credential)) {
                    log.accept("Credential refreshed after an HTTP 401; replaying the request.");
                    continue;
                }
                // Once text reached the caller, another attempt would append a second answer to it.
                if (emitted.get() || !failure.isRetryable() || attempt == policy.maxAttempts()) {
                    throw failure;
                }
                var waitMillis = failure.retryAfterMillis() >= 0
                        ? failure.retryAfterMillis()
                        : policy.backoffFor(attempt);
                log.accept("Attempt %d of %d failed (%s). Retrying in %d ms."
                        .formatted(attempt, policy.maxAttempts(), failure.kind().label(), waitMillis));
                sleep(waitMillis, trackedSink);
                attempt++;
            }
        }
        throw last == null
                ? new LlmFailure(LlmFailure.Kind.UNKNOWN, "The request loop exited without a result")
                : last;
    }

    private static StreamSink tracking(StreamSink delegate, AtomicBoolean emitted) {
        return new StreamSink() {
            @Override
            public void chunk(String text) {
                // Mark before invoking user code: a callback that throws still received the chunk,
                // and retrying would duplicate output that may have been rendered already.
                emitted.set(true);
                delegate.chunk(text);
            }

            @Override
            public boolean cancelled() {
                return delegate.cancelled() || Thread.currentThread().isInterrupted();
            }

            @Override
            public void progress(long charactersSoFar) {
                delegate.progress(charactersSoFar);
            }
        };
    }

    private static void checkCancelled(StreamSink sink) {
        if (sink.cancelled()) {
            throw cancelled("Cancelled before the request was sent", null);
        }
    }

    private static LlmFailure cancelled(String message, Throwable cause) {
        return new LlmFailure(LlmFailure.Kind.CANCELLED, message, "", 0, -1, cause);
    }

    private static void sleep(long millis, StreamSink sink) {
        // Slept in slices so a cancel during a backoff is honoured in well under a second rather
        // than after the whole wait a rate limiter asked for.
        var deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (sink.cancelled()) {
                throw cancelled("Cancelled while waiting to retry", null);
            }
            try {
                var remaining = deadline - System.nanoTime();
                Thread.sleep(Math.min(100, Math.max(1, (remaining + 999_999L) / 1_000_000L)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw cancelled("Cancelled while waiting to retry", interrupted);
            }
        }
        checkCancelled(sink);
    }

    /**
     * An answer that arrived is not automatically an answer that is usable.
     *
     * <p>Both checks here exist because the failure they catch is silent otherwise: a truncated
     * answer is a well-formed HTTP 200 carrying half a document, and a model asked to search that
     * did not search returns confident prose with a plausible citation in it.
     */
    private static void verify(ChatResult result, ChatCall call, CallPolicy policy) {
        if (policy.failOnTruncation() && result.wasTruncated()) {
            var limit = call.effectiveMaxOutputTokens();
            throw new LlmFailure(
                    LlmFailure.Kind.OUTPUT_TRUNCATED,
                    limit > 0
                            ? "The answer was cut off by the output limit (%d tokens). Raise it, or ask for less."
                                    .formatted(limit)
                            // No limit was sent, so the ceiling that stopped it is the model's own
                            // and raising a setting here would change nothing.
                            : "The answer was cut off by the model's own output ceiling. Ask for less, "
                                    + "or split the work.");
        }
        if (policy.requireSearchEvidence() && call.webSearch().enabled() && !result.searchPerformed()) {
            throw new LlmFailure(
                    LlmFailure.Kind.RESPONSE_FORMAT,
                    "The model answered without provider evidence of a completed web search. "
                            + "URLs written in the answer are not evidence.");
        }
    }


}
