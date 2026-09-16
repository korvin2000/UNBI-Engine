package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import java.util.LinkedHashMap;
import java.util.SequencedMap;

/**
 * The display rows of a node, ready to be filled in.
 *
 * <p>Seeded blank from the descriptor rather than assembled by hand, because the invariant that
 * makes a fetch trustworthy is that it writes <em>every</em> row. A fetch that only wrote the rows
 * it found values for would leave the others showing what the previous endpoint said — so switching
 * an Endpoint Info node from a paid account to a free one would keep the old balance on screen, and
 * the row would be a lie with a timestamp beside it that says it is fresh.
 *
 * <p>Reading the keys off the descriptor also means adding a row is one line in one place: the row
 * cannot be forgotten in the clearing pass, because there is no clearing pass to forget it in.
 */
final class DisplayRows {

    private DisplayRows() {}

    /** Every {@link Widget.Display} input this node declares, mapped to "not published". */
    static SequencedMap<String, Object> blank(NodeDescriptor descriptor) {
        var rows = new LinkedHashMap<String, Object>();
        for (var input : descriptor.inputs()) {
            if (input.widget() instanceof Widget.Display) {
                rows.put(input.key(), "");
            }
        }
        return rows;
    }
}
