package com.unbi.engine.nodes.files.model;

import java.nio.file.Path;

/**
 * A file discovered by a scan.
 *
 * <p>Carries the resolved {@link Path} for nodes downstream while exposing plain components for the
 * wire, so a preview can render a file without the transport knowing about {@code Path}.
 */
public record FileRef(String path, String name, String extension, long size) {

    public static FileRef of(Path file, long size) {
        var name = file.getFileName().toString();
        var dot = name.lastIndexOf('.');
        var extension = dot > 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        return new FileRef(file.toString(), name, extension, size);
    }

    public Path toPath() {
        return Path.of(path);
    }
}
