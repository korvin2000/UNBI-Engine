package com.unbi.engine.nodes.llm.model;

import com.unbi.engine.llm.spec.ChatResult;
import com.unbi.engine.llm.spec.ModelSpec;
import java.util.stream.Collectors;

/**
 * One answer, as it travels along an edge.
 *
 * <p>Flat and display-shaped, which is the difference between this and {@link ChatResult}. The
 * provider's result is allowed to grow a field whenever a gateway starts reporting something new;
 * this one is a contract that other nodes read, and a struct in {@code LlmTypes} mirrors it exactly.
 *
 * <p>{@code toString} is the answer itself rather than a record dump, because the obvious thing to
 * do with a result is wire it into Preview — and what someone wants to see there is what the model
 * said, not {@code LlmResult[text=...]}.
 */
public record LlmResult(
        String text,
        String finishReason,
        String model,
        long promptTokens,
        long completionTokens,
        long reasoningTokens,
        double costUsd,
        long latencyMillis,
        String sources) {

    public LlmResult {
        text = text == null ? "" : text;
        finishReason = finishReason == null ? "" : finishReason;
        model = model == null ? "" : model;
        sources = sources == null ? "" : sources;
    }

    public static LlmResult from(ChatResult result, ModelSpec model) {
        return new LlmResult(
                result.text(),
                result.finishReason().name().toLowerCase(java.util.Locale.ROOT),
                result.reportedModel().isBlank() ? model.name() : result.reportedModel(),
                result.usage().promptTokens(),
                result.usage().completionTokens(),
                result.usage().reasoningTokens(),
                result.costUsd(model.pricing()),
                result.latencyMillis(),
                result.sources().stream().map(ChatResult.Source::url).collect(Collectors.joining(" ")));
    }

    /** One line for a log or a node footer. */
    public String summary() {
        return "%s · %d→%d tokens · %.4f USD · %d ms"
                .formatted(model, promptTokens, completionTokens, costUsd, latencyMillis);
    }

    @Override
    public String toString() {
        return text;
    }
}
