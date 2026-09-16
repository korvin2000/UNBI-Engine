package com.unbi.engine.presets;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/**
 * A configured node, saved under a name so it can be used again somewhere else.
 *
 * <p>Deliberately generic rather than LLM-specific: a preset is "these widget values for this node
 * type", and that is as true of a scan-directory node as of a prompt. Making it a property of the
 * node system rather than of one pack is what stops a second pack from inventing its own.
 *
 * <p>{@code values} holds widget values, and widget values never hold secrets — the endpoint node
 * stores a credential's name, not its value. A preset is therefore safe to share by construction
 * rather than by a filter someone has to remember to keep correct.
 *
 * @param id       stable, filename-safe, derived from the name on first save
 * @param group    a folder for the palette; free text, because a fixed list would be wrong by the
 *                 second project
 */
public record Preset(
        String id,
        String name,
        String group,
        String nodeType,
        String description,
        Map<String, Object> values,
        Instant updatedAt) {

    /** Anything else would be a path, a shell argument, or an argument about encoding. */
    private static final String ID_PATTERN = "[a-z0-9][a-z0-9._-]{0,80}";

    public Preset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A preset needs a name");
        }
        if (nodeType == null || nodeType.isBlank()) {
            throw new IllegalArgumentException("A preset needs the node type it configures");
        }
        name = name.trim();
        group = group == null ? "" : group.trim();
        description = description == null ? "" : description.trim();
        values = Map.copyOf(values == null ? Map.of() : values);
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        id = id == null || id.isBlank() ? slug(nodeType + "-" + name) : id.trim().toLowerCase(Locale.ROOT);
        if (!id.matches(ID_PATTERN)) {
            throw new IllegalArgumentException("Not a usable preset id: " + id);
        }
    }

    /** Matches a free-text search over the fields a person would search by. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        var needle = query.trim().toLowerCase(Locale.ROOT);
        return name.toLowerCase(Locale.ROOT).contains(needle)
                || group.toLowerCase(Locale.ROOT).contains(needle)
                || description.toLowerCase(Locale.ROOT).contains(needle)
                || nodeType.toLowerCase(Locale.ROOT).contains(needle);
    }

    public Preset withId(String replacement) {
        return new Preset(replacement, name, group, nodeType, description, values, updatedAt);
    }

    /**
     * A readable, filename-safe id.
     *
     * <p>Readable because these files are edited by hand — a prompt worth saving is a prompt worth
     * opening in an editor — and a directory of UUIDs makes that impossible.
     */
    static String slug(String text) {
        var cleaned = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        var trimmed = cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
        return trimmed.isBlank() ? "preset" : trimmed;
    }
}
