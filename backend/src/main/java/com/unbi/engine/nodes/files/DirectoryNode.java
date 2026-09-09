package com.unbi.engine.nodes.files;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * A directory, named once and reused.
 *
 * <p>The smallest node in the pack, and the one that shows the shape: a descriptor, an execute, and
 * an {@code @Component} annotation that is the entire registration story.
 */
@Component
public class DirectoryNode implements NodeDefinition {

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("io.directory", "Directory")
                .in("Files", "Input & Output")
                .icon("folder")
                .accent("amber")
                .describedAs("Points at a folder on disk so several nodes can share one location.")
                .setting("path", "Path", FileTypes.DIRECTORY, new Widget.DirectoryPicker(), "")
                .out("directory", "Directory", FileTypes.DIRECTORY)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var path = context.text("path").trim();
        if (path.isEmpty()) {
            throw new IllegalStateException("Choose a folder first");
        }
        var directory = Path.of(path);
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException("Not a folder: " + directory);
        }
        context.output("directory", directory.toAbsolutePath().normalize().toString());
    }
}
