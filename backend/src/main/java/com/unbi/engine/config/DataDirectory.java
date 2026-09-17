package com.unbi.engine.config;

import com.unbi.engine.settings.SettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
    private final Lock dataReadLock;

    @Autowired
    public DataDirectory(SettingsStore settings) {
        this.root = settings::dataRoot;
        this.dataReadLock = settings.dataReadLock();
    }

    /** A fixed root: the test seam, and the reason the constructor above is annotated. */
    public DataDirectory(Path root) {
        var fixed = root.toAbsolutePath().normalize();
        this.root = () -> fixed;
        this.dataReadLock = new ReentrantReadWriteLock().readLock();
    }

    public Path root() {
        return root.get();
    }

    /**
     * Captures the data root while preventing a concurrent relocation.
     *
     * <p>A lease must be closed by its acquiring thread. It deliberately protects only active
     * data operations, not ordinary path lookups, so an idle engine remains relocatable.
     */
    public Lease acquireLease() {
        dataReadLock.lock();
        try {
            return new Lease(root());
        } catch (RuntimeException | Error failure) {
            dataReadLock.unlock();
            throw failure;
        }
    }

    public final class Lease implements AutoCloseable {
        private final Path root;
        private final Thread owner = Thread.currentThread();
        private boolean closed;

        private Lease(Path root) {
            this.root = root;
        }

        public Path root() {
            return root;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("A data-operation lease must be closed by its acquiring thread.");
            }
            dataReadLock.unlock();
            closed = true;
        }
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
