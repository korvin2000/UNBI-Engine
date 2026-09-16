package com.unbi.engine.presets;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.json.JsonValues;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
 * Presets on disk, one JSON file each, in the engine's data directory.
 *
 * <p>Separate from workflow files on purpose, and that separation is the requirement: a prompt or an
 * endpoint worth keeping outlives the graph it was first written in, and a preset stored inside a
 * workflow can only be reused by copying the workflow.
 *
 * <p>One file per preset rather than one index file, because these are meant to be edited by hand,
 * diffed, and dropped into a repository. A shared index would turn every edit into a merge conflict
 * and every hand-written preset into a chance to corrupt everyone else's.
 *
 * <p>Read straight from disk on every query. There are tens of these, not thousands, and a cache
 * would mean a preset added by dropping in a file did not appear until a restart — which is exactly
 * the workflow one-file-per-preset exists to support.
 */
@Component
public class PresetStore {

    /** A directory of presets that has grown past this is a sign of a different problem. */
    private static final int MAX_PRESETS = 2000;

    private static final Logger log = LoggerFactory.getLogger(PresetStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final DataDirectory data;
    private final Object writeLock = new Object();

    public PresetStore(DataDirectory data) {
        this.data = data;
    }

    /**
     * Presets matching every filter given, newest first.
     *
     * @param nodeType exact node type, or blank for any
     * @param group    exact group, or blank for any
     * @param query    free text over name, group, description and type
     */
    public List<Preset> query(String nodeType, String group, String query) {
        return all().stream()
                .filter(preset -> isBlank(nodeType) || preset.nodeType().equals(nodeType.trim()))
                .filter(preset -> isBlank(group) || preset.group().equalsIgnoreCase(group.trim()))
                .filter(preset -> preset.matches(query))
                .sorted(Comparator.comparing(Preset::updatedAt).reversed())
                .toList();
    }

    public Optional<Preset> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return all().stream().filter(preset -> preset.id().equals(id.trim())).findFirst();
    }

    /** Every group in use, so the editor can offer them without inventing a fixed list. */
    public List<String> groups() {
        return all().stream()
                .map(Preset::group)
                .filter(group -> !group.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * Writes a preset, giving it an id if it has none.
     *
     * <p>A new preset whose derived id is taken gets a numbered one rather than overwriting: two
     * prompts called "Summarise" are a normal thing to have, and silently replacing the first is
     * not a normal thing to do about it.
     */
    public Preset save(Preset preset) throws IOException {
        synchronized (writeLock) {
            var stamped = new Preset(
                    preset.id(), preset.name(), preset.group(), preset.nodeType(),
                    preset.description(), preset.values(), Instant.now());
            var directory = data.directory("presets");
            var target = uniqueTarget(directory, stamped);
            if (count(directory) >= MAX_PRESETS && !Files.exists(target.path())) {
                throw new IllegalStateException(
                        "There are already %d presets in %s.".formatted(MAX_PRESETS, directory));
            }
            Files.writeString(target.path(), toJson(target.preset()), StandardCharsets.UTF_8);
            return target.preset();
        }
    }

    /** @return false when there was nothing to delete */
    public boolean delete(String id) throws IOException {
        synchronized (writeLock) {
            var existing = find(id);
            if (existing.isEmpty()) {
                return false;
            }
            return Files.deleteIfExists(fileFor(data.directory("presets"), existing.get().id()));
        }
    }

    private List<Preset> all() {
        Path directory;
        try {
            directory = data.file("presets");
            if (!Files.isDirectory(directory)) {
                return List.of();
            }
        } catch (RuntimeException unavailable) {
            log.warn("Could not reach the presets directory: {}", unavailable.getMessage());
            return List.of();
        }

        var found = new ArrayList<Preset>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> read(path).ifPresent(found::add));
        } catch (IOException | UncheckedIOException unreadable) {
            log.warn("Could not list {}: {}", directory, unreadable.getMessage());
        }
        return List.copyOf(found);
    }

    /**
     * @return empty for a file that is not a usable preset
     *
     * <p>One corrupt file — hand-edited, half-written, left behind by another tool — must not hide
     * every other preset. It is logged and skipped.
     */
    private Optional<Preset> read(Path path) {
        try {
            var root = mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            var values = new LinkedHashMap<String, Object>();
            var valuesNode = root.path("values");
            if (valuesNode.isObject()) {
                valuesNode.propertyNames().forEach(name -> values.put(name, JsonValues.from(valuesNode.get(name))));
            }
            return Optional.of(new Preset(
                    stripExtension(path.getFileName().toString()),
                    root.path("name").asString(""),
                    root.path("group").asString(""),
                    root.path("nodeType").asString(""),
                    root.path("description").asString(""),
                    values,
                    parseInstant(root.path("updatedAt").asString(""), path)));
        } catch (IOException | RuntimeException unusable) {
            log.warn("Skipping preset {}: {}", path.getFileName(), unusable.getMessage());
            return Optional.empty();
        }
    }

    private String toJson(Preset preset) {
        var root = mapper.createObjectNode();
        root.put("name", preset.name());
        root.put("group", preset.group());
        root.put("nodeType", preset.nodeType());
        root.put("description", preset.description());
        root.put("updatedAt", preset.updatedAt().toString());
        var values = root.putObject("values");
        preset.values().forEach((key, value) -> values.set(key, JsonValues.of(value)));
        return root.toPrettyString();
    }

    private Target uniqueTarget(Path directory, Preset preset) {
        var file = fileFor(directory, preset.id());
        if (!Files.exists(file) || isUpdateOfExisting(preset)) {
            return new Target(preset, file);
        }
        for (int suffix = 2; suffix < 100; suffix++) {
            var candidate = preset.withId(preset.id() + "-" + suffix);
            var path = fileFor(directory, candidate.id());
            if (!Files.exists(path)) {
                return new Target(candidate, path);
            }
        }
        throw new IllegalStateException("Too many presets named like " + preset.name());
    }

    /**
     * True when this is a rewrite of the preset already holding that id, rather than a collision.
     *
     * <p>Node type decides it. Two presets named "Summarise" for two different node types are a
     * normal thing to have and must not overwrite each other; the same name for the same node type
     * is the user saving over their own work, which is what they meant.
     */
    private boolean isUpdateOfExisting(Preset preset) {
        return find(preset.id())
                .map(existing -> existing.nodeType().equals(preset.nodeType()))
                .orElse(false);
    }

    private static Path fileFor(Path directory, String id) {
        var file = directory.resolve(id + ".json").normalize();
        if (!file.startsWith(directory)) {
            throw new IllegalArgumentException("Not a usable preset id: " + id);
        }
        return file;
    }

    private static long count(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json")).count();
        }
    }

    private static Instant parseInstant(String raw, Path path) {
        try {
            return raw.isBlank() ? Files.getLastModifiedTime(path).toInstant() : Instant.parse(raw);
        } catch (IOException | RuntimeException unknown) {
            return Instant.EPOCH;
        }
    }


    private static String stripExtension(String fileName) {
        var dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName).toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Target(Preset preset, Path path) {}
}
