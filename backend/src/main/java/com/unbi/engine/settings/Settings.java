package com.unbi.engine.settings;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What {@code settings.json} holds: two relocatable directories and the editor's preferences.
 *
 * <p>Deliberately small on the engine side. A path is the engine's business because the engine is
 * what opens it; a preference is the editor's — the language, and whatever the editor adds next —
 * so the engine keeps preferences as an opaque map of scalars, validates their shape, and never
 * reads one. Adding a preference is therefore a frontend change and nothing here moves.
 *
 * @param dataDirectory      where profiles, credentials and presets live; blank for the home
 * @param workflowsDirectory where the workflow library lives; blank for {@code <home>/workflows}
 * @param preferences        scalar values under short keys, owned by the editor
 */
public record Settings(String dataDirectory, String workflowsDirectory, Map<String, Object> preferences) {

    public static final Settings DEFAULTS = new Settings("", "", Map.of());

    static final int MAX_PREFERENCES = 64;
    private static final String KEY_PATTERN = "[A-Za-z][A-Za-z0-9._-]{0,60}";
    private static final int MAX_VALUE_LENGTH = 4_000;

    public Settings {
        dataDirectory = normalisePath(dataDirectory, "data directory");
        workflowsDirectory = normalisePath(workflowsDirectory, "workflows directory");
        preferences = validPreferences(preferences);
    }

    public Settings withDataDirectory(String directory) {
        return new Settings(directory, workflowsDirectory, preferences);
    }

    public Settings withWorkflowsDirectory(String directory) {
        return new Settings(dataDirectory, directory, preferences);
    }

    public Settings withPreferences(Map<String, Object> replacement) {
        return new Settings(dataDirectory, workflowsDirectory, replacement);
    }

    /** The preferences with these changes applied: a {@code null} value removes the key. */
    public Settings withPreferenceChanges(Map<String, Object> changes) {
        var merged = new LinkedHashMap<>(preferences);
        if (changes != null) {
            changes.forEach((key, value) -> {
                if (value == null) {
                    merged.remove(key);
                } else {
                    merged.put(key, value);
                }
            });
        }
        return withPreferences(merged);
    }

    /**
     * A configured directory as it is stored: blank for "the default", otherwise absolute and
     * normalised.
     *
     * <p>Relative paths are refused rather than resolved, because they would resolve against
     * whichever working directory the engine happened to be launched from — and a data directory
     * that moves with the shell's current folder is a data directory that gets lost.
     */
    static String normalisePath(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Path path;
        try {
            path = Path.of(raw.trim());
        } catch (InvalidPathException malformed) {
            throw new IllegalArgumentException("Not a valid path for the " + what + ": " + raw.trim());
        }
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("The " + what + " must be an absolute path: " + raw.trim());
        }
        return path.normalize().toString();
    }

    private static Map<String, Object> validPreferences(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.size() > MAX_PREFERENCES) {
            throw new IllegalArgumentException("Too many preferences: " + raw.size());
        }
        var copy = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> {
            if (key == null || !key.matches(KEY_PATTERN)) {
                throw new IllegalArgumentException("Not a usable preference key: " + key);
            }
            switch (value) {
                case null -> { }
                case String text -> {
                    if (text.length() > MAX_VALUE_LENGTH) {
                        throw new IllegalArgumentException("Preference " + key + " is too long.");
                    }
                    copy.put(key, text);
                }
                case Boolean flag -> copy.put(key, flag);
                case Number number -> copy.put(key, number.doubleValue());
                default -> throw new IllegalArgumentException(
                        "A preference is text, a number or a switch: " + key);
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
