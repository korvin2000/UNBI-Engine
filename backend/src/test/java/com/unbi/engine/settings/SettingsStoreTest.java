package com.unbi.engine.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.presets.Preset;
import com.unbi.engine.presets.PresetStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {

    private static SettingsStore storeAt(Path home) {
        return new SettingsStore(new EngineHome(home));
    }

    @Test
    void startsFromDefaultsWhenThereIsNoFileYet(@TempDir Path home) {
        var store = storeAt(home);

        assertThat(store.current()).isEqualTo(Settings.DEFAULTS);
        assertThat(store.loadError()).isEmpty();
        assertThat(store.dataRoot()).isEqualTo(home);
        assertThat(store.workflowsRoot()).isEqualTo(home.resolve("workflows"));
        assertThat(home.resolve("settings.json")).doesNotExist();
    }

    @Test
    void preferencesSurviveARestart(@TempDir Path home) throws IOException {
        storeAt(home).updatePreferences(Map.of("language", "en", "confirmDelete", true, "zoom", 1.5));

        var reloaded = storeAt(home);
        assertThat(reloaded.current().preferences())
                .containsEntry("language", "en")
                .containsEntry("confirmDelete", true)
                .containsEntry("zoom", 1.5);
        assertThat(Files.readString(home.resolve("settings.json"))).contains("\"format\" : \"unbi-settings\"");
    }

    @Test
    void aNullPreferenceRemovesTheKey(@TempDir Path home) throws IOException {
        var store = storeAt(home);
        store.updatePreferences(Map.of("language", "de", "theme", "dark"));
        var changes = new HashMap<String, Object>();
        changes.put("theme", null);
        store.updatePreferences(changes);

        assertThat(store.current().preferences()).containsOnlyKeys("language");
    }

    @Test
    void refusesAPreferenceThatIsNotAScalar(@TempDir Path home) {
        var store = storeAt(home);

        assertThatThrownBy(() -> store.updatePreferences(Map.of("layout", List.of(1, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layout");
        assertThatThrownBy(() -> store.updatePreferences(Map.of("bad key", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a relative data directory is refused rather than resolved against the working directory")
    void refusesRelativePaths(@TempDir Path home) {
        var store = storeAt(home);

        assertThatThrownBy(() -> store.relocate(SettingsStore.Target.DATA, "data/here", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void pointingElsewhereWithoutCopyingLeavesTheOldFilesAlone(@TempDir Path home, @TempDir Path elsewhere)
            throws IOException {
        var store = storeAt(home);
        var presets = new PresetStore(new DataDirectory(store));
        presets.save(preset("Summarise"));

        var done = store.relocate(SettingsStore.Target.DATA, elsewhere.toString(), false);

        assertThat(done.filesCopied()).isZero();
        assertThat(store.dataRoot()).isEqualTo(elsewhere);
        assertThat(home.resolve("presets/llm-prompt-summarise.json")).exists();
        assertThat(elsewhere.resolve("presets")).doesNotExist();
        // The preset store follows the setting immediately: nothing is cached.
        assertThat(presets.query("", "", "")).isEmpty();
    }

    @Test
    @DisplayName("relocating with copy brings the data entries along and leaves the originals in place")
    void copiesDataEntriesToTheNewDirectory(@TempDir Path home, @TempDir Path elsewhere) throws IOException {
        var store = storeAt(home);
        var presets = new PresetStore(new DataDirectory(store));
        presets.save(preset("Summarise"));
        Files.createDirectories(home.resolve("profiles/llm.endpoint"));
        Files.writeString(home.resolve("profiles/llm.endpoint/lab.json"), "{}");
        Files.writeString(home.resolve("credentials.properties"), "openrouter=secret\n", StandardCharsets.UTF_8);
        Files.createDirectories(home.resolve("workflows"));
        Files.writeString(home.resolve("workflows/flow.unbi.json"), "{}");

        var done = store.relocate(SettingsStore.Target.DATA, elsewhere.toString(), true);

        assertThat(done.filesCopied()).isEqualTo(3);
        assertThat(done.filesSkipped()).isZero();
        assertThat(elsewhere.resolve("presets/llm-prompt-summarise.json")).exists();
        assertThat(elsewhere.resolve("profiles/llm.endpoint/lab.json")).exists();
        assertThat(elsewhere.resolve("credentials.properties")).exists();
        // Neither the settings file nor the workflow library is data, so neither travels.
        assertThat(elsewhere.resolve("settings.json")).doesNotExist();
        assertThat(elsewhere.resolve("workflows")).doesNotExist();
        // Copied, not moved.
        assertThat(home.resolve("presets/llm-prompt-summarise.json")).exists();
        assertThat(presets.find("llm-prompt-summarise")).isPresent();
        assertThat(storeAt(home).dataRoot()).isEqualTo(elsewhere);
    }

    @Test
    void dataLeaseBlocksRelocationWithoutChangingTheCurrentRoot(@TempDir Path home, @TempDir Path elsewhere)
            throws IOException {
        var store = storeAt(home);
        var data = new DataDirectory(store);

        try (var lease = data.acquireLease()) {
            assertThat(lease.root()).isEqualTo(home);
            assertThatThrownBy(() -> store.relocate(SettingsStore.Target.DATA, elsewhere.toString(), false))
                    .isInstanceOf(SettingsStore.DataBusyException.class)
                    .hasMessageContaining("finish or cancel authentication");
            assertThat(store.dataRoot()).isEqualTo(home);
        }

        store.relocate(SettingsStore.Target.DATA, elsewhere.toString(), false);
        assertThat(store.dataRoot()).isEqualTo(elsewhere);
    }

    @Test
    void relocationCopiesPrivateManagedCredentialPermissions(@TempDir Path home, @TempDir Path elsewhere)
            throws IOException {
        var store = storeAt(home);
        var credentials = home.resolve("credentials");
        Files.createDirectories(credentials.resolve("nested"));
        Files.writeString(credentials.resolve("nested/session.json"), "secret");
        Files.setPosixFilePermissions(credentials, PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(credentials.resolve("nested"), PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(
                credentials.resolve("nested/session.json"), PosixFilePermissions.fromString("rw-------"));

        store.relocate(SettingsStore.Target.DATA, elsewhere.toString(), true);

        assertThat(Files.getPosixFilePermissions(elsewhere.resolve("credentials")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(elsewhere.resolve("credentials/nested")))
                .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        assertThat(Files.getPosixFilePermissions(elsewhere.resolve("credentials/nested/session.json")))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    void relocationRefusesSymlinkedManagedCredentialFiles(@TempDir Path home, @TempDir Path elsewhere)
            throws IOException {
        var credentials = home.resolve("credentials");
        Files.createDirectories(credentials);
        Files.writeString(home.resolve("outside.json"), "outside");
        Files.createSymbolicLink(credentials.resolve("session.json"), home.resolve("outside.json"));

        assertThatThrownBy(() -> storeAt(home).relocate(SettingsStore.Target.DATA, elsewhere.toString(), true))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("must not contain symbolic links");
        assertThat(storeAt(home).dataRoot()).isEqualTo(home);
        assertThat(elsewhere.resolve("credentials/session.json")).doesNotExist();
    }

    @Test
    void copyingNeverOverwritesWhatTheDestinationAlreadyHas(@TempDir Path home, @TempDir Path elsewhere)
            throws IOException {
        var store = storeAt(home);
        Files.createDirectories(home.resolve("presets"));
        Files.writeString(home.resolve("presets/a.json"), "from-home");
        Files.writeString(home.resolve("presets/b.json"), "from-home");
        Files.createDirectories(elsewhere.resolve("presets"));
        Files.writeString(elsewhere.resolve("presets/a.json"), "already-there");

        var done = store.relocate(SettingsStore.Target.DATA, elsewhere.toString(), true);

        assertThat(done.filesCopied()).isEqualTo(1);
        assertThat(done.filesSkipped()).isEqualTo(1);
        assertThat(Files.readString(elsewhere.resolve("presets/a.json"))).isEqualTo("already-there");
        assertThat(Files.readString(elsewhere.resolve("presets/b.json"))).isEqualTo("from-home");
    }

    @Test
    void theWorkflowLibraryMovesAsAWholeTree(@TempDir Path home, @TempDir Path elsewhere) throws IOException {
        var store = storeAt(home);
        Files.createDirectories(home.resolve("workflows/favorites"));
        Files.writeString(home.resolve("workflows/flow.unbi.json"), "{}");
        Files.writeString(home.resolve("workflows/favorites/best.unbi.json"), "{}");

        var done = store.relocate(SettingsStore.Target.WORKFLOWS, elsewhere.toString(), true);

        assertThat(done.filesCopied()).isEqualTo(2);
        assertThat(elsewhere.resolve("favorites/best.unbi.json")).exists();
        assertThat(elsewhere.resolve("flow.unbi.json")).exists();
        assertThat(store.workflowsRoot()).isEqualTo(elsewhere);
    }

    @Test
    void refusesToCopyAFolderIntoItself(@TempDir Path home) throws IOException {
        var store = storeAt(home);
        Files.createDirectories(home.resolve("workflows"));
        Files.writeString(home.resolve("workflows/flow.unbi.json"), "{}");

        assertThatThrownBy(() -> store.relocate(
                        SettingsStore.Target.WORKFLOWS, home.resolve("workflows/nested").toString(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inside itself");
    }

    @Test
    void aBlankDirectoryGoesBackToTheDefault(@TempDir Path home, @TempDir Path elsewhere) throws IOException {
        var store = storeAt(home);
        store.relocate(SettingsStore.Target.WORKFLOWS, elsewhere.toString(), false);
        store.relocate(SettingsStore.Target.WORKFLOWS, "", false);

        assertThat(store.current().workflowsDirectory()).isEmpty();
        assertThat(store.workflowsRoot()).isEqualTo(home.resolve("workflows"));
    }

    @Test
    @DisplayName("a corrupt settings file is reported, not obeyed, and not overwritten until something is saved")
    void aCorruptFileFallsBackToDefaultsAndSaysWhy(@TempDir Path home) throws IOException {
        Files.writeString(home.resolve("settings.json"), "{ not json");

        var store = storeAt(home);

        assertThat(store.current()).isEqualTo(Settings.DEFAULTS);
        assertThat(store.loadError()).contains("settings.json");
        assertThat(Files.readString(home.resolve("settings.json"))).isEqualTo("{ not json");

        store.updatePreferences(Map.of("language", "en"));
        assertThat(store.loadError()).isEmpty();
        assertThat(storeAt(home).current().preferences()).containsEntry("language", "en");
    }

    @Test
    void aFileFromAnotherFormatIsRefused(@TempDir Path home) throws IOException {
        Files.writeString(home.resolve("settings.json"), "{\"format\":\"unbi-workflow\",\"version\":1}");

        assertThat(storeAt(home).loadError()).contains("not a UNBI settings file");
    }

    @Test
    void theHomeAcceptsTheOlderPropertyName(@TempDir Path somewhere) {
        assertThat(new EngineHome("", somewhere.toString()).root()).isEqualTo(somewhere);
        assertThat(new EngineHome(somewhere.resolve("new").toString(), somewhere.toString()).root())
                .isEqualTo(somewhere.resolve("new"));
        assertThat(new EngineHome("", "").root().getFileName().toString()).isEqualTo(".unbi-engine");
    }

    private static Preset preset(String name) {
        return new Preset(null, name, "", "llm.prompt", "", Map.of("text", "hello"), null);
    }
}
