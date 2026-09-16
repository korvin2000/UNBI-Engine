package com.unbi.engine.nodes.llm.model;

import com.unbi.engine.llm.discovery.Amounts;
import java.util.ArrayList;

/**
 * One model, as its gateway described it, travelling along an edge.
 *
 * <p>Flat and scalar-only for the same reason as {@link LlmEndpointInfo}: a list of these is a table,
 * and a table cell is one line. The lists a model has — capabilities, modalities, parameters — travel
 * as joined text here and as their own {@code Text[]} outputs on the node, so a graph can iterate
 * over providers without this record growing a column nothing can display.
 *
 * <p>{@code toString} is the multi-line summary, so a lone record wired into Preview shows the report
 * rather than the field names.
 *
 * @param contextWindow what the host serving the call accepts, not the best any host offers; 0 when
 *     the gateway published neither
 * @param pricePerM     the pair as it is read — {@code $0.15 in / $0.60 out per M}, or
 *     {@code free in / free out per M}, and blank when the gateway quoted no price at all. Carried
 *     as text beside the numbers because a zero cannot say which of those two it is: "not published"
 *     and "free" are different facts and only one of them is a zero, so the numeric fields keep the
 *     arithmetic and this one keeps the fact
 * @param aliasOf       the model this id redirects to, blank when it redirects to nothing
 * @param pricingNote   what makes the headline price incomplete, blank when nothing does
 */
public record LlmModelInfo(
        String id,
        String name,
        String canonicalSlug,
        long contextWindow,
        long maxOutputTokens,
        double inputPer1M,
        double outputPer1M,
        String pricePerM,
        String capabilitiesCsv,
        String inputModalitiesCsv,
        String outputModalitiesCsv,
        long providerCount,
        String parametersCsv,
        String released,
        String knowledgeCutoff,
        boolean moderated,
        String aliasOf,
        String huggingFaceId,
        String tokenizer,
        String pricingNote,
        String description) {

    public LlmModelInfo {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        canonicalSlug = canonicalSlug == null ? "" : canonicalSlug;
        pricePerM = pricePerM == null ? "" : pricePerM;
        capabilitiesCsv = capabilitiesCsv == null ? "" : capabilitiesCsv;
        inputModalitiesCsv = inputModalitiesCsv == null ? "" : inputModalitiesCsv;
        outputModalitiesCsv = outputModalitiesCsv == null ? "" : outputModalitiesCsv;
        parametersCsv = parametersCsv == null ? "" : parametersCsv;
        released = released == null ? "" : released;
        knowledgeCutoff = knowledgeCutoff == null ? "" : knowledgeCutoff;
        aliasOf = aliasOf == null ? "" : aliasOf;
        huggingFaceId = huggingFaceId == null ? "" : huggingFaceId;
        tokenizer = tokenizer == null ? "" : tokenizer;
        pricingNote = pricingNote == null ? "" : pricingNote;
        description = description == null ? "" : description;
    }

    /** One line for a log or a node footer. */
    public String summaryLine() {
        return pricePerM.isEmpty() ? id : "%s · %s".formatted(id, pricePerM);
    }

    @Override
    public String toString() {
        var lines = new ArrayList<String>();
        lines.add(name.isEmpty() ? id : "%s — %s".formatted(name, id));
        if (!aliasOf.isEmpty()) {
            lines.add("Resolves to " + aliasOf + ".");
        }
        if (contextWindow > 0 || maxOutputTokens > 0) {
            lines.add("Context %s tok, output ceiling %s tok.".formatted(
                    contextWindow > 0 ? Amounts.count(contextWindow) : "unpublished",
                    maxOutputTokens > 0 ? Amounts.count(maxOutputTokens) : "unpublished"));
        }
        if (!pricePerM.isEmpty()) {
            lines.add("Priced at " + pricePerM + (pricingNote.isEmpty() ? "." : " — " + pricingNote + "."));
        }
        if (!capabilitiesCsv.isEmpty()) {
            lines.add("Declares: " + capabilitiesCsv + ".");
        }
        if (!inputModalitiesCsv.isEmpty()) {
            lines.add("Takes " + inputModalitiesCsv
                    + (outputModalitiesCsv.isEmpty() ? "." : ", answers " + outputModalitiesCsv + "."));
        }
        if (providerCount > 0) {
            lines.add("%s provider%s serving it.".formatted(
                    Amounts.count(providerCount), providerCount == 1 ? "" : "s"));
        }
        if (!released.isEmpty()) {
            lines.add("Released " + released
                    + (knowledgeCutoff.isEmpty() ? "." : ", knowledge to " + knowledgeCutoff + "."));
        }
        if (!description.isEmpty()) {
            lines.add("");
            lines.add(description);
        }
        return String.join("\n", lines);
    }
}
