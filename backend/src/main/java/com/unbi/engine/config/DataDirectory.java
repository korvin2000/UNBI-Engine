package com.unbi.engine.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the engine keeps the things that outlive a workflow: presets, prompt templates, credentials.
 *
 * <p>One place rather than three properties, so "where did my presets go?" has one answer. Defaults
 * to {@code ~/.unbi-engine} and is overridable with {@code unbi.data-dir} — a deployment that wants
 * everything under one mounted volume changes one line.
 *
 * <p>Directories are created on demand rather than at startup: an engine that never saves a preset
 * should not litter a home directory.
 */
@Component
public class DataDirectory {

    private final Path root;

    @Autowired
    public DataDirectory(@Value("${unbi.data-dir:}") String configured) {
        this(configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".unbi-engine")
                : Path.of(configured));
    }

    public DataDirectory(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** A subdirectory, created if it is not there yet. */
    public Path directory(String name) throws IOException {
        var path = root.resolve(name);
        Files.createDirectories(path);
        return path;
    }

    /** A file inside the data directory. Not created, and its parent is not either. */
    public Path file(String name) {
        return root.resolve(name);
    }
}
