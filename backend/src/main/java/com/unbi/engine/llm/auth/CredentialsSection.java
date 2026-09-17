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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Sensitive connection definitions travel in backups; rotating login sessions never do. */
@Component
public class CredentialsSection implements SettingsSection {
    static final String ENTRY = "credentials/credentials.properties";
    private static final String MANAGED = "credentials/definitions/";
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final PropertiesFileCredentials file;
    private final ManagedCredentialStore managed;
    private final CredentialStore credentials;

    public CredentialsSection(PropertiesFileCredentials file, ManagedCredentialStore managed, CredentialStore credentials) {
        this.file = file;
        this.managed = managed;
        this.credentials = credentials;
    }

    @Override public String id() { return "credentials"; }
    @Override public String label() { return "API keys & credentials"; }
    @Override public String description() {
        return "API keys, passwords and connection definitions are sensitive: protect the bundle with a password. "
                + "OAuth and ChatGPT connections require reconnect after import; login tokens are never exported.";
    }
    @Override public boolean sensitive() { return true; }
    @Override public int order() { return 20; }
    @Override public int count() { return file.names().size() + managed.names().size(); }

    @Override
    public void export(BundleWriter writer) throws IOException {
        if (Files.isRegularFile(file.location())) writer.add(ENTRY, Files.readAllBytes(file.location()));
        for (var name : managed.names()) {
            var entry = managed.find(name).orElseThrow(() -> new IOException("Credential changed during export"));
            var definition = MAPPER.createObjectNode().put("version", 1).put("name", name)
                    .put("type", entry.type().name().toLowerCase(Locale.ROOT));
            definition.set("configuration", configuration(entry.type(), entry.configuration()));
            writer.add(MANAGED + name + ".json", MAPPER.writeValueAsBytes(definition));
        }
    }

    @Override
    public SectionReport importFrom(BundleReader reader, ConflictPolicy policy) throws IOException {
        int[] counts = {0, 0, 0};
        var problems = new ArrayList<String>();
        if (!reader.entries(ENTRY).isEmpty()) {
            var incoming = new Properties();
            try (var input = new InputStreamReader(new ByteArrayInputStream(reader.read(ENTRY)), StandardCharsets.UTF_8)) {
                incoming.load(input);
            } catch (IllegalArgumentException malformed) {
                incoming.clear();
                problems.add("The API-key file is malformed");
            }
            for (var name : incoming.stringPropertyNames().stream().sorted().toList()) {
                try {
                    ManagedCredentialStore.validateName(name);
                    var value = incoming.getProperty(name, "").trim();
                    if (value.isEmpty()) throw new IllegalArgumentException();
                    var target = target(name, policy);
                    if (target == null) { counts[2]++; continue; }
                    boolean replacing = exists(target);
                    file.store(target, value);
                    if (managed.names().contains(target)) managed.remove(target);
                    counts[replacing ? 1 : 0]++;
                } catch (RuntimeException | IOException invalid) { problems.add("Could not import API-key definition: " + safeName(name)); }
            }
        }
        for (var path : reader.entries(MANAGED)) {
            var basename = path.substring(MANAGED.length());
            String name = basename.endsWith(".json") ? basename.substring(0, basename.length() - 5) : "";
            try {
                ManagedCredentialStore.validateName(name);
                var definition = MAPPER.readTree(reader.read(path));
                if (!definition.isObject() || definition.path("version").asInt() != 1
                        || !name.equals(definition.path("name").asString()) || definition.has("session"))
                    throw new IllegalArgumentException();
                var type = ManagedCredentialStore.Type.valueOf(definition.path("type").asString().toUpperCase(Locale.ROOT));
                if (!(definition.path("configuration") instanceof ObjectNode config)) throw new IllegalArgumentException();
                var sanitized = configuration(type, config);
                if (sanitized.size() != config.size()) throw new IllegalArgumentException();
                var target = target(name, policy);
                if (target == null) { counts[2]++; continue; }
                boolean replacing = exists(target);
                var current = managed.find(target);
                if (current.isPresent()) managed.replace(target, type, sanitized);
                else {
                    managed.create(target, type, sanitized);
                    file.remove(target);
                }
                counts[replacing ? 1 : 0]++;
            } catch (RuntimeException | IOException invalid) { problems.add("Could not import managed definition: " + safeName(name)); }
        }
        return new SectionReport(id(), counts[0], counts[1], counts[2], problems);
    }

    private boolean exists(String name) { return file.names().contains(name) || managed.names().contains(name); }

    private String target(String name, ConflictPolicy policy) {
        var owned = new LinkedHashSet<>(file.names());
        owned.addAll(managed.names());
        var all = new LinkedHashSet<>(credentials.names());
        all.addAll(owned);
        if (!all.contains(name)) return name;
        if (policy == ConflictPolicy.SKIP) return null;
        if (policy == ConflictPolicy.REPLACE) {
            if (!owned.contains(name)) throw new IllegalArgumentException("External credentials are read-only");
            return name;
        }
        for (int suffix = 2; ; suffix++) {
            var ending = "-" + suffix;
            var candidate = name.substring(0, Math.min(name.length(), 61 - ending.length())) + ending;
            if (!all.contains(candidate)) return candidate;
        }
    }

    private static ObjectNode configuration(ManagedCredentialStore.Type type, ObjectNode source) {
        var fields = ManagedCredentialStore.configurationFields(type);
        var result = MAPPER.createObjectNode();
        fields.forEach(field -> { if (source.has(field)) result.set(field, source.get(field).deepCopy()); });
        return result;
    }

    private static String safeName(String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,60}") ? name : "invalid name";
    }
}
