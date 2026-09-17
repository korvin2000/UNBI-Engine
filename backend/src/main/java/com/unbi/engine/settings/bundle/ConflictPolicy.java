package com.unbi.engine.settings.bundle;

import java.util.Locale;

/** What an import does with an item that already exists on this engine. */
public enum ConflictPolicy {
    /** Leave what is here; the incoming one is counted and dropped. The default, because it cannot lose anything. */
    SKIP,
    /** The incoming one wins. */
    REPLACE,
    /** Both stay: the incoming one lands under a numbered name. */
    KEEP_BOTH;

    /** Accepts {@code skip}, {@code replace}, {@code keep-both} (and the underscore spelling). */
    public static ConflictPolicy parse(String raw) {
        return switch ((raw == null ? "" : raw).trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "", "skip" -> SKIP;
            case "replace" -> REPLACE;
            case "keep-both", "keepboth" -> KEEP_BOTH;
            default -> throw new IllegalArgumentException(
                    "conflicts must be \"skip\", \"replace\" or \"keep-both\", not \"" + raw + "\"");
        };
    }
}
