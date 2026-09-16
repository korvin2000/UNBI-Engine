package com.unbi.engine.nodes.files;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.nodes.files.model.FileRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A handful of named files, chosen by hand.
 *
 * <p>Scan Directory answers "everything under here"; this answers "these four". That is a different
 * question and the graph had no way to ask it: reaching the four meant scanning a folder and then
 * writing a filter precise enough to exclude everything else in it, which is a pattern nobody gets
 * right and nobody can read a week later.
 *
 * <p>The value is a <em>list</em> of paths, not a separated string, and that is the whole reason
 * this is a node rather than a second path field. Every separator worth using — comma, semicolon,
 * space — is legal inside a Windows filename, so any string encoding is a parser with a failure mode
 * the user discovers as a missing file. A list also makes the only two operations anyone wants —
 * add one, remove the wrong one — exact rather than a text edit.
 *
 * <p>Missing files are reported by name. Silently dropping one turns "the model ignored my
 * contract" into an investigation of the model.
 */
@Component
public class SelectFilesNode implements NodeDefinition {

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("io.select_files", "Select Files")
                .in("Files", "Input & Output")
                .icon("documents")
                .accent("amber")
                .describedAs("Picks specific files on the engine's disk and passes them on as a list.")
                .setting("paths", "Files", com.unbi.engine.core.type.PortType.list(Types.TEXT), Widget.FileList.any(), null)
                .hint("Add them one at a time from the engine's disk. Each row can be removed on "
                        + "its own, so a wrong pick costs one click.")
                .advancedSetting("skipMissing", "Skip Missing Files", Types.BOOLEAN,
                        new Widget.Toggle(), false)
                .hint("Off fails the node and names the file, which is what you want the first time "
                        + "something was moved.")
                .out("files", "Files", FileTypes.FILE_LIST)
                .out("count", "Count", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) throws IOException {
        var chosen = paths(context);
        if (chosen.isEmpty()) {
            throw new IllegalStateException("Add at least one file.");
        }

        var skipMissing = context.flag("skipMissing");
        var files = new ArrayList<FileRef>(chosen.size());
        var skipped = new ArrayList<String>();

        for (int index = 0; index < chosen.size(); index++) {
            context.checkCancelled();
            var path = Path.of(chosen.get(index)).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                if (!skipMissing) {
                    throw new IllegalStateException(
                            "Not a file that can be read: " + path
                                    + " — remove it from the list, or switch Skip Missing Files on.");
                }
                skipped.add(path.getFileName().toString());
                continue;
            }
            files.add(FileRef.of(path, sizeOf(path)));
            context.progress((index + 1d) / chosen.size(), path.getFileName().toString());
        }

        context.log(skipped.isEmpty()
                ? "%d file%s".formatted(files.size(), files.size() == 1 ? "" : "s")
                : "%d file%s, %d skipped: %s".formatted(
                        files.size(), files.size() == 1 ? "" : "s", skipped.size(), String.join(", ", skipped)));
        context.output("files", List.copyOf(files));
        context.output("count", (double) files.size());
    }

    /** An unreadable size is a size of zero, not a failed node: the path is what matters here. */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException unreadable) {
            return 0;
        }
    }

    /**
     * The chosen paths, de-duplicated and in the order they were added.
     *
     * <p>Duplicates are dropped rather than refused: adding the same file twice is a slip with one
     * obvious intention, and a node that failed over it would be punishing a double-click. Order is
     * kept because the rest of the pack pairs lists up position by position.
     */
    private static List<String> paths(NodeContext context) {
        var raw = context.rawInput("paths");
        var found = new LinkedHashSet<String>();
        if (raw instanceof List<?> items) {
            for (var item : items) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    found.add(String.valueOf(item).trim());
                }
            }
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            found.add(String.valueOf(raw).trim());
        }
        return List.copyOf(found);
    }
}
