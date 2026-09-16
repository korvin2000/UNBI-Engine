package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.spec.SamplingParams;
import org.springframework.stereotype.Component;

/**
 * How the model should sample — every field blank by default, and blank means "do not send".
 *
 * <p>Its own node rather than a section of the Model node because the two answer different
 * questions: a model's capabilities and price are facts about the model, while sampling is a choice
 * about this piece of work. Keeping them apart is what lets one Model node feed a cautious extraction
 * and an inventive rewrite at once, and it is why the simple workflow needs no such node at all.
 *
 * <p>Blank rather than zero is load-bearing. "Temperature 0" and "no temperature" are different
 * requests, and at least one gateway validates the first and then ignores it, so writing it down
 * would record an intention the endpoint does not honour.
 */
@Component
public class LlmSamplingNode implements NodeDefinition {

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.sampling", "Generation Params")
                .in(LlmTypes.CATEGORY, "Connection")
                .icon("sliders")
                .accent(LlmTypes.ACCENT)
                .describedAs("Sampling settings. Anything left blank is not sent at all, which is "
                        + "not the same as sending zero.")
                .setting("temperature", "Temperature", Types.NUMBER,
                        Widget.NumberField.optional(0, 2, 0.05), null)
                .setting("topP", "Top P", Types.NUMBER, Widget.NumberField.optional(0, 1, 0.01), null)
                .advancedSetting("topK", "Top K", Types.NUMBER, Widget.NumberField.optional(0, 500, 1), null)
                .hint("Not an OpenAI field. Gateways commonly accept it and quietly ignore it — "
                        + "see Require Parameter Support on the model.")
                .advancedSetting("minP", "Min P", Types.NUMBER, Widget.NumberField.optional(0, 1, 0.005), null)
                .advancedSetting("frequencyPenalty", "Frequency Penalty", Types.NUMBER,
                        Widget.NumberField.optional(-2, 2, 0.1), null)
                .advancedSetting("presencePenalty", "Presence Penalty", Types.NUMBER,
                        Widget.NumberField.optional(-2, 2, 0.1), null)
                .advancedSetting("seed", "Seed", Types.NUMBER,
                        Widget.NumberField.optional(0, 2_147_483_647d, 1), null)
                .hint("Only some hosts honour it; reproducibility comes mostly from temperature 0.")
                .advancedSetting("stop", "Stop Sequences", Types.TEXT, Widget.TextField.of("###, END"), "")
                .setting("maxOutputTokens", "Max Output Tokens", Types.NUMBER,
                        Widget.NumberField.optional(0, 1_000_000, 256, "tok", "model's limit"), null)
                .hint("Blank leaves it to the model node, which may itself have no limit. A number "
                        + "here is still capped by the model's own ceiling when it has one.")
                .out("sampling", "Generation Params", LlmTypes.SAMPLING)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var params = new SamplingParams(
                NodeValues.optionalDouble(context, "temperature"),
                NodeValues.optionalDouble(context, "topP"),
                NodeValues.optionalInt(context, "topK"),
                NodeValues.optionalDouble(context, "minP"),
                NodeValues.optionalDouble(context, "frequencyPenalty"),
                NodeValues.optionalDouble(context, "presencePenalty"),
                NodeValues.optionalInt(context, "seed"),
                NodeValues.csv(context.text("stop")),
                NodeValues.optionalInt(context, "maxOutputTokens"));

        context.output("sampling", params);
        context.progress(1, params.isUnset() ? "nothing set" : describe(params));
    }

    private static String describe(SamplingParams params) {
        var parts = new java.util.ArrayList<String>();
        if (params.temperature() != null) {
            parts.add("temp " + params.temperature());
        }
        if (params.topP() != null) {
            parts.add("top_p " + params.topP());
        }
        if (params.topK() != null) {
            parts.add("top_k " + params.topK());
        }
        if (params.seed() != null) {
            parts.add("seed " + params.seed());
        }
        return parts.isEmpty() ? "set" : String.join(", ", parts);
    }
}
