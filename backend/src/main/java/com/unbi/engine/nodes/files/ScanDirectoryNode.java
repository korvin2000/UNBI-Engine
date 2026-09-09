package com.unbi.engine.nodes.files;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.nodes.files.model.FileRef;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Walks a folder and produces the files it finds.
 *
 * <p>Bounded on purpose. A node that can be pointed at {@code C:\} needs a depth limit and a file
 * cap that are visible in the UI, not buried as constants — a prototype that hangs for ten minutes
 * because someone picked the wrong folder is a prototype nobody runs twice.
 */
@Component
public class ScanDirectoryNode implements NodeDefinition {

    /** Beyond this the UI stops being useful and the browser starts to suffer. */
    private static final int HARD_LIMIT = 100_000;

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("io.scan_directory", "Scan Directory")
                .in("Files", "Input & Output")
                .icon("search-folder")
                .accent("amber")
                .describedAs("Lists every file in a folder, optionally recursing into subfolders.")
                .field("directory", "Directory", FileTypes.DIRECTORY, new Widget.DirectoryPicker(), "")
                .setting("pattern", "Name Pattern", Types.TEXT, Widget.TextField.of("*"), "*")
                .setting("recursive", "Recursive", Types.BOOLEAN, new Widget.Toggle(), true)
                .setting("maxDepth", "Max Depth", Types.NUMBER, Widget.NumberField.of(1, 64), 16d)
                .setting("limit", "Max Files", Types.NUMBER, Widget.NumberField.of(1, HARD_LIMIT), 5000d)
                .out("files", "Files", FileTypes.FILE_LIST)
                .out("count", "Count", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) throws IOException {
        var root = Path.of(context.text("directory").trim());
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Not a folder: " + root);
        }

        var recursive = context.flag("recursive");
        var depth = recursive ? Math.max(1, context.integer("maxDepth")) : 1;
        var limit = Math.min(Math.max(1, context.integer("limit")), HARD_LIMIT);
        var pattern = context.text("pattern").isBlank() ? "*" : context.text("pattern").trim();
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        var found = new ArrayList<FileRef>();
        // Streaming rather than collecting: the walk stops as soon as the cap is reached instead of
        // materialising a directory tree that may be orders of magnitude larger than the limit.
        try (var walk = Files.walk(root, depth, FileVisitOption.FOLLOW_LINKS)) {
            var iterator = walk.iterator();
            while (iterator.hasNext()) {
                context.checkCancelled();
                var candidate = iterator.next();
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                var name = candidate.getFileName();
                if (name == null || !matcher.matches(name)) {
                    continue;
                }
                found.add(FileRef.of(candidate, sizeOf(candidate)));
                if (found.size() % 250 == 0) {
                    context.log("Found " + found.size() + " files...");
                }
                if (found.size() >= limit) {
                    context.log("Stopped at the " + limit + " file limit");
                    break;
                }
            }
        }

        context.progress(1, found.size() + " files");
        context.output("files", List.copyOf(found));
        context.output("count", (double) found.size());
    }

    /**
     * A file can vanish between being listed and being measured, and one racing file should not
     * fail a scan of ten thousand.
     */
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException unreadable) {
            return 0L;
        }
    }
}
