package com.unbi.engine.llm.runtime;

import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.provider.ProviderRegistry;
import com.unbi.engine.llm.provider.StreamSink;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatResult;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * The single door every LLM call goes through.
 *
 * <p>In order: check compatibility, resolve the credential, wait for a dispatch slot, send, retry
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
        var credential = resolve(endpoint);
        var provider = providers.forFormat(call.model().apiFormat());
        var pacer = pacers.forEndpoint(endpoint);

        LlmFailure last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (sink.cancelled()) {
                throw new LlmFailure(LlmFailure.Kind.UNKNOWN, "Cancelled before the request was sent");
            }
            try (var lease = pacer.acquire()) {
                assert lease != null;
                var result = provider.complete(call, credential, sink);
                verify(result, call, policy);
                return result;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LlmFailure(
                        LlmFailure.Kind.TIMEOUT, "Interrupted while waiting for a request slot",
                        call.model().key(), 0, -1, interrupted);
            } catch (LlmFailure failure) {
                last = failure;
                if (!failure.isRetryable() || attempt == policy.maxAttempts()) {
                    throw failure;
                }
                var waitMillis = failure.retryAfterMillis() >= 0
                        ? failure.retryAfterMillis()
                        : policy.backoffFor(attempt);
                log.accept("Attempt %d of %d failed (%s). Retrying in %d ms."
                        .formatted(attempt, policy.maxAttempts(), failure.kind().label(), waitMillis));
                sleep(waitMillis, sink);
            }
        }
        throw last == null
                ? new LlmFailure(LlmFailure.Kind.UNKNOWN, "The request loop exited without a result")
                : last;
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

    private Credential resolve(EndpointSpec endpoint) {
        return switch (endpoint.authScheme()) {
            case NONE -> null;
            case BEARER -> credentials.require(
                    endpoint.credentialRef().isBlank() ? endpoint.id() : endpoint.credentialRef());
            case CODEX -> credentials.require(
                    endpoint.credentialRef().isBlank() ? "codex" : endpoint.credentialRef());
        };
    }

    private static void sleep(long millis, StreamSink sink) {
        // Slept in slices so a cancel during a backoff is honoured in well under a second rather
        // than after the whole wait a rate limiter asked for.
        var deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            if (sink.cancelled()) {
                throw new LlmFailure(LlmFailure.Kind.UNKNOWN, "Cancelled while waiting to retry");
            }
            try {
                Thread.sleep(Math.min(200, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LlmFailure(LlmFailure.Kind.TIMEOUT, "Interrupted while waiting to retry");
            }
        }
    }
}
