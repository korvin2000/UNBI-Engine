package com.unbi.engine.llm.spec;

import java.util.List;

/**
 * What came back, at full fidelity.
 *
 * <p>Deliberately not the value that travels along an edge — the graph gets a flattened, display
 * shaped record instead. Keeping them apart means adding a field a provider reports does not change
 * the type another node sees.
 *
 * @param reportedModel    what the provider says answered, which may differ from what was asked for
 * @param providerCostUsd  cost the gateway reported; negative when it reported none, in which case
 *                         the model's own pricing is the estimate
 * @param searchPerformed  provider-side evidence of a completed search, never inferred from URLs
 *                         written in the model's prose
 */
public record ChatResult(
        String text,
        FinishReason finishReason,
        TokenUsage usage,
        String reportedModel,
        long latencyMillis,
        double providerCostUsd,
        boolean searchPerformed,
        List<Source> sources) {

    public ChatResult {
        text = text == null ? "" : text;
        finishReason = finishReason == null ? FinishReason.UNKNOWN : finishReason;
        usage = usage == null ? TokenUsage.NONE : usage;
        reportedModel = reportedModel == null ? "" : reportedModel;
        sources = List.copyOf(sources == null ? List.of() : sources);
    }

    /** The gateway's own figure when it gave one, otherwise the model's price list. */
    public double costUsd(Pricing pricing) {
        return providerCostUsd >= 0 ? providerCostUsd : pricing.estimate(usage);
    }

    /** True when the answer stops mid-sentence because it ran out of room. */
    public boolean wasTruncated() {
        return finishReason == FinishReason.LENGTH;
    }

    /** One web result the provider says it actually visited. */
    public record Source(String url, String title) {

        public Source {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("A source needs a URL");
            }
            title = title == null ? "" : title;
        }

        @Override
        public String toString() {
            return title.isBlank() ? url : title + " — " + url;
        }
    }
}
