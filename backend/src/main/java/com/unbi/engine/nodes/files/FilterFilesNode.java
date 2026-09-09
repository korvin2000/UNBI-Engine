package com.unbi.engine.nodes.files;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.nodes.files.model.FileRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Keeps or drops files by extension.
 *
 * <p>Both directions from one node, because "only these types" and "everything except these types"
 * are the same decision seen from two sides, and two nodes would mean two places to fix a bug.
 */
@Component
public class FilterFilesNode implements NodeDefinition {

    private static final String KEEP = "keep";

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("io.filter_files", "Filter Files")
                .in("Files", "Processing")
                .icon("filter")
                .accent("sky")
                .describedAs("Narrows a file list to the extensions you care about, or removes them.")
                .socket("files", "Files", FileTypes.FILE_LIST)
                .setting("extensions", "Extensions", Types.TEXT,
                        Widget.TextField.of("txt, md, java"), "txt, md")
                .setting("mode", "Mode", Types.TEXT,
                        Widget.Dropdown.of(KEEP, "Keep matching", "drop", "Remove matching"), KEEP)
                .setting("minSize", "Min Size (bytes)", Types.NUMBER, Widget.NumberField.of(0, Integer.MAX_VALUE), 0d)
                .out("files", "Files", FileTypes.FILE_LIST)
                .out("count", "Count", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var files = context.listOf("files", FileRef.class);
        var wanted = parseExtensions(context.text("extensions"));
        var keepMatching = KEEP.equals(context.text("mode"));
        var minSize = (long) context.number("minSize");

        var kept = new ArrayList<FileRef>();
        for (int index = 0; index < files.size(); index++) {
            context.checkCancelled();
            var file = files.get(index);
            // An empty extension list is a no-op filter rather than a filter that removes
            // everything: an unconfigured node should not silently delete the pipeline.
            var matchesExtension = wanted.isEmpty() || wanted.contains(file.extension());
            if (matchesExtension == keepMatching && file.size() >= minSize) {
                kept.add(file);
            }
            if (index % 200 == 0) {
                context.progress((double) index / Math.max(1, files.size()), null);
            }
        }

        context.progress(1, kept.size() + " of " + files.size() + " kept");
        context.output("files", List.copyOf(kept));
        context.output("count", (double) kept.size());
    }

    /** Accepts {@code txt, .md; java} — people type separators inconsistently and it does not matter. */
    private static Set<String> parseExtensions(String raw) {
        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(token -> token.trim().toLowerCase(Locale.ROOT))
                .map(token -> token.startsWith(".") ? token.substring(1) : token)
                .filter(token -> !token.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
