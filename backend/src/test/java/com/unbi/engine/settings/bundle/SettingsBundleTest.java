package com.unbi.engine.settings.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.core.node.NodeInput;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.auth.CredentialsSection;
import com.unbi.engine.llm.auth.PropertiesFileCredentials;
import com.unbi.engine.presets.Preset;
import com.unbi.engine.presets.PresetStore;
import com.unbi.engine.presets.PresetsSection;
import com.unbi.engine.profiles.Profile;
import com.unbi.engine.profiles.ProfileSchema;
import com.unbi.engine.profiles.ProfileStore;
import com.unbi.engine.profiles.ProfilesSection;
import com.unbi.engine.settings.EngineHome;
import com.unbi.engine.settings.PreferencesSection;
import com.unbi.engine.settings.SettingsStore;
import com.unbi.engine.workflows.StoredWorkflow;
import com.unbi.engine.workflows.WorkflowStore;
import com.unbi.engine.workflows.WorkflowsSection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * A bundle written by one engine and read by another, with nothing but the file in between.
 *
 * <p>Every test builds two engines by hand from temporary homes — the same beans Spring would wire,
 * without the container — so the round trip is the real stores talking to the real zip.
 */
class SettingsBundleTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
            return List.of(new NodeInput("url", "URL", Types.TEXT, false, false, Widget.TextField.of(""), "", null, false, null));
        }
    };

    /** One engine: its stores, and the bundle service over them. */
    private record Engine(
            Path home,
            SettingsStore settings,
            PresetStore presets,
            ProfileStore profiles,
            PropertiesFileCredentials credentials,
            WorkflowStore workflows,
            SettingsBundle bundle) {

        static Engine at(Path home) {
            var settings = new SettingsStore(new EngineHome(home));
            var data = new DataDirectory(settings);
            var presets = new PresetStore(data);
            var profiles = new ProfileStore(data, List.of(SERVERS));
            var credentials = new PropertiesFileCredentials(data);
            var workflows = new WorkflowStore(settings);
            var bundle = new SettingsBundle(List.of(
                    new WorkflowsSection(workflows),
                    new PreferencesSection(settings),
                    new ProfilesSection(data),
                    new CredentialsSection(credentials, new com.unbi.engine.llm.auth.ManagedCredentialStore(data),
                            new com.unbi.engine.llm.auth.CredentialStore(List.of(credentials))),
                    new PresetsSection(data)), "test");
            return new Engine(home, settings, presets, profiles, credentials, workflows, bundle);
        }

        /** A bit of everything, so an export has something in every section. */
        void seed() throws IOException {
            presets.save(new Preset(null, "Summarise", "Prompts", "llm.prompt", "", Map.of("text", "Summarise this"), null));
            profiles.save(new Profile("", "test.server", "Lab", "", Map.of("url", "http://lab/v1"), null));
            credentials.store("openrouter", "sk-secret");
            workflows.save("Nightly", workflow("nightly"), false);
            workflows.save("Best one", workflow("best"), false);
            workflows.setFavorite("Best one", true);
            settings.updatePreferences(Map.of("language", "en"));
        }

        Path write(byte[] bytes, String name) throws IOException {
            var file = home.resolve(name);
            Files.write(file, bytes);
            return file;
        }
    }

    private static tools.jackson.databind.JsonNode workflow(String title) {
        return MAPPER.readTree("""
                {"format":"unbi-workflow","version":1,"nodes":[{"id":"a","type":"util.preview","values":{"t":"%s"}}],"edges":[]}"""
                .formatted(title));
    }

    private static List<String> entryNames(Path zip) throws IOException {
        try (var file = new ZipFile(zip.toFile())) {
            return file.getFileHeaders().stream().map(FileHeader::getFileName).sorted().toList();
        }
    }

    @Test
    void sectionsComeInChecklistOrderWithTheirCounts(@TempDir Path home) throws IOException {
        var engine = Engine.at(home);
        engine.seed();

        assertThat(engine.bundle().sections()).extracting(SettingsSection::id)
                .containsExactly("profiles", "credentials", "presets", "workflows", "preferences");
        assertThat(engine.bundle().counts())
                .containsEntry("profiles", 1)
                .containsEntry("credentials", 1)
                .containsEntry("presets", 1)
                .containsEntry("workflows", 2)
                .containsEntry("preferences", 1);
        assertThat(engine.bundle().section("credentials").orElseThrow().sensitive()).isTrue();
    }

    @Test
    void exportsOnlyWhatWasChosen(@TempDir Path home) throws IOException {
        var engine = Engine.at(home);
        engine.seed();

        var zip = engine.write(engine.bundle().export(List.of("presets", "workflows"), ""), "chosen.ucfg");

        assertThat(entryNames(zip)).containsExactly(
                "manifest.json",
                "presets/llm-prompt-summarise.json",
                "workflows/Nightly.unbi.json",
                "workflows/favorites/Best one.unbi.json");
        var inspection = engine.bundle().inspect(zip);
        assertThat(inspection.manifest().encrypted()).isFalse();
        assertThat(inspection.manifest().engine()).isEqualTo("test");
        assertThat(inspection.sections()).extracting(SettingsBundle.Inspection.Entry::id).containsExactly("presets", "workflows");
        assertThat(inspection.sections()).extracting(SettingsBundle.Inspection.Entry::count).containsExactly(1, 2);
    }

    @Test
    @DisplayName("everything exported from one engine lands in another, favourites and secrets included")
    void roundTripsIntoAnotherEngine(@TempDir Path home, @TempDir Path other) throws IOException {
        var source = Engine.at(home);
        source.seed();
        var zip = source.write(source.bundle().export(
                List.of("profiles", "credentials", "presets", "workflows", "preferences"), ""), "all.ucfg");

        var target = Engine.at(other);
        var report = target.bundle().importFrom(zip, List.of(
                "profiles", "credentials", "presets", "workflows", "preferences"), "", ConflictPolicy.SKIP);

        assertThat(report.sections()).extracting(SettingsSection.SectionReport::imported).containsExactly(1, 1, 1, 2, 1);
        assertThat(report.sections()).allSatisfy(section -> assertThat(section.problems()).isEmpty());
        assertThat(target.presets().find("llm-prompt-summarise")).isPresent();
        assertThat(target.profiles().find("test.server", "lab").orElseThrow().values()).containsEntry("url", "http://lab/v1");
        assertThat(target.credentials().names()).containsExactly("openrouter");
        assertThat(target.credentials().find("openrouter").orElseThrow().token()).isEqualTo("sk-secret");
        assertThat(target.workflows().list()).extracting(StoredWorkflow::name).containsExactly("Best one", "Nightly");
        assertThat(target.workflows().find("Best one").orElseThrow().favorite()).isTrue();
        assertThat(target.settings().current().preferences()).containsEntry("language", "en");
    }

    @Test
    void aPasswordEncryptsEverythingButTheManifest(@TempDir Path home, @TempDir Path other) throws IOException {
        var source = Engine.at(home);
        source.seed();
        var zip = source.write(source.bundle().export(List.of("credentials", "presets"), "correct horse"), "locked.ucfg");

        try (var file = new ZipFile(zip.toFile())) {
            for (var header : file.getFileHeaders()) {
                assertThat(header.isEncrypted())
                        .as(header.getFileName())
                        .isEqualTo(!header.getFileName().equals("manifest.json"));
            }
        }
        // The manifest says so without a password, and says nothing about what the keys are.
        var inspection = source.bundle().inspect(zip);
        assertThat(inspection.manifest().encrypted()).isTrue();
        assertThat(Files.readString(zip, StandardCharsets.ISO_8859_1)).doesNotContain("sk-secret");

        var target = Engine.at(other);
        assertThatThrownBy(() -> target.bundle().importFrom(zip, List.of("presets"), "", ConflictPolicy.SKIP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password-protected");
        assertThatThrownBy(() -> target.bundle().importFrom(zip, List.of("presets"), "wrong", ConflictPolicy.SKIP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wrong password");
        assertThat(target.presets().query("", "", "")).isEmpty();

        var report = target.bundle().importFrom(zip, List.of("credentials", "presets"), "correct horse", ConflictPolicy.SKIP);
        assertThat(report.sections()).extracting(SettingsSection.SectionReport::imported).containsExactly(1, 1);
        assertThat(target.credentials().find("openrouter").orElseThrow().token()).isEqualTo("sk-secret");
    }

    @Test
    void theConflictPolicyDecidesWhatHappensToWhatIsAlreadyThere(@TempDir Path home, @TempDir Path other)
            throws IOException {
        var source = Engine.at(home);
        source.seed();
        var zip = source.write(source.bundle().export(List.of("presets", "credentials"), ""), "presets.ucfg");

        var target = Engine.at(other);
        target.presets().save(new Preset(null, "Summarise", "Mine", "llm.prompt", "", Map.of("text", "Keep me"), null));
        target.credentials().store("openrouter", "mine");

        var skipped = target.bundle().importFrom(zip, List.of("presets", "credentials"), "", ConflictPolicy.SKIP);
        assertThat(skipped.sections()).extracting(SettingsSection.SectionReport::skipped).containsExactly(1, 1);
        assertThat(target.presets().find("llm-prompt-summarise").orElseThrow().values()).containsEntry("text", "Keep me");
        assertThat(target.credentials().find("openrouter").orElseThrow().token()).isEqualTo("mine");

        var both = target.bundle().importFrom(zip, List.of("presets", "credentials"), "", ConflictPolicy.KEEP_BOTH);
        assertThat(both.sections()).extracting(SettingsSection.SectionReport::imported).containsExactly(1, 1);
        assertThat(target.presets().find("llm-prompt-summarise-2").orElseThrow().values()).containsEntry("text", "Summarise this");
        assertThat(target.credentials().names()).containsExactly("openrouter", "openrouter-2");

        var replaced = target.bundle().importFrom(zip, List.of("presets", "credentials"), "", ConflictPolicy.REPLACE);
        assertThat(replaced.sections()).extracting(SettingsSection.SectionReport::replaced).containsExactly(1, 1);
        assertThat(target.presets().find("llm-prompt-summarise").orElseThrow().values()).containsEntry("text", "Summarise this");
        assertThat(target.credentials().find("openrouter").orElseThrow().token()).isEqualTo("sk-secret");
    }

    @Test
    @DisplayName("an entry that tries to leave its folder is reported and written nowhere")
    void refusesEntriesThatEscapeTheirFolder(@TempDir Path home) throws IOException {
        var zip = home.resolve("evil.ucfg");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "manifest.json", new BundleManifest("x", null, false, Map.of("presets", 3)).toJson());
            put(out, "presets/../escaped.json", preset("escaped"));
            put(out, "presets/nested/deeper.json", preset("nested"));
            put(out, "presets/fine.json", preset("fine"));
            put(out, "presets/broken.json", "not json".getBytes(StandardCharsets.UTF_8));
        }

        var engine = Engine.at(home);
        var report = engine.bundle().importFrom(zip, List.of("presets"), "", ConflictPolicy.SKIP);

        var presets = report.sections().get(0);
        assertThat(presets.imported()).isEqualTo(1);
        assertThat(presets.problems()).hasSize(3);
        assertThat(engine.presets().find("fine")).isPresent();
        assertThat(home.resolve("escaped.json")).doesNotExist();
        assertThat(home.resolve("presets/nested")).doesNotExist();
        assertThat(home.resolve("presets/broken.json")).doesNotExist();
    }

    @Test
    void refusesWhatIsNotABundle(@TempDir Path home) throws IOException {
        var engine = Engine.at(home);
        var notAZip = engine.write("hello".getBytes(StandardCharsets.UTF_8), "notes.ucfg");
        var noManifest = home.resolve("plain.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(noManifest))) {
            put(out, "presets/fine.json", preset("fine"));
        }

        assertThatThrownBy(() -> engine.bundle().inspect(notAZip)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.bundle().inspect(noManifest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a UNBI settings bundle");
        assertThatThrownBy(() -> engine.bundle().export(List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one");
        assertThatThrownBy(() -> engine.bundle().export(List.of("attic"), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attic");
    }

    @Test
    void preferencesNeverCarryThisMachinesPaths(@TempDir Path home, @TempDir Path other) throws IOException {
        var source = Engine.at(home);
        source.settings().updatePreferences(Map.of("language", "en", "theme", "dark"));
        source.settings().relocate(SettingsStore.Target.WORKFLOWS, other.toString(), false);
        var zip = source.write(source.bundle().export(List.of("preferences"), ""), "prefs.ucfg");

        assertThat(entryNames(zip)).containsExactly("manifest.json", "preferences/preferences.json");
        assertThat(Files.readString(zip, StandardCharsets.ISO_8859_1)).doesNotContain(other.getFileName().toString());

        var target = Engine.at(other);
        target.settings().updatePreferences(Map.of("theme", "light"));
        var report = target.bundle().importFrom(zip, List.of("preferences"), "", ConflictPolicy.SKIP);
        assertThat(report.sections().get(0).imported()).isEqualTo(1);
        assertThat(report.sections().get(0).skipped()).isEqualTo(1);
        assertThat(target.settings().current().preferences()).containsEntry("theme", "light").containsEntry("language", "en");
        assertThat(target.settings().current().workflowsDirectory()).isEmpty();
    }

    private static byte[] preset(String name) {
        return ("{\"name\":\"" + name + "\",\"nodeType\":\"llm.prompt\",\"values\":{}}").getBytes(StandardCharsets.UTF_8);
    }

    private static void put(ZipOutputStream out, String path, byte[] content) throws IOException {
        var parameters = new ZipParameters();
        parameters.setFileNameInZip(path);
        out.putNextEntry(parameters);
        out.write(content);
        out.closeEntry();
    }
}
