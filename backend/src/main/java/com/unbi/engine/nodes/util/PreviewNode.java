package com.unbi.engine.nodes.util;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import java.util.Collection;
import org.springframework.stereotype.Component;

/**
 * Shows whatever is wired into it, inside the node.
 *
 * <p>The debugging tool for a visual language. Accepts {@code Any}, so it can be dropped onto any
 * edge without thought — which is the only way an inspector node is actually useful.
 */
@Component
public class PreviewNode implements NodeDefinition {

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("util.preview", "Preview")
                .in("Utility", "Inspect")
                .icon("eye")
                .accent("slate")
                .describedAs("Displays the value flowing along an edge.")
                .socket("value", "Value", Types.ANY)
                .setting("rows", "Rows To Show", Types.NUMBER, Widget.NumberField.of(1, 200), 20d)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var value = context.rawInput("value");
        var rows = Math.max(1, context.integer("rows"));

        context.log(ValueRendering.describe(value));
        var rendered = ValueRendering.toPlainText(value);
        var lines = rendered.isEmpty() ? new String[0] : rendered.split("\n", -1);
        for (int index = 0; index < Math.min(rows, lines.length); index++) {
            context.log(lines[index]);
        }
        if (lines.length > rows) {
            context.log("... and %d more".formatted(lines.length - rows));
        }
        context.progress(1, value instanceof Collection<?> items ? items.size() + " items" : null);
    }
}
