package com.unbi.engine.llm.auth;

import com.unbi.engine.settings.bundle.BundleReader;
import com.unbi.engine.settings.bundle.BundleWriter;
import com.unbi.engine.settings.bundle.ConflictPolicy;
import com.unbi.engine.settings.bundle.SettingsSection;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * The keys in {@code credentials.properties}, in a settings bundle.
 *
 * <p>The one sensitive section, and the one that is not a folder of files: it is merged key by key
 * through the same writer the profile dialog uses, so a key that already exists here is handled by
 * the conflict policy rather than by whichever file happened to be copied last. Keys the engine
 * only knows from the environment are not the file's and are not exported.
 */
@Component
public class CredentialsSection implements SettingsSection {

    static final String ENTRY = "credentials/credentials.properties";

    private static final String NAME_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,60}";

    private final PropertiesFileCredentials file;

    public CredentialsSection(PropertiesFileCredentials file) {
        this.file = file;
    }

    @Override
    public String id() {
        return "credentials";
    }

    @Override
    public String label() {
        return "API keys & credentials";
    }

    @Override
    public String description() {
        return "The keys in credentials.properties, by name. These are secrets: protect the bundle "
                + "with a password, or they travel in plain text.";
    }

    @Override
    public boolean sensitive() {
        return true;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public int count() {
        return file.names().size();
    }

    @Override
    public void export(BundleWriter writer) throws IOException {
        var location = file.location();
        if (Files.isRegularFile(location)) {
            writer.add(ENTRY, Files.readAllBytes(location));
        }
    }

    @Override
    public SectionReport importFrom(BundleReader reader, ConflictPolicy policy) throws IOException {
        if (reader.entries(ENTRY).isEmpty()) {
            return new SectionReport(id(), 0, 0, 0, List.of());
        }
        var incoming = new Properties();
        try (var in = new InputStreamReader(new ByteArrayInputStream(reader.read(ENTRY)), StandardCharsets.UTF_8)) {
            incoming.load(in);
        } catch (IllegalArgumentException malformed) {
            return new SectionReport(id(), 0, 0, 0, List.of("credentials.properties: " + malformed.getMessage()));
        }

        int imported = 0;
        int replaced = 0;
        int skipped = 0;
        var problems = new ArrayList<String>();
        for (var name : incoming.stringPropertyNames().stream().sorted().toList()) {
            var value = incoming.getProperty(name, "").trim();
            if (!name.matches(NAME_PATTERN)) {
                problems.add(name + ": not a usable credential name");
                continue;
            }
            if (value.isEmpty()) {
                problems.add(name + ": empty");
                continue;
            }
            var existing = file.names();
            if (!existing.contains(name)) {
                file.store(name, value);
                imported++;
                continue;
            }
            switch (policy) {
                case SKIP -> skipped++;
                case REPLACE -> {
                    file.store(name, value);
                    replaced++;
                }
                case KEEP_BOTH -> {
                    var free = name;
                    for (var suffix = 2; existing.contains(free); suffix++) {
                        free = name + "-" + suffix;
                    }
                    file.store(free, value);
                    imported++;
                }
            }
        }
        return new SectionReport(id(), imported, replaced, skipped, problems);
    }
}
