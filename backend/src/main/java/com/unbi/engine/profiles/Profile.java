package com.unbi.engine.profiles;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * One saved configuration under one schema.
 *
 * <p>Holds widget values only. The endpoint schema stores a credential's <em>name</em>, so a profile
 * is safe to share, commit and paste into a ticket by construction rather than by a filter someone
 * has to keep correct.
 *
 * @param id     filename-safe, blank until the store assigns one from the name on first save, and
 *               stable afterwards
 * @param schema which {@link ProfileSchema} the values belong to
 */
public record Profile(
        String id,
        String schema,
        String name,
        String description,
        Map<String, Object> values,
        Instant updatedAt) {

    /** Anything else would be a path, a shell argument, or an argument about encoding. */
    private static final String ID_PATTERN = "[a-z0-9][a-z0-9._-]{0,80}";

    public Profile {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("A profile needs the schema it belongs to");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A profile needs a name");
        }
        name = name.trim();
        description = description == null ? "" : description.trim();
        values = withoutNulls(values);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (!id.isEmpty() && !id.matches(ID_PATTERN)) {
            throw new IllegalArgumentException("Not a usable profile id: " + id);
        }
    }

    /** True until the store has given this profile an id. */
    public boolean isNew() {
        return id.isEmpty();
    }

    /** A null value is an unset field, and an immutable map cannot hold one; it is left out. */
    private static Map<String, Object> withoutNulls(Map<String, Object> values) {
        if (values == null) {
            return Map.of();
        }
        var copy = new java.util.LinkedHashMap<String, Object>();
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    public Profile withId(String replacement) {
        return new Profile(replacement, schema, name, description, values, updatedAt);
    }

    public Profile withValues(Map<String, Object> replacement) {
        return new Profile(id, schema, name, description, replacement, updatedAt);
    }

    /** A readable, filename-safe id — these files are meant to be opened in an editor. */
    static String slug(String text) {
        var cleaned = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        var trimmed = cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
        return trimmed.isBlank() ? "profile" : trimmed;
    }
}
