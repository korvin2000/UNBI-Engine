package com.unbi.engine.settings.bundle;

import java.io.IOException;
import java.util.List;

/**
 * One kind of thing a settings bundle can carry: endpoint profiles, presets, the workflow library.
 *
 * <p>Discovered by injection, like nodes, profile schemas and credential sources: a bean implementing
 * this is a checkbox in the export dialog and a folder in the {@code .ucfg}. Each section owns its
 * own files — how they are named, what a valid one looks like, what "already exists" means for it —
 * because those are the things a generic copier gets wrong, one per store.
 *
 * <p>Entries a section writes all live under {@code <id>/} in the bundle, so two sections cannot
 * collide and an archive opened by hand reads as a table of contents.
 */
public interface SettingsSection {

    /** Stable identity, also the folder inside the bundle: {@code profiles}, {@code workflows}. */
    String id();

    /** What the export checklist calls it. The editor may translate by id and use this as the fallback. */
    String label();

    /** One line under the label saying what travels — and, for a sensitive section, what that means. */
    String description();

    /**
     * Whether the section carries secrets.
     *
     * <p>The dialog warns when such a section is exported without a password; nothing else is
     * different about it. Whether to accept that warning is the user's call.
     */
    default boolean sensitive() {
        return false;
    }

    /** Where the checklist puts it. Lower comes first. */
    default int order() {
        return 100;
    }

    /** How many items there are to export right now, for the checklist's count. */
    int count();

    /** Writes every item as entries under {@code id()/}. */
    void export(BundleWriter writer) throws IOException;

    /**
     * Reads this section's entries back into the store, applying the policy to anything that is
     * already there. Never throws for one bad entry: it is reported and the rest still land.
     */
    SectionReport importFrom(BundleReader reader, ConflictPolicy policy) throws IOException;

    /** What an import did to one section. */
    record SectionReport(String id, int imported, int replaced, int skipped, List<String> problems) {

        public SectionReport {
            problems = List.copyOf(problems == null ? List.of() : problems);
        }
    }
}
