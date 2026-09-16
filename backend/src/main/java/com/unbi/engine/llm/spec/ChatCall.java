package com.unbi.engine.llm.spec;

import java.util.List;

/**
 * Everything one request to one model consists of.
 *
 * <p>The contract between a node and a provider. A node builds one of these and never learns which
 * wire format carries it; a provider reads one and never learns which node built it.
 *
 * @param cacheKey      stable key for requests sharing a prompt prefix; blank to send none
 * @param correlationId forwarded to gateways that accept a {@code user} field, so a call can be
 *                      found again in someone else's logs
 */
public record ChatCall(
        ModelSpec model,
        List<ChatMessage> messages,
        ResponseFormat responseFormat,
        SamplingParams sampling,
        WebSearch webSearch,
        String cacheKey,
        String correlationId) {

    public ChatCall {
        if (model == null) {
            throw new IllegalArgumentException("A call needs a model");
        }
        messages = List.copyOf(messages == null ? List.of() : messages);
        if (messages.stream().allMatch(ChatMessage::isEmpty)) {
            throw new IllegalArgumentException("A call needs at least one message with something in it");
        }
        responseFormat = responseFormat == null ? ResponseFormat.TEXT : responseFormat;
        sampling = sampling == null ? SamplingParams.UNSET : sampling;
        webSearch = webSearch == null ? WebSearch.OFF : webSearch;
        cacheKey = cacheKey == null ? "" : cacheKey;
        correlationId = correlationId == null ? "" : correlationId;
    }

    public static ChatCall of(ModelSpec model, List<ChatMessage> messages) {
        return new ChatCall(model, messages, ResponseFormat.TEXT, SamplingParams.UNSET, WebSearch.OFF, "", "");
    }

    /** Every attachment across every message, in order. */
    public List<Attachment> attachments() {
        return messages.stream().flatMap(message -> message.attachments().stream()).toList();
    }

    /**
     * The output ceiling this call will actually be sent, respecting the model's own limit.
     *
     * @return 0 when no ceiling is to be sent at all — which is what a model with no ceiling and a
     *     request that names none between them mean. A model <em>with</em> a ceiling still caps a
     *     request that asks for more, because that ceiling is a statement about the target rather
     *     than a preference.
     */
    public int effectiveMaxOutputTokens() {
        var requested = sampling.outputTokenLimit().orElse(0);
        if (!model.hasOutputCeiling()) {
            return Math.max(0, requested);
        }
        return requested > 0 ? Math.min(requested, model.maxOutputTokens()) : model.maxOutputTokens();
    }

    /**
     * Whether to search, and whether an answer without search evidence counts as a failure.
     *
     * @param required a response carrying no provider evidence of a completed search is rejected
     * @param contextSize {@code low} | {@code medium} | {@code high}; blank to send none
     */
    public record WebSearch(boolean enabled, boolean required, String contextSize) {

        public static final WebSearch OFF = new WebSearch(false, false, "");

        public WebSearch {
            contextSize = contextSize == null ? "" : contextSize.trim();
        }
    }
}
