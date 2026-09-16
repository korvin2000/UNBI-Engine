package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.prompt.PromptTemplate;
import com.unbi.engine.nodes.llm.model.LlmVariables;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/**
 * A prompt, written once and filled in per run.
 *
 * <p>One node for both halves of a request: drop two in, wire one into System and one into User.
 * Splitting them into a SystemPromptNode and a UserPromptNode would be two files doing the identical
 * thing, and would stop a system prompt from ever being reused as a user prompt.
 *
 * <p>Saving a template is the preset mechanism, not a second library: a Prompt Template node saved
 * as a preset <em>is</em> a stored prompt, retrievable by name and group across workflows. One
 * mechanism, and nothing to keep in sync — which is also why the editor behind the Template field
 * and the one behind an LLM Request's prompts read from and write to the same place. Save a prompt
 * from either and it appears in both, and in the Presets tab, where it can be renamed or deleted.
 *
 * <p>Strict is on by default. A mistyped variable rendering as an empty hole in the middle of an
 * instruction is the failure this node exists to avoid: the model answers something plausible and
 * nothing anywhere reports that a value went missing.
 */
@Component
public class LlmPromptNode implements NodeDefinition {

    /** The name the directly-wired value is bound to, so a one-input prompt needs no Variables node. */
    private static final String INPUT_BINDING = "input";

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.prompt", "Prompt Template")
                .in(LlmTypes.CATEGORY, "Prompt")
                .icon("document")
                .accent(LlmTypes.ACCENT)
                .describedAs("Renders a template, filling {{name}} from wired values. Save it as a "
                        + "preset to reuse the same prompt across workflows.")
                .setting("template", "Template", Types.TEXT,
                        Widget.TextField.code("Summarise {{input}} in three sentences.", 5,
                                LlmRequestNode.PROMPT_LIBRARY, LlmRequestNode.PROMPT_LIBRARY_KEY), "")
                .hint("Press the expand button for a full-window editor, where a template can be "
                        + "loaded from a file, picked from the saved library, or saved into it.")
                .optionalSocket("variables", "Variables", LlmTypes.VARIABLES)
                .optionalSocket("input", "Input", Types.ANY)
                .hint("Bound as {{input}}, so a single-value prompt needs no Variables node.")
                .advancedSetting("strict", "Fail On Missing Values", Types.BOOLEAN, new Widget.Toggle(), true)
                .out("text", "Text", Types.TEXT)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var template = context.text("template");
        if (template.isBlank()) {
            throw new IllegalStateException("This node needs a template.");
        }

        var bindings = new LinkedHashMap<String, Object>(
                context.optional("variables", LlmVariables.class).orElse(LlmVariables.EMPTY).asMap());
        var wired = context.rawInput("input");
        if (wired != null) {
            // Named bindings win: someone who wired a value in *and* bound the same name meant the
            // name they wrote down.
            bindings.putIfAbsent(INPUT_BINDING, wired);
        }

        var needed = PromptTemplate.variables(template);
        var rendered = render(context, template, bindings);

        context.log(needed.isEmpty()
                ? "No variables in this template."
                : "Filled " + String.join(", ", needed));
        context.output("text", rendered);
        context.progress(1, rendered.length() + " characters");
    }

    private static String render(
            NodeContext context, String template, java.util.Map<String, Object> bindings) {
        try {
            return PromptTemplate.render(template, bindings, context.flag("strict"));
        } catch (PromptTemplate.MissingVariableException missing) {
            // Re-thrown as the engine's own failure type so the node goes red with this sentence
            // rather than with a class name from inside the template engine.
            throw new IllegalStateException(missing.getMessage(), missing);
        }
    }
}
