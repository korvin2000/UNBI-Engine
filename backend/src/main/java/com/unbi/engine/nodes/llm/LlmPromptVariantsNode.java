package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Several prompts written in one box, to be tried one after another.
 *
 * <p>The cheapest way to answer "which of these three system prompts works best": type them
 * separated by a line of three dashes, wire the output into System Prompt on an LLM Request, and
 * the request runs once per variant with the answers in the same order. The same box on User
 * Prompt tries several questions; both at once, with Combine Lists set to every combination, tries
 * every pairing.
 *
 * <p>A separator line rather than one field per prompt, because prompts are paragraphs and a node
 * with four prompt boxes is a node nobody can read.
 */
@Component
public class LlmPromptVariantsNode implements NodeDefinition {

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.prompt_variants", "Prompt Variants")
                .in(LlmTypes.CATEGORY, "Prompt")
                .icon("layers")
                .accent(LlmTypes.ACCENT)
                .describedAs("Several prompts in one box, separated by a line of dashes. Wired into "
                        + "a prompt on LLM Request, each becomes its own request.")
                .setting("text", "Prompts", Types.TEXT,
                        Widget.TextField.prose("You are terse.\n---\nYou are thorough.", 6,
                                LlmRequestNode.PROMPT_LIBRARY, LlmRequestNode.PROMPT_LIBRARY_KEY), "")
                .hint("Separate prompts with a line holding only the separator.")
                .advancedSetting("separator", "Separator", Types.TEXT, Widget.TextField.of("---"), "---")
                .out("prompts", "Prompts", LlmTypes.TEXT_LIST)
                .out("count", "Count", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var separator = context.text("separator").strip();
        if (separator.isEmpty()) {
            separator = "---";
        }
        var variants = split(context.text("text"), separator);
        if (variants.isEmpty()) {
            throw new IllegalStateException("This node needs at least one prompt.");
        }
        context.log("%d prompt%s".formatted(variants.size(), variants.size() == 1 ? "" : "s"));
        context.output("prompts", variants);
        context.output("count", (double) variants.size());
        context.progress(1, variants.size() + " prompts");
    }

    /** Splits on lines that are exactly the separator, trimming each part and dropping empty ones. */
    static List<String> split(String text, String separator) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        for (var line : (text == null ? "" : text).lines().toList()) {
            if (line.strip().equals(separator)) {
                add(parts, current);
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        add(parts, current);
        return List.copyOf(parts);
    }

    private static void add(List<String> parts, StringBuilder current) {
        var part = current.toString().strip();
        if (!part.isEmpty()) {
            parts.add(part);
        }
    }
}
