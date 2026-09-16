package com.unbi.engine.llm.discovery;

import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import java.time.Duration;
import tools.jackson.databind.JsonNode;

/**
 * A call whose failure is not the answer.
 *
 * <p>An info node's verdict rests on one call. Everything after it — a balance, the hosts serving a
 * model — is an extra, and an extra that 404s must leave the node green with one row saying why.
 * {@link com.unbi.engine.llm.provider.HttpTransport#get} throws for every status ≥ 400 because a
 * <em>call</em> has no way to know which of them matters; this is where a probe decides that a 403
 * on a balance is a sentence rather than a failure.
 *
 * <p>The wording branches on the <b>status</b> and never on {@link LlmFailure.Kind}. The kinds exist
 * to answer "retry, change something, or stop" and they deliberately collapse cases that differ
 * here: 404 classifies as {@code MODEL_UNAVAILABLE}, which about a credits endpoint would say the
 * model is unavailable — a sentence that is not merely wrong, it sends the reader after the wrong
 * setting.
 */
public final class OptionalFetch {

    /**
     * What a 401 or a 403 on an extra reads as.
     *
     * <p>Named because a caller holding a fact that explains the refusal — "this key is not a
     * management key" — has to recognise this case to add it, and a second copy of the sentence at
     * the recognising end is a second place for the wording to drift.
     */
    public static final String NOT_READABLE = "not readable with this key";

    private OptionalFetch() {}

    /**
     * Fetches {@code path} if there is one and there is time, and otherwise says why not.
     *
     * @param path the profile's path for this extra; blank means this gateway has no such thing
     */
    public static Result of(
            GatewayDirectory gateways, EndpointSpec endpoint, String path, TimeBudget budget) {

        if (path == null || path.isBlank()) {
            return new Result(null, "this gateway publishes none", "");
        }
        if (budget.isExhausted()) {
            return new Result(null, "the probe ran out of time before asking", path);
        }
        try {
            return new Result(gateways.get(endpoint, path, budget.remaining()), "", path);
        } catch (LlmFailure failure) {
            return new Result(null, describe(failure), path);
        } catch (RuntimeException unexpected) {
            var message = unexpected.getMessage();
            return new Result(null, message == null ? unexpected.getClass().getSimpleName() : message, path);
        }
    }

    /** Fetches a path the caller has already decided is required to exist. */
    public static Result required(
            GatewayDirectory gateways, EndpointSpec endpoint, String path, Duration timeout) {
        try {
            return new Result(gateways.get(endpoint, path, timeout), "", path);
        } catch (LlmFailure failure) {
            return new Result(null, failure.describe(), path);
        } catch (RuntimeException unexpected) {
            var message = unexpected.getMessage();
            return new Result(null, message == null ? unexpected.getClass().getSimpleName() : message, path);
        }
    }

    private static String describe(LlmFailure failure) {
        return switch (failure.status()) {
            case 404 -> "no such endpoint on this gateway";
            case 401, 403 -> NOT_READABLE;
            default -> failure.describe();
        };
    }

    /**
     * @param body    what answered, or null
     * @param problem why nothing answered, in the user's words; blank when something did
     * @param path    what was asked, for the row that names the call
     */
    public record Result(JsonNode body, String problem, String path) {

        public Result {
            problem = problem == null ? "" : problem;
            path = path == null ? "" : path;
        }

        public boolean answered() {
            return body != null;
        }
    }
}
