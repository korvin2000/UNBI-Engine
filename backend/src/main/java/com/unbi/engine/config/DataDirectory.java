package com.unbi.engine.config;

import com.unbi.engine.settings.SettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Where the engine keeps the things that outlive a workflow: presets, profiles, credentials.
 *
 * <p>One place rather than three properties, so "where did my presets go?" has one answer — and
 * since the data directory can be relocated from the settings page while the engine runs, that
 * answer is asked of {@link SettingsStore} on every call rather than remembered. The stores read
 * their files fresh on every query, so nothing caches a path that may have just changed.
 *
 * <p>Directories are created on demand rather than at startup: an engine that never saves a preset
 * should not litter a home directory.
 */
@Component
public class DataDirectory {

    private final Supplier<Path> root;

    @Autowired
    public DataDirectory(SettingsStore settings) {
        this.root = settings::dataRoot;
    }

    /** A fixed root: the test seam, and the reason the constructor above is annotated. */
    public DataDirectory(Path root) {
        var fixed = root.toAbsolutePath().normalize();
        this.root = () -> fixed;
    }

    public Path root() {
        return root.get();
    }

    /** A subdirectory, created if it is not there yet. */
    public Path directory(String name) throws IOException {
        var path = root().resolve(name);
        Files.createDirectories(path);
        return path;
    }

    /** A file inside the data directory. Not created, and its parent is not either. */
    public Path file(String name) {
        return root().resolve(name);
    }
}
