package com.unbi.engine.llm.spec;

import java.util.Locale;
import java.util.Set;

/**
 * A call that did not work, classified.
 *
 * <p>The taxonomy exists so that "retry, try something else, or give up" is decided from a small
 * testable enum rather than by matching provider message strings at the call site. Gateways shape
 * their errors differently enough that classifying once, here, is the only way the decision stays
 * the same everywhere.
 */
public class LlmFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Kinds that mean the endpoint is unhealthy, rather than that one request was unsuitable. */
    private static final Set<Kind> RETRYABLE =
            Set.of(Kind.RATE_LIMIT, Kind.TIMEOUT, Kind.NETWORK, Kind.SERVER);

    private final Kind kind;
    private final int status;
    private final String target;
    private final long retryAfterMillis;

    public LlmFailure(Kind kind, String message) {
        this(kind, message, "", 0, -1, null);
    }

    public LlmFailure(
            Kind kind, String message, String target, int status, long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.kind = kind == null ? Kind.UNKNOWN : kind;
        this.target = target == null ? "" : target;
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public String target() {
        return target;
    }

    /** What the server asked us to wait, when it said so; negative when it did not. */
    public long retryAfterMillis() {
        return retryAfterMillis;
    }

    public boolean isRetryable() {
        return status != 401 && status != 403 && RETRYABLE.contains(kind);
    }

    /** The sentence a user reads when the node goes red. */
    public String describe() {
        var where = target.isBlank() ? "" : " on " + target;
        return "%s%s: %s".formatted(kind.label(), where, getMessage());
    }

    public enum Kind {
        /** 429 or an explicit rate limit — the same target will work after a wait. */
        RATE_LIMIT("Rate limited"),
        TIMEOUT("Timed out"),
        CANCELLED("Cancelled"),
        /** Socket, DNS or TLS failure before any response. */
        NETWORK("Network failure"),
        /** 5xx from the endpoint. */
        SERVER("Server error"),
        /** 401/403 — the credential is wrong or missing; retrying cannot help. */
        AUTH("Authentication failed"),
        /** A 400-class request error we caused. */
        INVALID_REQUEST("Rejected request"),
        /** The prompt did not fit. The remedy is less input, not another attempt. */
        CONTEXT_LENGTH("Prompt too long"),
        CONTENT_FILTER("Refused on policy grounds"),
        QUOTA("Quota or billing exhausted"),
        MODEL_UNAVAILABLE("Model unavailable"),
        /**
         * The answer hit the output ceiling and stops mid-sentence.
         *
         * <p>Distinct from a parse failure because the remedy is the opposite one: the request was
         * fine and the model was willing, so asking again buys the identical cut. Only a wider limit
         * or less to say helps.
         */
        OUTPUT_TRUNCATED("Answer was cut off"),
        /** The call succeeded and the body could not be used. */
        RESPONSE_FORMAT("Unusable answer"),
        /** The request asked for something the target does not support. */
        UNSUPPORTED("Unsupported request"),
        UNKNOWN("Failed");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Classifies an HTTP failure.
     *
     * <p>Message-level signals come first: a gateway may report the same context overflow as 400,
     * 413 or 422 depending on which upstream it is proxying, so the status alone is not enough.
     */
    public static Kind classify(int status, String body) {
        var text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        if (text.contains("context length")
                || text.contains("context_length")
                || text.contains("maximum context")
                || text.contains("too many tokens")
                || text.contains("prompt is too long")) {
            return Kind.CONTEXT_LENGTH;
        }
        if (text.contains("content filter") || text.contains("content_filter") || text.contains("content_policy")
                || text.contains("refusal") || text.contains("safety") || text.contains("flagged")) {
            return Kind.CONTENT_FILTER;
        }
        if (text.contains("insufficient") || text.contains("quota") || text.contains("billing")
                || text.contains("credit")) {
            return Kind.QUOTA;
        }
        if (text.contains("cannot be used with json") || text.contains("not supported")) {
            return Kind.UNSUPPORTED;
        }
        if (text.contains("rate_limit")) return Kind.RATE_LIMIT;
        if (text.contains("invalid_api_key") || text.contains("authentication_error")) return Kind.AUTH;
        if (text.contains("server_error")) return Kind.SERVER;
        return switch (status) {
            case 429 -> Kind.RATE_LIMIT;
            case 401, 403 -> Kind.AUTH;
            case 402 -> Kind.QUOTA;
            case 404 -> Kind.MODEL_UNAVAILABLE;
            case 408, 409 -> Kind.TIMEOUT;
            case 413, 422 -> Kind.CONTEXT_LENGTH;
            default -> {
                if (status >= 500) {
                    yield Kind.SERVER;
                }
                yield status >= 400 ? Kind.INVALID_REQUEST : Kind.UNKNOWN;
            }
        };
    }
}
