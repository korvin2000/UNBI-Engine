package com.unbi.engine.presets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.config.DataDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresetStoreTest {

    private static PresetStore storeIn(Path directory) {
        return new PresetStore(new DataDirectory(directory));
    }

    private static Preset preset(String name, String group, String nodeType, Map<String, Object> values) {
        return new Preset("", name, group, nodeType, "", values, Instant.now());
    }

    @Nested
    class RoundTrip {

        @Test
        void savesAndReadsBackEverySortOfValue(@TempDir Path directory) throws IOException {
            var store = storeIn(directory);
            var saved = store.save(preset("German Summary", "Translation", "llm.prompt", Map.of(
                    "template", "Fasse {{input}} zusammen",
                    "strict", true,
                    "rows", 8d,
                    "capabilities", List.of("json_object", "vision"))));

            var reloaded = storeIn(directory).find(saved.id()).orElseThrow();
            assertThat(reloaded.name()).isEqualTo("German Summary");
            assertThat(reloaded.group()).isEqualTo("Translation");
            assertThat(reloaded.values())
                    .containsEntry("template", "Fasse {{input}} zusammen")
                    .containsEntry("strict", true)
                    .containsEntry("rows", 8d)
                    .containsEntry("capabilities", List.of("json_object", "vision"));
        }

        @Test
        @DisplayName("the file is named after the preset, because these get edited by hand")
        void theFileNameIsReadable(@TempDir Path directory) throws IOException {
            storeIn(directory).save(preset("German Summary", "", "llm.prompt", Map.of()));
            assertThat(directory.resolve("presets")).isDirectoryContaining(
                    path -> path.getFileName().toString().equals("llm-prompt-german-summary.json"));
        }

        @Test
        @DisplayName("saving again under the same id replaces it rather than piling up copies")
        void resavingUpdatesInPlace(@TempDir Path directory) throws IOException {
            var store = storeIn(directory);
            var first = store.save(preset("Summary", "", "llm.prompt", Map.of("template", "one")));
            var second = store.save(new Preset(
                    first.id(), "Summary", "", "llm.prompt", "", Map.of("template", "two"), Instant.now()));

            assertThat(second.id()).isEqualTo(first.id());
            assertThat(store.query("", "", "")).hasSize(1);
            assertThat(store.find(first.id()).orElseThrow().values()).containsEntry("template", "two");
        }

        @Test
        @DisplayName("two different node types may share a name without overwriting each other")
        void sameNameDifferentTypeGetsItsOwnFile(@TempDir Path directory) throws IOException {
            var store = storeIn(directory);
            var prompt = store.save(preset("Default", "", "llm.prompt", Map.of()));
            var model = store.save(preset("Default", "", "llm.model", Map.of()));

            assertThat(model.id()).isNotEqualTo(prompt.id());
            assertThat(store.query("", "", "")).hasSize(2);
        }
    }

    @Nested
    class Discovery {

        private PresetStore populated(Path directory) throws IOException {
            var store = storeIn(directory);
            store.save(preset("German Summary", "Translation", "llm.prompt", Map.of()));
            store.save(preset("English Summary", "Translation", "llm.prompt", Map.of()));
            store.save(preset("Local llama", "Infrastructure", "llm.endpoint", Map.of()));
            return store;
        }

        @Test
        void filtersByNodeType(@TempDir Path directory) throws IOException {
            assertThat(populated(directory).query("llm.endpoint", "", ""))
                    .singleElement()
                    .extracting(Preset::name).isEqualTo("Local llama");
        }

        @Test
        void filtersByGroup(@TempDir Path directory) throws IOException {
            assertThat(populated(directory).query("", "Translation", "")).hasSize(2);
        }

        @Test
        void searchesNameGroupAndType(@TempDir Path directory) throws IOException {
            var store = populated(directory);
            assertThat(store.query("", "", "german")).hasSize(1);
            assertThat(store.query("", "", "summary")).hasSize(2);
            assertThat(store.query("", "", "endpoint")).hasSize(1);
        }

        @Test
        void filtersCombine(@TempDir Path directory) throws IOException {
            assertThat(populated(directory).query("llm.prompt", "Translation", "english")).hasSize(1);
        }

        @Test
        void listsTheGroupsInUse(@TempDir Path directory) throws IOException {
            assertThat(populated(directory).groups()).containsExactly("Infrastructure", "Translation");
        }

        @Test
        @DisplayName("an empty store is empty, not an error")
        void nothingSavedYetIsFine(@TempDir Path directory) {
            var store = storeIn(directory);
            assertThat(store.query("", "", "")).isEmpty();
            assertThat(store.find("anything")).isEmpty();
            assertThat(store.groups()).isEmpty();
        }
    }

    @Nested
    class Deletion {

        @Test
        void deletesByIdAndSaysWhetherItFoundOne(@TempDir Path directory) throws IOException {
            var store = storeIn(directory);
            var saved = store.save(preset("Summary", "", "llm.prompt", Map.of()));

            assertThat(store.delete(saved.id())).isTrue();
            assertThat(store.delete(saved.id())).isFalse();
            assertThat(store.query("", "", "")).isEmpty();
        }
    }

    @Nested
    class Robustness {

        @Test
        @DisplayName("one unreadable file does not hide every other preset")
        void aCorruptFileIsSkipped(@TempDir Path directory) throws IOException {
            var store = storeIn(directory);
            store.save(preset("Good", "", "llm.prompt", Map.of()));
            Files.writeString(directory.resolve("presets").resolve("broken.json"), "{ not json");

            assertThat(store.query("", "", "")).singleElement()
                    .extracting(Preset::name).isEqualTo("Good");
        }

        @Test
        @DisplayName("a preset id that tries to escape the directory is refused, not resolved")
        void idsCannotTraversePaths() {
            assertThatThrownBy(() -> new Preset(
                            "../../etc/passwd", "x", "", "llm.prompt", "", Map.of(), Instant.now()))
                    .hasMessageContaining("usable preset id");
        }

        @Test
        void aPresetNeedsANameAndANodeType() {
            assertThatThrownBy(() -> preset("  ", "", "llm.prompt", Map.of()))
                    .hasMessageContaining("name");
            assertThatThrownBy(() -> preset("x", "", "", Map.of()))
                    .hasMessageContaining("node type");
        }

        @Test
        @DisplayName("a file dropped into the directory by hand is picked up without a restart")
        void handWrittenPresetsAreFound(@TempDir Path directory) throws IOException {
            var presets = directory.resolve("presets");
            Files.createDirectories(presets);
            Files.writeString(presets.resolve("hand-written.json"), """
                    {"name":"By Hand","group":"Manual","nodeType":"llm.prompt",
                     "description":"","values":{"template":"hi"}}""");

            assertThat(storeIn(directory).find("hand-written")).get()
                    .extracting(Preset::name).isEqualTo("By Hand");
        }
    }
}
