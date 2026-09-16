package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.nodes.llm.model.LlmVariables;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

/**
 * Gives upstream values names a prompt template can read.
 *
 * <p>This is the answer to "how does a template get data from another node". Ports are fixed by the
 * descriptor, so a socket cannot be named by the user at edit time; naming them <em>beside</em> the
 * sockets is the shape that works — {@code Name 1} labels whatever is wired into {@code Value 1},
 * and the template says {@code &#123;&#123;that name&#125;&#125;}.
 *
 * <p>Four pairs, and a {@code More} input to merge another Variables node in front. Four covers the
 * prompts anyone writes by hand, and chaining covers the rest without this node growing a scrollbar.
 * Later bindings win, so a chained node overrides rather than duplicates.
 */
@Component
public class LlmVariablesNode implements NodeDefinition {

    /** Enough for a hand-written prompt; beyond this, chain a second node. */
    private static final int SLOTS = 4;

    @Override
    public NodeDescriptor descriptor() {
        var builder = NodeDescriptor.of("llm.variables", "Variables")
                .in(LlmTypes.CATEGORY, "Prompt")
                .icon("tag")
                .accent(LlmTypes.ACCENT)
                .describedAs("Names upstream values so a prompt template can read them as "
                        + "{{name}}.")
                .optionalSocket("more", "More", LlmTypes.VARIABLES)
                .hint("Merged underneath: anything named here wins.");

        for (int slot = 1; slot <= SLOTS; slot++) {
            builder.setting("name" + slot, "Name " + slot, Types.TEXT,
                    Widget.TextField.of(slot == 1 ? "document" : ""), "");
            builder.optionalSocket("value" + slot, "Value " + slot, Types.ANY);
        }
        return builder.out("variables", "Variables", LlmTypes.VARIABLES).build();
    }

    @Override
    public void execute(NodeContext context) {
        var values = new LinkedHashMap<String, Object>();
        var unnamed = 0;
        for (int slot = 1; slot <= SLOTS; slot++) {
            var name = context.text("name" + slot).trim();
            var value = context.rawInput("value" + slot);
            if (value == null) {
                continue;
            }
            if (name.isEmpty()) {
                // Wiring a value into an unnamed slot is a half-finished edit, and a template that
                // then renders an empty hole gives no clue why. Say it here instead.
                unnamed++;
                continue;
            }
            values.put(name, value);
        }
        if (unnamed > 0) {
            throw new IllegalStateException(
                    "%d value%s wired in without a name, so nothing can read %s."
                            .formatted(unnamed, unnamed == 1 ? " is" : "s are", unnamed == 1 ? "it" : "them"));
        }

        var base = context.optional("more", LlmVariables.class).orElse(LlmVariables.EMPTY);
        var merged = new LlmVariables(values).over(base);
        context.output("variables", merged);
        context.progress(1, merged.toString());
    }
}
