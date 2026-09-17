package com.unbi.engine.settings.bundle;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code manifest.json}: the first entry of every bundle, and the one that is never encrypted.
 *
 * <p>Never encrypted so that the import dialog can say what a file holds, and whether it will ask
 * for a password, before anything is typed or touched. It carries counts, not contents — which
 * sections are there and how many items each has — so nothing sensitive is in the clear.
 *
 * @param counts items per section id, in checklist order
 */
public record BundleManifest(String engine, Instant createdAt, boolean encrypted, Map<String, Integer> counts) {

    public static final String ENTRY = "manifest.json";
    public static final String FORMAT = "unbi-settings-bundle";
    public static final int VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public BundleManifest {
        engine = engine == null ? "" : engine;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        // Not Map.copyOf, which forgets the order — and the order is the checklist's.
        counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts == null ? Map.of() : counts));
    }

    public byte[] toJson() {
        var root = MAPPER.createObjectNode();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("engine", engine);
        root.put("createdAt", createdAt.toString());
        root.put("encrypted", encrypted);
        var sections = root.putArray("sections");
        counts.forEach((id, count) -> sections.addObject().put("id", id).put("count", count));
        return root.toPrettyString().getBytes(StandardCharsets.UTF_8);
    }

    /** @throws IllegalArgumentException when the bytes are not a manifest this engine understands */
    public static BundleManifest parse(byte[] json) {
        try {
            var root = MAPPER.readTree(json);
            if (!FORMAT.equals(root.path("format").asString(""))) {
                throw new IllegalArgumentException("This is not a UNBI settings bundle.");
            }
            var version = root.path("version").asInt(0);
            if (version != VERSION) {
                throw new IllegalArgumentException(
                        "This bundle was written by a newer engine (format version " + version + ").");
            }
            var counts = new LinkedHashMap<String, Integer>();
            for (var section : root.path("sections")) {
                var id = section.path("id").asString("");
                if (!id.isEmpty()) {
                    counts.put(id, section.path("count").asInt(0));
                }
            }
            Instant created;
            try {
                created = Instant.parse(root.path("createdAt").asString(""));
            } catch (RuntimeException unparseable) {
                created = Instant.EPOCH;
            }
            return new BundleManifest(
                    root.path("engine").asString(""), created, root.path("encrypted").asBoolean(false), counts);
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (RuntimeException unreadable) {
            throw new IllegalArgumentException("This is not a UNBI settings bundle.", unreadable);
        }
    }
}
