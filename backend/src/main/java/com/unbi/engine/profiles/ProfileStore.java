package com.unbi.engine.profiles;

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
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Profiles on disk: one JSON file each, under {@code profiles/<schema>/} in the engine's data
 * directory, and the registry of the schemas they may belong to.
 *
 * <p>The same shape as the preset store, for the same reasons — hand-editable, diffable, one file
 * per thing, read fresh on every query — and deliberately not the same store. A preset is copied
 * into a node and a profile is referenced by one; conflating the two would put "the endpoint called
 * openrouter" into the Presets tab as something you drop onto a canvas, which it is not.
 *
 * <p>Schemas are checked once at startup: a field a profile dialog cannot draw, or a connectable
 * input that means nothing in a dialog, fails the engine while a developer is looking at it rather
 * than a user.
 */
@Component
public class ProfileStore {

    private static final Logger log = LoggerFactory.getLogger(ProfileStore.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final DataDirectory data;
    private final Map<String, ProfileSchema> schemas;
    private final Object writeLock = new Object();

    public ProfileStore(DataDirectory data, List<ProfileSchema> discovered) {
        this.data = data;
        var byId = new LinkedHashMap<String, ProfileSchema>();
        for (var schema : discovered) {
            if (byId.putIfAbsent(schema.id(), schema) != null) {
                throw new IllegalStateException("Two profile schemas claim the id " + schema.id());
            }
            schema.fields().stream().filter(field -> field.connectable() || !field.hasWidget()).findFirst()
                    .ifPresent(field -> {
                        throw new IllegalStateException(
                                ("Profile schema %s: field '%s' has no widget or is connectable, and a "
                                        + "profile dialog can draw neither").formatted(schema.id(), field.key()));
                    });
        }
        this.schemas = Map.copyOf(byId);
    }

    public List<ProfileSchema> schemas() {
        return List.copyOf(schemas.values());
    }

    public ProfileSchema schema(String id) {
        var schema = schemas.get(id == null ? "" : id.trim());
        if (schema == null) {
            throw new IllegalArgumentException("No profile schema named " + id);
        }
        return schema;
    }

    /** Every profile under one schema, alphabetically, with every field filled in. */
    public List<Profile> list(String schemaId) {
        var schema = schema(schemaId);
        return all(schema).stream()
                .sorted(Comparator.comparing(Profile::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Optional<Profile> find(String schemaId, String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        var schema = schema(schemaId);
        var wanted = id.trim().toLowerCase(Locale.ROOT);
        return all(schema).stream().filter(profile -> profile.id().equals(wanted)).findFirst();
    }

    /**
     * Writes a profile, giving a new one an id from its name.
     *
     * <p>A new profile whose derived id is taken gets a numbered one rather than overwriting: two
     * endpoints both called "Local" are a normal thing to have, and silently replacing the first is
     * not a normal thing to do about it. A profile that already has its id is the user saving over
     * their own work, which is what they meant.
     */
    public Profile save(Profile profile) throws IOException {
        var schema = schema(profile.schema());
        synchronized (writeLock) {
            var directory = directoryFor(schema);
            var target = profile.isNew()
                    ? uniqueTarget(directory, profile.withId(Profile.slug(profile.name())))
                    : new Target(profile, fileFor(directory, profile.id()));
            var stamped = new Profile(
                    target.profile().id(), schema.id(), profile.name(), profile.description(),
                    schema.withDefaults(profile.values()), Instant.now());
            Files.writeString(target.path(), toJson(stamped), StandardCharsets.UTF_8);
            return stamped;
        }
    }

    /** @return false when there was nothing to delete */
    public boolean delete(String schemaId, String id) throws IOException {
        var schema = schema(schemaId);
        synchronized (writeLock) {
            var existing = find(schema.id(), id);
            if (existing.isEmpty()) {
                return false;
            }
            return Files.deleteIfExists(fileFor(directoryFor(schema), existing.get().id()));
        }
    }

    private List<Profile> all(ProfileSchema schema) {
        var directory = data.file("profiles").resolve(schema.id());
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        var found = new ArrayList<Profile>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> read(schema, path).ifPresent(found::add));
        } catch (IOException | UncheckedIOException unreadable) {
            log.warn("Could not list {}: {}", directory, unreadable.getMessage());
        }
        return List.copyOf(found);
    }

    /** One corrupt file — hand-edited, half-written — must not hide every other profile. */
    private Optional<Profile> read(ProfileSchema schema, Path path) {
        try {
            var root = mapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            var values = new LinkedHashMap<String, Object>();
            var valuesNode = root.path("values");
            if (valuesNode.isObject()) {
                valuesNode.propertyNames().forEach(name -> values.put(name, JsonValues.from(valuesNode.get(name))));
            }
            return Optional.of(new Profile(
                    stripExtension(path.getFileName().toString()),
                    schema.id(),
                    root.path("name").asString(""),
                    root.path("description").asString(""),
                    schema.withDefaults(values),
                    parseInstant(root.path("updatedAt").asString(""), path)));
        } catch (IOException | RuntimeException unusable) {
            log.warn("Skipping profile {}: {}", path.getFileName(), unusable.getMessage());
            return Optional.empty();
        }
    }

    private String toJson(Profile profile) {
        var root = mapper.createObjectNode();
        root.put("name", profile.name());
        root.put("schema", profile.schema());
        root.put("description", profile.description());
        root.put("updatedAt", profile.updatedAt().toString());
        var values = root.putObject("values");
        profile.values().forEach((key, value) -> values.set(key, JsonValues.of(value)));
        return root.toPrettyString();
    }

    private Target uniqueTarget(Path directory, Profile profile) {
        var file = fileFor(directory, profile.id());
        if (!Files.exists(file)) {
            return new Target(profile, file);
        }
        for (int suffix = 2; suffix < 100; suffix++) {
            var candidate = profile.withId(profile.id() + "-" + suffix);
            var path = fileFor(directory, candidate.id());
            if (!Files.exists(path)) {
                return new Target(candidate, path);
            }
        }
        throw new IllegalStateException("Too many profiles named like " + profile.name());
    }

    private Path directoryFor(ProfileSchema schema) throws IOException {
        return data.directory("profiles/" + schema.id());
    }

    private static Path fileFor(Path directory, String id) {
        var file = directory.resolve(id + ".json").normalize();
        if (!file.startsWith(directory)) {
            throw new IllegalArgumentException("Not a usable profile id: " + id);
        }
        return file;
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

    private record Target(Profile profile, Path path) {}
}
