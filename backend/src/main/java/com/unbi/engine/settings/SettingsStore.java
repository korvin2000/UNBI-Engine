package com.unbi.engine.settings;

import com.unbi.engine.json.JsonValues;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code settings.json} in the engine's home: read once at startup, rewritten on every change, and
 * the one place that knows where the relocatable directories currently are.
 *
 * <p>The other stores never hold a path of their own. {@code DataDirectory} asks this store for its
 * root on every call, and every store reads its files fresh on every query, so pointing the engine
 * at a different folder takes effect on the next request rather than on the next restart — which is
 * the only behaviour under which a "change location" button in a running editor makes sense.
 */
@Component
public final class SettingsStore {

    public static final String FILE_NAME = "settings.json";
    public static final String FORMAT = "unbi-settings";
    public static final int VERSION = 1;

    /** The workflow library's directory under the home, while it has not been relocated. */
    public static final String WORKFLOWS_DIRECTORY = "workflows";

    /**
     * What "the data directory" contains, and therefore what a relocation copies: the entries the
     * other stores create under it. Listed rather than discovered, because the home directory also
     * holds {@code settings.json} and the workflow library, and neither of those is data.
     */
    public static final List<String> DATA_ENTRIES =
            List.of("credentials.properties", "credentials", "profiles", "presets");

    private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final EngineHome home;
    private final Object lock = new Object();
    private final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();
    private volatile Loaded loaded;

    public SettingsStore(EngineHome home) {
        this.home = home;
        this.loaded = read();
    }

    /** Which of the two relocatable directories a request is about. */
    public enum Target { DATA, WORKFLOWS }

    /** What a relocation did, for the sentence the editor shows afterwards. */
    public record Relocation(Settings settings, Path from, Path to, int filesCopied, int filesSkipped) {}

    /** A data operation is active, so relocating its root would capture an inconsistent snapshot. */
    public static final class DataBusyException extends IOException {
        private static final long serialVersionUID = 1L;
        public DataBusyException() {
            super("Data relocation is busy; finish or cancel authentication and try again.");
        }
    }

    public Settings current() {
        return loaded.settings();
    }

    /** Why the file on disk was not used, or blank. Shown on the settings page, not only logged. */
    public String loadError() {
        return loaded.error();
    }

    public Path home() {
        return home.root();
    }

    public Path file() {
        return home.file(FILE_NAME);
    }

    public Path dataRoot() {
        var configured = current().dataDirectory();
        return configured.isEmpty() ? defaultDataRoot() : Path.of(configured);
    }

    /** Shared with {@link com.unbi.engine.config.DataDirectory} for active data operations. */
    public Lock dataReadLock() {
        return dataLock.readLock();
    }

    public Path workflowsRoot() {
        var configured = current().workflowsDirectory();
        return configured.isEmpty() ? defaultWorkflowsRoot() : Path.of(configured);
    }

    public Path defaultDataRoot() {
        return home.root();
    }

    public Path defaultWorkflowsRoot() {
        return home.root().resolve(WORKFLOWS_DIRECTORY);
    }

    public Path root(Target target) {
        return target == Target.DATA ? dataRoot() : workflowsRoot();
    }

    /** Merges changes into the preferences and writes the file; a {@code null} value removes a key. */
    public Settings updatePreferences(Map<String, Object> changes) throws IOException {
        synchronized (lock) {
            var updated = current().withPreferenceChanges(changes);
            write(updated);
            return updated;
        }
    }

    /**
     * Points the engine at a different directory, first copying what is in the current one if asked.
     *
     * <p>Copied, never moved. The originals stay until the user deletes them by hand: a relocation
     * that deletes is one that can lose a directory tree to a half-failed copy, and the cost of the
     * alternative is one folder to clean up. Nothing already at the destination is overwritten
     * either — a file that exists there is skipped and counted, so pointing at a folder that
     * already holds an older copy merges rather than clobbers.
     *
     * @param directory the new location, or blank for the default
     */
    public Relocation relocate(Target target, String directory, boolean copyExisting) throws IOException {
        Lock dataWriteLock = null;
        if (target == Target.DATA) {
            dataWriteLock = dataLock.writeLock();
            if (!dataWriteLock.tryLock()) {
                throw new DataBusyException();
            }
        }
        try {
            synchronized (lock) {
                var from = root(target);
                var what = target == Target.DATA ? "data directory" : "workflows directory";
                var configured = Settings.normalisePath(directory, what);
                var to = configured.isEmpty()
                        ? (target == Target.DATA ? defaultDataRoot() : defaultWorkflowsRoot())
                        : Path.of(configured);
                if (Files.exists(to) && !Files.isDirectory(to)) {
                    throw new IllegalArgumentException(to + " is a file, not a directory.");
                }

                var copied = new int[] {0, 0};
                if (copyExisting && !from.equals(to)) {
                    copied = copy(sources(target, from), from, to);
                }
                Files.createDirectories(to);

                var updated = target == Target.DATA
                        ? current().withDataDirectory(configured)
                        : current().withWorkflowsDirectory(configured);
                write(updated);
                return new Relocation(updated, from, to, copied[0], copied[1]);
            }
        } finally {
            if (dataWriteLock != null) {
                dataWriteLock.unlock();
            }
        }
    }

    /** The files and trees a relocation copies: the data entries, or the whole workflow library. */
    private static List<Path> sources(Target target, Path from) {
        if (target == Target.WORKFLOWS) {
            return Files.isDirectory(from) ? List.of(from) : List.of();
        }
        return DATA_ENTRIES.stream()
                .map(from::resolve)
                .filter(path -> Files.exists(path) || Files.isSymbolicLink(path))
                .toList();
    }

    /** @return files copied, then files skipped because the destination already had them */
    private static int[] copy(List<Path> sources, Path from, Path to) throws IOException {
        for (var source : sources) {
            if (to.startsWith(source)) {
                throw new IllegalArgumentException("Cannot copy " + source + " into a folder inside itself.");
            }
        }
        int copied = 0;
        int skipped = 0;
        for (var source : sources) {
            var secretSource = source.equals(from.resolve("credentials"))
                    || source.equals(from.resolve("credentials.properties"));
            try (Stream<Path> walk = Files.walk(source)) {
                for (var path : (Iterable<Path>) walk::iterator) {
                    if (secretSource && Files.isSymbolicLink(path)) {
                        throw new IOException("Credential paths must not contain symbolic links");
                    }
                    var destination = to.resolve(from.relativize(path));
                    if (secretSource) {
                        rejectSymlinks(destination);
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        var existed = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
                        Files.createDirectories(destination);
                        if (!existed) {
                            copyPermissions(path, destination);
                        }
                        continue;
                    }
                    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                        skipped++;
                        continue;
                    }
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                    copyPermissions(path, destination);
                    copied++;
                }
            }
        }
        return new int[] {copied, skipped};
    }

    private static void rejectSymlinks(Path path) throws IOException {
        for (var current = path.toAbsolutePath().normalize();
                current != null;
                current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Credential paths must not contain symbolic links");
            }
        }
    }

    private static void copyPermissions(Path source, Path destination) throws IOException {
        var sourceView = Files.getFileAttributeView(source, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        var destinationView =
                Files.getFileAttributeView(destination, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (sourceView != null && destinationView != null) {
            destinationView.setPermissions(sourceView.readAttributes().permissions());
        }
    }

    private Loaded read() {
        var file = file();
        if (!Files.isRegularFile(file)) {
            return new Loaded(Settings.DEFAULTS, "");
        }
        try {
            var root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!FORMAT.equals(root.path("format").asString(""))) {
                throw new IllegalArgumentException("not a UNBI settings file");
            }
            var version = root.path("version").asInt(0);
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported settings version " + version);
            }
            var paths = root.path("paths");
            var preferences = new LinkedHashMap<String, Object>();
            var stored = root.path("preferences");
            if (stored.isObject()) {
                stored.propertyNames().forEach(name -> preferences.put(name, JsonValues.from(stored.get(name))));
            }
            var settings = new Settings(
                    paths.path("data").asString(""), paths.path("workflows").asString(""), preferences);
            return new Loaded(settings, "");
        } catch (IOException | RuntimeException unusable) {
            // A broken settings file must neither take the engine down nor be quietly replaced: the
            // defaults are used, the reason is kept for the settings page, and the file stays as it
            // is until the user saves something.
            var reason = "Could not read " + file + ": " + unusable.getMessage();
            log.warn(reason);
            return new Loaded(Settings.DEFAULTS, reason);
        }
    }

    /** Written through a temporary file: the bootstrap file is the one that must never be half there. */
    private void write(Settings settings) throws IOException {
        var root = mapper.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        var paths = root.putObject("paths");
        paths.put("data", settings.dataDirectory());
        paths.put("workflows", settings.workflowsDirectory());
        var preferences = root.putObject("preferences");
        settings.preferences().forEach((key, value) -> preferences.set(key, JsonValues.of(value)));
        root.put("updatedAt", Instant.now().toString());

        var file = file();
        Files.createDirectories(file.getParent());
        var temporary = Files.createTempFile(file.getParent(), "settings", ".tmp");
        try {
            Files.writeString(temporary, root.toPrettyString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        loaded = new Loaded(settings, "");
    }

    private record Loaded(Settings settings, String error) {}
}
