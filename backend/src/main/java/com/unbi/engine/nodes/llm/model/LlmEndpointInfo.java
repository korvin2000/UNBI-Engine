package com.unbi.engine.nodes.llm.model;

import com.unbi.engine.llm.discovery.Amounts;
import java.util.ArrayList;

/**
 * One gateway, as it described itself, travelling along an edge.
 *
 * <p>Flat and scalar-only on purpose. The obvious thing to do with this is wire it into Preview or
 * Generate Report, both of which reflect over record components and lay them out as a table — and a
 * table cell is one line. A nested object would render as {@code LlmKeyUsage[daily=0.42, …]} in a
 * column, which is a worse answer than the four columns it replaced. Lists travel on their own
 * {@code Text[]} output and as joined text in here, for the same reason.
 *
 * <p>{@code toString} is the multi-line summary rather than a record dump, following
 * {@link LlmResult}: Preview shows {@code toString()} for a lone record, and what someone wants to
 * see there is the report, not the field names.
 *
 * @param fetchedAt when this was asked, ISO-8601 — a fact about a gateway is only as good as its age
 * @param creditLimit the key's spending cap; 0 means the gateway stated none
 */
public record LlmEndpointInfo(
        String gateway,
        String baseUrl,
        boolean reachable,
        String fetchedAt,
        long modelsServed,
        String keyLabel,
        double creditLimit,
        double creditsRemaining,
        double usageTotal,
        double usageToday,
        boolean freeTier,
        long freeRequestsUsed,
        long freeRequestsLimit,
        long modelsWithVision,
        long modelsWithReasoning,
        long modelsWithTools,
        long modelsWithStructuredOutput,
        long modelsWithFileInput,
        String inputModalitiesCsv) {

    public LlmEndpointInfo {
        gateway = gateway == null ? "" : gateway;
        baseUrl = baseUrl == null ? "" : baseUrl;
        fetchedAt = fetchedAt == null ? "" : fetchedAt;
        keyLabel = keyLabel == null ? "" : keyLabel;
        inputModalitiesCsv = inputModalitiesCsv == null ? "" : inputModalitiesCsv;
    }

    /** One line for a log or a node footer. */
    public String summaryLine() {
        return "%s · %s · %s model%s".formatted(
                gateway, reachable ? "reachable" : "not reachable",
                Amounts.count(modelsServed), modelsServed == 1 ? "" : "s");
    }

    @Override
    public String toString() {
        var lines = new ArrayList<String>();
        lines.add("%s — %s".formatted(gateway, baseUrl));
        lines.add(reachable ? "Reachable." : "Not reachable.");
        if (modelsServed > 0) {
            lines.add("%s models served.".formatted(Amounts.count(modelsServed)));
        }
        if (!keyLabel.isEmpty()) {
            lines.add("Key: " + keyLabel);
        }
        if (creditLimit > 0) {
            lines.add("Credits: %s of %s remaining."
                    .formatted(Amounts.money(creditsRemaining), Amounts.money(creditLimit)));
        } else if (!keyLabel.isEmpty()) {
            lines.add("Credits: no limit.");
        }
        if (usageTotal > 0 || usageToday > 0) {
            lines.add("Usage: %s in total, %s today."
                    .formatted(Amounts.money(usageTotal), Amounts.money(usageToday)));
        }
        if (freeRequestsLimit > 0) {
            lines.add("Free-model requests: %s of %s used today."
                    .formatted(Amounts.count(freeRequestsUsed), Amounts.count(freeRequestsLimit)));
        }
        if (modelsServed > 0) {
            lines.add(("Of those models: %s with vision, %s with reasoning, %s with tools, "
                    + "%s with structured output, %s taking files.").formatted(
                            Amounts.count(modelsWithVision),
                            Amounts.count(modelsWithReasoning),
                            Amounts.count(modelsWithTools),
                            Amounts.count(modelsWithStructuredOutput),
                            Amounts.count(modelsWithFileInput)));
        }
        if (!inputModalitiesCsv.isEmpty()) {
            lines.add("Input modalities seen: " + inputModalitiesCsv + ".");
        }
        if (!fetchedAt.isEmpty()) {
            lines.add("Fetched " + fetchedAt + ".");
        }
        return String.join("\n", lines);
    }
}
