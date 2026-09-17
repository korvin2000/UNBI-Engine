package com.unbi.engine.workflows;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * One workflow in the library, as the listing describes it: a name, whether it is a favourite, and
 * when it last changed. The document itself is read separately, because a listing of two hundred
 * workflows should not read two hundred files.
 *
 * <p>The name <em>is</em> the file name (without {@code .unbi.json}), and is therefore the id. That
 * is deliberate: the library is a folder the user can look at, and a folder of files called what
 * the workflows are called is one nobody has to decode. It also means the name has to be one the
 * filesystem accepts on every platform, which {@link #validName} enforces before anything is written.
 *
 * @param favorite whether the file lives in the {@code favorites/} subdirectory
 */
public record StoredWorkflow(String id, String name, boolean favorite, Instant updatedAt, long size) {

    public static final String EXTENSION = ".unbi.json";

    /** Windows refuses these as file names whatever their extension. */
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private static final int MAX_NAME_LENGTH = 120;

    /**
     * The name trimmed and checked, or an {@link IllegalArgumentException} saying what is wrong with it.
     *
     * <p>Refuses what any platform's filesystem refuses, plus a leading dot (a hidden file nobody
     * sees in the folder) and a trailing dot or space (Windows silently strips them, so the file
     * would come back under a different name).
     */
    public static String validName(String raw) {
        var name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("A workflow needs a name.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A workflow name is at most %d characters.".formatted(MAX_NAME_LENGTH));
        }
        for (var i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || "<>:\"/\\|?*".indexOf(c) >= 0) {
                throw new IllegalArgumentException(
                        "A workflow name cannot contain any of < > : \" / \\ | ? * or control characters.");
            }
        }
        if (name.startsWith(".") || name.endsWith(".")) {
            throw new IllegalArgumentException("A workflow name cannot start or end with a dot.");
        }
        var stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (RESERVED.contains(stem.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("\"" + name + "\" is a reserved name on Windows.");
        }
        return name;
    }

    /** The file name this workflow is stored under. */
    public String fileName() {
        return name + EXTENSION;
    }
}
