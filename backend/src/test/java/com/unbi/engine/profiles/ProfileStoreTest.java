package com.unbi.engine.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.core.node.NodeInput;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileStoreTest {

    /** A two-field schema, enough to show defaults being filled in and unknown keys surviving. */
    private static final ProfileSchema SERVERS = new ProfileSchema() {
        @Override
        public String id() {
            return "test.server";
        }

        @Override
        public String label() {
            return "Server";
        }

        @Override
        public List<NodeInput> fields() {
            return List.of(
                    new NodeInput("url", "URL", Types.TEXT, false, false, Widget.TextField.of(""), "", null, false, null),
                    new NodeInput("retries", "Retries", Types.NUMBER, false, false,
                            Widget.NumberField.of(0, 9), 3d, null, true, null));
        }
        @Override
        public void validate(Map<String, Object> values) {
            if ("bad".equals(values.get("url"))) {
                throw new IllegalArgumentException("URL rejected");
            }
        }
    };

    private static ProfileStore storeIn(Path directory) {
        return new ProfileStore(new DataDirectory(directory), List.of(SERVERS));
    }

    private static Profile profile(String name, Map<String, Object> values) {
        return new Profile("", "test.server", name, "", values, Instant.now());
    }

    @Test
    void savesAndReadsBackWithDefaultsFilledIn(@TempDir Path directory) throws IOException {
        var saved = storeIn(directory).save(profile("Lab", Map.of("url", "http://lab/v1")));

        var reloaded = storeIn(directory).find("test.server", saved.id()).orElseThrow();
        assertThat(reloaded.name()).isEqualTo("Lab");
        assertThat(reloaded.values()).containsEntry("url", "http://lab/v1").containsEntry("retries", 3d);
        assertThat(directory.resolve("profiles/test.server/lab.json")).exists();
    }

    @Test
    void schemaValidationRejectsBeforeWriting(@TempDir Path directory) {
        var store = storeIn(directory);
        assertThatThrownBy(() -> store.save(profile("Rejected", Map.of("url", "bad"))))
                .hasMessageContaining("URL rejected");
        assertThat(directory.resolve("profiles/test.server/rejected.json")).doesNotExist();
    }
    @Test
    @DisplayName("a second profile with the same name gets its own file rather than replacing the first")
    void sameNameDoesNotOverwrite(@TempDir Path directory) throws IOException {
        var store = storeIn(directory);
        var first = store.save(profile("Lab", Map.of("url", "a")));
        var second = store.save(profile("Lab", Map.of("url", "b")));

        assertThat(second.id()).isEqualTo("lab-2");
        assertThat(store.find("test.server", first.id()).orElseThrow().values()).containsEntry("url", "a");
    }

    @Test
    void savingWithAnIdUpdatesInPlace(@TempDir Path directory) throws IOException {
        var store = storeIn(directory);
        var saved = store.save(profile("Lab", Map.of("url", "a")));
        store.save(new Profile(saved.id(), "test.server", "Lab renamed", "", Map.of("url", "b"), null));

        assertThat(store.list("test.server")).hasSize(1);
        assertThat(store.find("test.server", saved.id()).orElseThrow().name()).isEqualTo("Lab renamed");
    }

    @Test
    void listsAlphabeticallyAndDeletes(@TempDir Path directory) throws IOException {
        var store = storeIn(directory);
        store.save(profile("Zed", Map.of()));
        var alpha = store.save(profile("Alpha", Map.of()));

        assertThat(store.list("test.server")).extracting(Profile::name).containsExactly("Alpha", "Zed");
        assertThat(store.delete("test.server", alpha.id())).isTrue();
        assertThat(store.delete("test.server", alpha.id())).isFalse();
        assertThat(store.list("test.server")).extracting(Profile::name).containsExactly("Zed");
    }

    @Test
    @DisplayName("a hand-written file missing a field still loads, with the field at its default")
    void aCorruptFileIsSkippedAndAPartialOneIsCompleted(@TempDir Path directory) throws IOException {
        var folder = Files.createDirectories(directory.resolve("profiles/test.server"));
        Files.writeString(folder.resolve("partial.json"), "{\"name\":\"Partial\",\"values\":{\"url\":\"x\"}}", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("broken.json"), "{ not json", StandardCharsets.UTF_8);

        var found = storeIn(directory).list("test.server");
        assertThat(found).hasSize(1);
        assertThat(found.getFirst().values()).containsEntry("retries", 3d);
    }

    @Test
    void anUnknownSchemaIsRefused(@TempDir Path directory) {
        assertThatThrownBy(() -> storeIn(directory).list("test.other")).hasMessageContaining("test.other");
    }

    @Test
    void aSchemaWithAConnectableFieldIsRefusedAtStartup(@TempDir Path directory) {
        var broken = new ProfileSchema() {
            @Override
            public String id() {
                return "test.broken";
            }

            @Override
            public String label() {
                return "Broken";
            }

            @Override
            public List<NodeInput> fields() {
                return List.of(new NodeInput("in", "In", Types.TEXT, false, true, null, null, null, false, null));
            }
        };
        assertThatThrownBy(() -> new ProfileStore(new DataDirectory(directory), List.of(broken)))
                .hasMessageContaining("test.broken");
    }
}
