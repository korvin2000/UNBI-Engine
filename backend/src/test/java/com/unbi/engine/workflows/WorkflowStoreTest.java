package com.unbi.engine.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.settings.EngineHome;
import com.unbi.engine.settings.SettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkflowStoreTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static WorkflowStore storeAt(Path home) {
        return new WorkflowStore(new SettingsStore(new EngineHome(home)));
    }

    private static JsonNode workflow(String title) {
        return MAPPER.readTree("""
                {"format":"unbi-workflow","version":1,
                 "nodes":[{"id":"a","type":"util.preview","position":{"x":1,"y":2},"values":{"title":"%s"}}],
                 "edges":[]}""".formatted(title));
    }

    @Test
    void savesUnderTheNameAsAFileAndReadsItBack(@TempDir Path home) throws IOException {
        var store = storeAt(home);

        var saved = store.save("Nightly report", workflow("one"), false);

        assertThat(saved.id()).isEqualTo("Nightly report");
        assertThat(saved.favorite()).isFalse();
        assertThat(home.resolve("workflows/Nightly report.unbi.json")).exists();
        assertThat(store.read("Nightly report").orElseThrow().path("nodes").get(0).path("values").path("title").asString())
                .isEqualTo("one");
        assertThat(store.list()).extracting(StoredWorkflow::name).containsExactly("Nightly report");
    }

    @Test
    @DisplayName("a taken name is refused unless the caller asked to overwrite")
    void refusesToOverwriteSilently(@TempDir Path home) throws IOException {
        var store = storeAt(home);
        store.save("Flow", workflow("first"), false);

        assertThatThrownBy(() -> store.save("Flow", workflow("second"), false))
                .isInstanceOf(WorkflowStore.AlreadyExists.class)
                .hasMessageContaining("Flow");
        // Case does not make it a different workflow: that is one file on Windows and macOS.
        assertThatThrownBy(() -> store.save("flow", workflow("second"), false))
                .isInstanceOf(WorkflowStore.AlreadyExists.class);

        store.save("Flow", workflow("second"), true);
        assertThat(store.read("Flow").orElseThrow().path("nodes").get(0).path("values").path("title").asString())
                .isEqualTo("second");
        assertThat(store.list()).hasSize(1);
    }

    @Test
    void aFavouriteIsAFileInTheFavouritesFolder(@TempDir Path home) throws IOException {
        var store = storeAt(home);
        store.save("Flow", workflow("x"), false);

        var starred = store.setFavorite("Flow", true).orElseThrow();

        assertThat(starred.favorite()).isTrue();
        assertThat(home.resolve("workflows/favorites/Flow.unbi.json")).exists();
        assertThat(home.resolve("workflows/Flow.unbi.json")).doesNotExist();
        assertThat(store.find("Flow").orElseThrow().favorite()).isTrue();

        // Saving over a favourite keeps it where it is.
        store.save("Flow", workflow("y"), true);
        assertThat(home.resolve("workflows/favorites/Flow.unbi.json")).exists();
        assertThat(home.resolve("workflows/Flow.unbi.json")).doesNotExist();

        var unstarred = store.setFavorite("Flow", false).orElseThrow();
        assertThat(unstarred.favorite()).isFalse();
        assertThat(home.resolve("workflows/Flow.unbi.json")).exists();
    }

    @Test
    void renamesInPlaceAndRefusesATakenName(@TempDir Path home) throws IOException {
        var store = storeAt(home);
        store.save("Draft", workflow("x"), false);
        store.save("Other", workflow("y"), false);
        store.setFavorite("Draft", true);

        var renamed = store.rename("Draft", "Final").orElseThrow();

        assertThat(renamed.name()).isEqualTo("Final");
        assertThat(renamed.favorite()).isTrue();
        assertThat(home.resolve("workflows/favorites/Final.unbi.json")).exists();
        assertThatThrownBy(() -> store.rename("Final", "Other")).isInstanceOf(WorkflowStore.AlreadyExists.class);
        assertThat(store.rename("missing", "Whatever")).isEmpty();
    }

    @Test
    void deletesAndReportsWhenThereWasNothing(@TempDir Path home) throws IOException {
        var store = storeAt(home);
        store.save("Flow", workflow("x"), false);

        assertThat(store.delete("Flow")).isTrue();
        assertThat(store.delete("Flow")).isFalse();
        assertThat(store.list()).isEmpty();
    }

    @Test
    void listsFavouritesAndTheRestTogetherAlphabetically(@TempDir Path home) throws IOException {
        var store = storeAt(home);
        store.save("beta", workflow("b"), false);
        store.save("Alpha", workflow("a"), false);
        store.save("gamma", workflow("g"), false);
        store.setFavorite("gamma", true);

        assertThat(store.list()).extracting(StoredWorkflow::name).containsExactly("Alpha", "beta", "gamma");
        assertThat(store.list()).extracting(StoredWorkflow::favorite).containsExactly(false, false, true);
        assertThat(store.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("a name that is a path, a reserved device or hidden is refused before anything is written")
    void refusesUnusableNames(@TempDir Path home) {
        var store = storeAt(home);
        for (var bad : new String[] {"", "  ", "../escape", "a/b", "a\\b", "con", "NUL.flow", ".hidden", "trailing.", "x:y", "a\tb"}) {
            assertThatThrownBy(() -> store.save(bad, workflow("x"), false), bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(home.resolve("workflows")).doesNotExist();
    }

    @Test
    void refusesWhatIsNotAWorkflow(@TempDir Path home) {
        var store = storeAt(home);

        assertThatThrownBy(() -> store.save("x", MAPPER.readTree("{\"format\":\"unbi-preset\"}"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a UNBI workflow");
        assertThatThrownBy(() -> store.save("x", MAPPER.readTree("{\"format\":\"unbi-workflow\",\"version\":2,\"nodes\":[],\"edges\":[]}"), false))
                .hasMessageContaining("version 2");
        assertThatThrownBy(() -> store.save("x", MAPPER.readTree("{\"format\":\"unbi-workflow\",\"version\":1,\"nodes\":{}}"), false))
                .hasMessageContaining("nodes or edges");
    }

    @Test
    void aFileDroppedIntoTheFolderByHandIsListed(@TempDir Path home) throws IOException {
        var store = storeAt(home);
        Files.createDirectories(home.resolve("workflows/favorites"));
        Files.writeString(home.resolve("workflows/favorites/Hand-made.unbi.json"), workflow("h").toString());
        Files.writeString(home.resolve("workflows/notes.txt"), "not a workflow");

        assertThat(store.list()).extracting(StoredWorkflow::name).containsExactly("Hand-made");
        assertThat(store.find("hand-made").orElseThrow().favorite()).isTrue();
    }
}
