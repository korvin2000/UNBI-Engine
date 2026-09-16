package com.unbi.engine.llm.auth;

import com.unbi.engine.config.DataDirectory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.SequencedSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Credentials from {@code credentials.properties} in the engine's data directory.
 *
 * <p>For the person running the engine on their own machine, where exporting a variable per gateway
 * before every launch is friction they will route around by pasting a key into a node instead —
 * which is the outcome this whole layer exists to prevent.
 *
 * <p>Read fresh on every lookup rather than cached. A key added while the engine is running should
 * work on the next run, not after a restart; the file is a few hundred bytes and a call to an LLM
 * takes seconds.
 */
@Component
public class PropertiesFileCredentials implements CredentialSource {

    public static final String FILE_NAME = "credentials.properties";

    private static final Logger log = LoggerFactory.getLogger(PropertiesFileCredentials.class);

    private final Path file;

    @Autowired
    public PropertiesFileCredentials(DataDirectory data) {
        this(data.file(FILE_NAME));
    }

    /** Package-private: the test seam, and the reason the constructor above is annotated. */
    PropertiesFileCredentials(Path file) {
        this.file = file;
    }

    @Override
    public String id() {
        return "file";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Optional<Credential> find(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        var value = load().getProperty(ref.trim());
        return value == null || value.isBlank()
                ? Optional.empty()
                : Optional.of(Credential.bearer(ref.trim(), value.trim()));
    }

    @Override
    public SequencedSet<String> names() {
        var found = new LinkedHashSet<String>();
        var loaded = load();
        loaded.stringPropertyNames().stream().sorted().forEach(name -> {
            if (!loaded.getProperty(name).isBlank()) {
                found.add(name);
            }
        });
        return found;
    }

    /** Where a user should put the file, for the message shown when a reference is not found. */
    public Path location() {
        return file;
    }

    /**
     * Writes one key, creating the file on first use and replacing a key of the same name.
     *
     * <p>Written through {@link Properties#store} rather than appended, so a value containing a
     * character the format escapes is read back as what was typed. The file is the user's own and
     * stays hand-editable; this only ever changes the one line it was asked about.
     */
    public synchronized void store(String name, String value) throws IOException {
        var properties = load();
        properties.setProperty(name.trim(), value);
        write(properties);
    }

    /** @return false when there was no such key in the file */
    public synchronized boolean remove(String name) throws IOException {
        var properties = load();
        if (properties.remove(name == null ? "" : name.trim()) == null) {
            return false;
        }
        write(properties);
        return true;
    }

    private void write(Properties properties) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "UNBI-Engine credentials — one key per line, by name. Never share this file.");
        }
    }

    private Properties load() {
        var properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException unreadable) {
            // A malformed credentials file must not take the engine down: every other source still
            // works, and the node that needed this one says so by name.
            log.warn("Could not read {}: {}", file, unreadable.getMessage());
            return new Properties();
        } catch (UncheckedIOException unreadable) {
            log.warn("Could not read {}: {}", file, unreadable.getMessage());
            return new Properties();
        }
        return properties;
    }
}
