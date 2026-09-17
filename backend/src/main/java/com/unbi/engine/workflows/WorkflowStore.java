package com.unbi.engine.workflows;

import com.unbi.engine.settings.SettingsStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The workflow library: one {@code .unbi.json} file per workflow, in the workflows directory, with
 * the favourites in a {@code favorites/} subdirectory of it.
 *
 * <p>A favourite is a <em>place</em> rather than a flag inside the file, and that is the point: the
 * library is a folder the user can open, and "the ones I keep coming back to" being a subfolder
 * is something anybody can see, sort and back up without the editor. Marking a favourite moves the
 * file; nothing inside it changes.
 *
 * <p>The same shape as the preset and profile stores, for the same reasons — hand-editable,
 * one file per thing, read fresh on every query — with one difference: the document is stored
 * verbatim. The engine checks that a file <em>is</em> a workflow before writing it, and otherwise
 * knows nothing about what is in it. What a workflow means is the editor's business.
 */
@Component
public class WorkflowStore {

    public static final String FAVORITES_DIRECTORY = "favorites";

    /** A saved workflow larger than this is not a workflow; it is a mistake. */
    static final int MAX_DOCUMENT_BYTES = 16 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(WorkflowStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final SettingsStore settings;
    private final Object writeLock = new Object();

    public WorkflowStore(SettingsStore settings) {
        this.settings = settings;
    }

    /** Thrown when a name is already taken and the caller did not ask to overwrite. */
    public static final class AlreadyExists extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AlreadyExists(String name) {
            super("A workflow called \"" + name + "\" already exists.");
        }
    }

    public Path root() {
        return settings.workflowsRoot();
    }

    public Path favoritesRoot() {
        return root().resolve(FAVORITES_DIRECTORY);
    }

    /** Everything in the library, favourites and all, alphabetically. */
    public List<StoredWorkflow> list() {
        // Favourites first so that, should the same name exist in both places, the favourite is
        // the one the listing keeps — and the shadowed file is at least mentioned in the log.
        var found = new LinkedHashMap<String, StoredWorkflow>();
        for (var entry : scan(favoritesRoot(), true)) {
            found.put(key(entry.name()), entry);
        }
        for (var entry : scan(root(), false)) {
            if (found.putIfAbsent(key(entry.name()), entry) != null) {
                log.warn("Workflow {} exists both as a favourite and not; the favourite is used.", entry.name());
            }
        }
        return found.values().stream()
                .sorted(Comparator.comparing(StoredWorkflow::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public int count() {
        return list().size();
    }

    public Optional<StoredWorkflow> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        var wanted = key(id.trim());
        return list().stream().filter(entry -> key(entry.name()).equals(wanted)).findFirst();
    }

    /** The document exactly as it was saved. */
    public Optional<JsonNode> read(String id) throws IOException {
        var entry = find(id);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(mapper.readTree(Files.readString(fileFor(entry.get()), StandardCharsets.UTF_8)));
    }

    /**
     * Writes a workflow under a name.
     *
     * <p>A name already in use is refused unless {@code overwrite} is set — the editor asks first.
     * Saving over an existing favourite keeps it a favourite: the file is rewritten where it is.
     */
    public StoredWorkflow save(String rawName, JsonNode document, boolean overwrite) throws IOException {
        var name = StoredWorkflow.validName(rawName);
        var text = validDocument(document);
        synchronized (writeLock) {
            var existing = find(name);
            if (existing.isPresent() && !overwrite) {
                throw new AlreadyExists(name);
            }
            var favorite = existing.map(StoredWorkflow::favorite).orElse(false);
            // Saving over "flow" with "Flow" renames the file on a case-insensitive filesystem only
            // if the old one goes first; on a case-sensitive one it would otherwise leave two.
            if (existing.isPresent() && !existing.get().name().equals(name)) {
                Files.deleteIfExists(fileFor(existing.get()));
            }
            var target = fileFor(name, favorite);
            Files.createDirectories(target.getParent());
            Files.writeString(target, text, StandardCharsets.UTF_8);
            return describe(target, favorite);
        }
    }

    /** @return false when there was nothing to delete */
    public boolean delete(String id) throws IOException {
        synchronized (writeLock) {
            var existing = find(id);
            return existing.isPresent() && Files.deleteIfExists(fileFor(existing.get()));
        }
    }

    /** Moves the file into, or out of, the favourites folder. */
    public Optional<StoredWorkflow> setFavorite(String id, boolean favorite) throws IOException {
        synchronized (writeLock) {
            var existing = find(id);
            if (existing.isEmpty()) {
                return Optional.empty();
            }
            var current = existing.get();
            if (current.favorite() == favorite) {
                return existing;
            }
            var target = fileFor(current.name(), favorite);
            Files.createDirectories(target.getParent());
            Files.move(fileFor(current), target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(describe(target, favorite));
        }
    }

    /** Renames the file in place; a favourite stays a favourite. */
    public Optional<StoredWorkflow> rename(String id, String rawName) throws IOException {
        var name = StoredWorkflow.validName(rawName);
        synchronized (writeLock) {
            var existing = find(id);
            if (existing.isEmpty()) {
                return Optional.empty();
            }
            var current = existing.get();
            if (current.name().equals(name)) {
                return existing;
            }
            var taken = find(name);
            if (taken.isPresent() && !key(taken.get().name()).equals(key(current.name()))) {
                throw new AlreadyExists(name);
            }
            var target = fileFor(name, current.favorite());
            Files.move(fileFor(current), target, StandardCopyOption.REPLACE_EXISTING);
            return Optional.of(describe(target, current.favorite()));
        }
    }

    /**
     * Enough of a check that a file in the library is a workflow the editor can open: the format
     * marker, the version, and the two arrays. Everything else is the editor's to validate.
     */
    static String validDocument(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("Expected a workflow document.");
        }
        if (!"unbi-workflow".equals(document.path("format").asString(""))) {
            throw new IllegalArgumentException("Not a UNBI workflow.");
        }
        if (document.path("version").asInt(0) != 1) {
            throw new IllegalArgumentException(
                    "Unsupported workflow version " + document.path("version").asString("?") + ".");
        }
        if (!document.path("nodes").isArray() || !document.path("edges").isArray()) {
            throw new IllegalArgumentException("The workflow is missing its nodes or edges.");
        }
        var text = document.toPrettyString();
        if (text.length() > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("The workflow is too large to keep in the library.");
        }
        return text;
    }

    private List<StoredWorkflow> scan(Path directory, boolean favorite) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        var found = new ArrayList<StoredWorkflow>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(StoredWorkflow.EXTENSION))
                    .sorted()
                    .forEach(path -> {
                        try {
                            found.add(describe(path, favorite));
                        } catch (IOException | RuntimeException vanished) {
                            // Listed a moment ago, gone or unreadable now; one such entry must not
                            // hide the rest of the library.
                            log.warn("Skipping workflow {}: {}", path.getFileName(), vanished.getMessage());
                        }
                    });
        } catch (IOException | UncheckedIOException unreadable) {
            log.warn("Could not list {}: {}", directory, unreadable.getMessage());
        }
        return found;
    }

    private static StoredWorkflow describe(Path file, boolean favorite) throws IOException {
        var fileName = file.getFileName().toString();
        var name = fileName.substring(0, fileName.length() - StoredWorkflow.EXTENSION.length());
        return new StoredWorkflow(name, name, favorite, Files.getLastModifiedTime(file).toInstant(), Files.size(file));
    }

    private Path fileFor(StoredWorkflow workflow) {
        return fileFor(workflow.name(), workflow.favorite());
    }

    private Path fileFor(String name, boolean favorite) {
        var directory = favorite ? favoritesRoot() : root();
        var file = directory.resolve(name + StoredWorkflow.EXTENSION).normalize();
        if (!file.getParent().equals(directory)) {
            throw new IllegalArgumentException("Not a usable workflow name: " + name);
        }
        return file;
    }

    /** Names differing only in case are the same file on Windows and macOS; treat them so everywhere. */
    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
