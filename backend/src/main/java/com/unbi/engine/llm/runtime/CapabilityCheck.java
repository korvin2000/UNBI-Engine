package com.unbi.engine.llm.runtime;

import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.ResponseFormat;
import com.unbi.engine.llm.spec.WebSearchMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares what a call asks for against what the target says it can do, before anything is sent.
 *
 * <p>This is the answer to the requirement that incompatibilities be surfaced rather than silently
 * discarded — and it is not a formality. A response format sent to a model that ignores it produces
 * prose where JSON was expected, one sent to a model that rejects it produces a 400 on every call
 * that reads like an ordinary provider outage, and neither is visible from the graph. Saying so
 * here, in the node, with the setting named, is the difference.
 *
 * <p>Findings carry a severity rather than throwing, because the caller decides. Strict mode fails
 * the node; lenient mode drops the unsupported feature and says loudly that it did.
 */
public final class CapabilityCheck {

    private CapabilityCheck() {}

    public static List<Finding> inspect(ChatCall call) {
        var model = call.model();
        var findings = new ArrayList<Finding>();

        var required = call.responseFormat().requiredCapability();
        if (required != null && !model.can(required)) {
            findings.add(Finding.error(
                    "Response format",
                    "%s does not declare %s, so the format would be dropped on the wire and the "
                            .formatted(model.name(), required.wireName())
                            + "answer would come back as prose. Declare the capability on the model "
                            + "node if the endpoint really supports it."));
        }
        if (call.responseFormat() instanceof ResponseFormat.JsonSchema && model.apiFormat() == ApiFormat.RESPONSES
                && !model.can(Capability.JSON_SCHEMA)) {
            findings.add(Finding.warning(
                    "Response format", "Responses targets need json_schema declared for structured output."));
        }

        checkAttachments(call, findings);
        checkWebSearch(call, findings);
        checkSampling(call, findings);
        checkBudget(call, findings);
        return List.copyOf(findings);
    }

    private static void checkAttachments(ChatCall call, List<Finding> findings) {
        var model = call.model();
        for (var attachment : call.attachments()) {
            var needed = attachment.requiredCapability();
            if (needed != null && !model.can(needed)) {
                findings.add(Finding.error(
                        "Attachments",
                        "%s cannot be sent: %s does not declare %s."
                                .formatted(attachment.name(), model.name(), needed.wireName())));
            }
        }
    }

    private static void checkWebSearch(ChatCall call, List<Finding> findings) {
        if (!call.webSearch().enabled()) {
            return;
        }
        var model = call.model();
        if (!model.can(Capability.WEB_SEARCH)) {
            findings.add(Finding.error(
                    "Web search",
                    "%s does not declare web_search. A model without search answers the same "
                                    .formatted(model.name())
                            + "question fluently, with a citation, from memory."));
        }
        if (model.webSearchMode() == WebSearchMode.NONE) {
            findings.add(Finding.error(
                    "Web search",
                    "No search mode is set on the model, so nothing would actually be sent to "
                            + "request a search."));
        }
        if (model.webSearchMode() == WebSearchMode.RESPONSES_TOOL && model.apiFormat() != ApiFormat.RESPONSES) {
            findings.add(Finding.error(
                    "Web search", "The hosted search tool only exists on the Responses API; "
                            + "set the model's API format to Responses."));
        }
        if (model.webSearchMode() == WebSearchMode.ONLINE && !model.name().endsWith(":online")) {
            findings.add(Finding.warning(
                    "Web search",
                    "Search mode is 'online' but the model name has no :online suffix, so the "
                            + "gateway will not add a search step."));
        }
        // Measured on the configured gateways: they answer 400 to the combination outright.
        if (call.responseFormat().isJson()) {
            findings.add(Finding.error(
                    "Web search",
                    "Web search and JSON mode cannot be combined on these gateways. Ask for JSON in "
                            + "the prompt instead, and set the response format to Text."));
        }
        if (call.webSearch().required()
                && model.webSearchMode() != WebSearchMode.RESPONSES_TOOL
                && model.webSearchMode() != WebSearchMode.HOSTED) {
            findings.add(Finding.warning(
                    "Web search",
                    "Only the hosted tool returns provider-side evidence, so 'require evidence' "
                            + "may reject answers that did search."));
        }
    }

    private static void checkSampling(ChatCall call, List<Finding> findings) {
        var model = call.model();
        var sampling = call.sampling();
        if (model.apiFormat() == ApiFormat.RESPONSES && (sampling.topK() != null || sampling.minP() != null)) {
            findings.add(Finding.warning(
                    "Generation params",
                    "The Responses API does not accept top_k or min_p; they will not be sent."));
        }
        var carriesMoreThanTemperature = sampling.topK() != null
                || sampling.minP() != null
                || sampling.frequencyPenalty() != null
                || sampling.presencePenalty() != null;
        if (carriesMoreThanTemperature
                && !model.routing().isEmpty()
                && !Boolean.TRUE.equals(model.routing().requireParameters())) {
            findings.add(Finding.warning(
                    "Provider routing",
                    "This call carries samplers beyond temperature and provider routing does not "
                            + "require parameter support, so a host that ignores them can serve it "
                            + "and nothing will say so."));
        }
        if (model.reasoning().speaks() && model.reasoning().enabled() && !model.can(Capability.REASONING)) {
            findings.add(Finding.warning(
                    "Reasoning",
                    "%s does not declare reasoning, but the call asks it to think.".formatted(model.name())));
        }
    }

    private static void checkBudget(ChatCall call, List<Finding> findings) {
        var model = call.model();
        var requested = call.sampling().outputTokenLimit().orElse(0);
        if (!model.hasOutputCeiling()) {
            // No ceiling is a deliberate configuration, not an omission to warn about. The only
            // thing left to check needs a number, and there is none.
            return;
        }
        if (requested > model.maxOutputTokens()) {
            findings.add(Finding.warning(
                    "Output limit",
                    "Asked for %d output tokens; %s caps at %d, so that is what will be sent."
                            .formatted(requested, model.name(), model.maxOutputTokens())));
        }
        if (call.effectiveMaxOutputTokens() >= model.contextWindow()) {
            findings.add(Finding.error(
                    "Output limit",
                    "The output limit (%d) is not smaller than the context window (%d), which "
                            .formatted(call.effectiveMaxOutputTokens(), model.contextWindow())
                            + "leaves no room for the prompt."));
        }
    }

    /**
     * Applies the findings.
     *
     * @param strict fail on the first error rather than degrading
     * @param report receives every finding, so lenient mode is loud rather than silent
     */
    public static void enforce(List<Finding> findings, boolean strict, java.util.function.Consumer<String> report) {
        for (var finding : findings) {
            report.accept(finding.toString());
        }
        if (!strict) {
            return;
        }
        var errors = findings.stream().filter(Finding::isError).toList();
        if (errors.isEmpty()) {
            return;
        }
        throw new LlmFailure(
                LlmFailure.Kind.UNSUPPORTED,
                errors.size() == 1
                        ? errors.getFirst().message()
                        : "%d incompatible settings: %s".formatted(
                                errors.size(),
                                errors.stream().map(Finding::message).reduce((a, b) -> a + " " + b).orElse("")));
    }

    /** One thing the target cannot do, or can only do differently than asked. */
    public record Finding(Severity severity, String setting, String message) {

        public static Finding error(String setting, String message) {
            return new Finding(Severity.ERROR, setting, message);
        }

        public static Finding warning(String setting, String message) {
            return new Finding(Severity.WARNING, setting, message);
        }

        public boolean isError() {
            return severity == Severity.ERROR;
        }

        @Override
        public String toString() {
            return "%s — %s: %s".formatted(severity, setting, message);
        }
    }

    public enum Severity {
        WARNING,
        ERROR
    }
}
