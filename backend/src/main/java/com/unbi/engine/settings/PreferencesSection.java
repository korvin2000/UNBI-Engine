package com.unbi.engine.settings;

import com.unbi.engine.json.JsonValues;
import com.unbi.engine.settings.bundle.BundleReader;
import com.unbi.engine.settings.bundle.BundleWriter;
import com.unbi.engine.settings.bundle.ConflictPolicy;
import com.unbi.engine.settings.bundle.SettingsSection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The editor's preferences in a settings bundle: {@code preferences/preferences.json}.
 *
 * <p>Preferences only — never the directory locations. A path is true of the machine the settings
 * were written on, and carrying {@code D:/unbi} to a laptop that has no D: is how a migration
 * strands someone on first launch.
 */
@Component
public class PreferencesSection implements SettingsSection {

    static final String ENTRY = "preferences/preferences.json";

    private final SettingsStore settings;
    private final ObjectMapper mapper = new ObjectMapper();

    public PreferencesSection(SettingsStore settings) {
        this.settings = settings;
    }

    @Override
    public String id() {
        return "preferences";
    }

    @Override
    public String label() {
        return "Application preferences";
    }

    @Override
    public String description() {
        return "Language and other editor preferences. Directory locations stay with this machine.";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public int count() {
        return settings.current().preferences().size();
    }

    @Override
    public void export(BundleWriter writer) throws IOException {
        var root = mapper.createObjectNode();
        settings.current().preferences().forEach((key, value) -> root.set(key, JsonValues.of(value)));
        writer.add(ENTRY, root.toPrettyString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public SectionReport importFrom(BundleReader reader, ConflictPolicy policy) throws IOException {
        if (reader.entries(ENTRY).isEmpty()) {
            return new SectionReport(id(), 0, 0, 0, List.of());
        }
        var incoming = mapper.readTree(reader.read(ENTRY));
        if (!incoming.isObject()) {
            return new SectionReport(id(), 0, 0, 0, List.of("preferences.json: not an object"));
        }
        var existing = settings.current().preferences();
        var changes = new LinkedHashMap<String, Object>();
        int imported = 0;
        int replaced = 0;
        int skipped = 0;
        for (var key : incoming.propertyNames()) {
            if (!existing.containsKey(key)) {
                changes.put(key, JsonValues.from(incoming.get(key)));
                imported++;
            } else if (policy == ConflictPolicy.REPLACE) {
                changes.put(key, JsonValues.from(incoming.get(key)));
                replaced++;
            } else {
                skipped++;
            }
        }
        try {
            if (!changes.isEmpty()) {
                settings.updatePreferences(changes);
            }
        } catch (IllegalArgumentException invalid) {
            return new SectionReport(id(), 0, 0, skipped, List.of("preferences.json: " + invalid.getMessage()));
        }
        return new SectionReport(id(), imported, replaced, skipped, List.of());
    }
}
