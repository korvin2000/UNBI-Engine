package com.unbi.engine.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * The listing behind the editor's file and folder dialogs, against a real temporary directory.
 *
 * <p>Called directly rather than through a web context: the interesting behaviour is what it makes
 * of a filesystem, and a servlet round trip would only test Spring.
 */
class FileSystemControllerTest {

    private final FileSystemController controller = new FileSystemController();

    @TempDir
    Path workspace;

    @BeforeEach
    void layOutFiles() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello");
        Files.writeString(workspace.resolve("readme.md"), "# hi");
        Files.writeString(workspace.resolve("archive.zip"), "not really a zip");
        Files.createDirectory(workspace.resolve("nested"));
    }

    @Test
    void listsFoldersBeforeFiles() {
        var names = namesIn(controller.list(workspace.toString(), false, null));

        assertThat(names).containsExactly("nested", "archive.zip", "notes.txt", "readme.md");
    }

    @Test
    @DisplayName("directoriesOnly hides files, which is what the folder picker needs")
    void directoriesOnlyHidesFiles() {
        var names = namesIn(controller.list(workspace.toString(), true, null));

        assertThat(names).containsExactly("nested");
    }

    @Test
    void filtersFilesByExtensionButKeepsFoldersReachable() {
        var names = namesIn(controller.list(workspace.toString(), false, "txt, .md"));

        // Folders always survive the filter: a picker that hid them could not be navigated.
        assertThat(names).containsExactly("nested", "notes.txt", "readme.md");
    }

    @Test
    void reportsSizeAndParentSoTheDialogCanNavigateUp() {
        var response = controller.list(workspace.toString(), false, null);

        assertThat(response.get("path").asString()).isEqualTo(workspace.toString());
        assertThat(response.get("parent").asString()).isEqualTo(workspace.getParent().toString());
        assertThat(response.get("roots").size()).isGreaterThan(0);

        var notes = entryNamed(response, "notes.txt");
        assertThat(notes.get("directory").asBoolean()).isFalse();
        assertThat(notes.get("size").asLong()).isEqualTo(5L);
    }

    @Test
    @DisplayName("a file path opens the folder that contains it")
    void resolvesAFileToItsFolder() {
        var response = controller.list(workspace.resolve("notes.txt").toString(), false, null);

        assertThat(response.get("path").asString()).isEqualTo(workspace.toString());
        assertThat(response.get("error").isNull()).isTrue();
    }

    @Test
    @DisplayName("a missing file opens its folder and says the file was not there")
    void explainsWhyItDidNotOpenWhatWasAskedFor() {
        var response = controller.list(workspace.resolve("does-not-exist.txt").toString(), false, null);

        // Landing somewhere else without a word would look like the dialog ignored the path.
        assertThat(response.get("path").asString()).isEqualTo(workspace.toString());
        assertThat(response.get("error").asString()).startsWith("Cannot open");
    }

    @Test
    @DisplayName("a path with no usable folder anywhere in it falls back to home")
    void fallsBackToHomeWhenNothingInThePathExists() {
        // `notes.txt` is a regular file, so nothing in this path can be opened as a folder.
        var response = controller.list(workspace.resolve("notes.txt").resolve("child").toString(), false, null);

        var home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        assertThat(response.get("path").asString()).isEqualTo(home.toString());
        assertThat(response.get("error").isNull()).isFalse();
    }

    private static List<String> namesIn(JsonNode response) {
        var names = new ArrayList<String>();
        response.get("entries").forEach(entry -> names.add(entry.get("name").asString()));
        return names;
    }

    private static JsonNode entryNamed(JsonNode response, String name) {
        for (var entry : response.get("entries")) {
            if (name.equals(entry.get("name").asString())) {
                return entry;
            }
        }
        throw new AssertionError("No entry named " + name);
    }
}
