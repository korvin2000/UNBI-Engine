package com.unbi.engine.llm.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.llm.spec.LlmFailure;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialStoreTest {

    @Nested
    class Environment {

        @Test
        void readsThePrefixedVariable() {
            var source = new EnvironmentCredentials(Map.of("UNBI_LLM_KEY_OPENROUTER", "sk-abc"));
            assertThat(source.find("openrouter")).get()
                    .extracting(Credential::token).isEqualTo("sk-abc");
        }

        @Test
        @DisplayName("dashes and dots in a name become underscores in the variable")
        void normalisesTheName() {
            var source = new EnvironmentCredentials(Map.of("UNBI_LLM_KEY_MY_KEY", "v"));
            assertThat(source.find("my-key")).isPresent();
            assertThat(source.find("my.key")).isPresent();
        }

        @Test
        @DisplayName("a conventional variable works too, so an existing shell needs no new setup")
        void acceptsWellKnownVariables() {
            var source = new EnvironmentCredentials(Map.of("OPENAI_API_KEY", "sk-openai"));
            assertThat(source.find("openai")).get().extracting(Credential::token).isEqualTo("sk-openai");
            assertThat(source.names()).containsExactly("openai");
        }

        @Test
        void anExplicitVariableWinsOverTheConvention() {
            var source = new EnvironmentCredentials(
                    Map.of("UNBI_LLM_KEY_OPENAI", "explicit", "OPENAI_API_KEY", "conventional"));
            assertThat(source.find("openai")).get().extracting(Credential::token).isEqualTo("explicit");
        }

        @Test
        void aBlankValueIsNotACredential() {
            var source = new EnvironmentCredentials(Map.of("UNBI_LLM_KEY_EMPTY", "  "));
            assertThat(source.find("empty")).isEmpty();
            assertThat(source.names()).isEmpty();
        }

        @Test
        void listsWhatItCanAnswerFor() {
            var source = new EnvironmentCredentials(
                    Map.of("UNBI_LLM_KEY_ONE", "a", "UNBI_LLM_KEY_TWO", "b", "UNRELATED", "c"));
            assertThat(source.names()).containsExactlyInAnyOrder("one", "two");
        }
    }

    @Nested
    class PropertiesFile {

        @Test
        void readsAKeyByName(@TempDir Path directory) throws IOException {
            var file = directory.resolve("credentials.properties");
            Files.writeString(file, "openrouter=sk-file\nspare=\n");
            var source = new PropertiesFileCredentials(file);

            assertThat(source.find("openrouter")).get().extracting(Credential::token).isEqualTo("sk-file");
            assertThat(source.names()).containsExactly("openrouter");
        }

        @Test
        @DisplayName("a missing file is simply a source with nothing in it")
        void aMissingFileIsNotAnError(@TempDir Path directory) {
            var source = new PropertiesFileCredentials(directory.resolve("absent.properties"));
            assertThat(source.find("anything")).isEmpty();
            assertThat(source.names()).isEmpty();
        }

        @Test
        @DisplayName("a key added while the engine runs is visible without a restart")
        void isReadFreshEachTime(@TempDir Path directory) throws IOException {
            var file = directory.resolve("credentials.properties");
            Files.writeString(file, "a=1\n");
            var source = new PropertiesFileCredentials(file);
            assertThat(source.find("b")).isEmpty();

            Files.writeString(file, "a=1\nb=2\n");
            assertThat(source.find("b")).isPresent();
        }
    }

    @Nested
    class Codex {

        private static final String AUTH_JSON = """
                {"tokens":{"access_token":"tok-123","account_id":"acct-9","refresh_token":"r"}}""";

        @Test
        void readsTheTokenAndTheAccountHeader() {
            var source = new CodexCredentials(AUTH_JSON, null);
            var credential = source.find("codex").orElseThrow();

            assertThat(credential.token()).isEqualTo("tok-123");
            assertThat(credential.headers())
                    .containsEntry(CodexCredentials.ACCOUNT_HEADER, "acct-9")
                    .containsEntry("originator", "codex_cli_rs")
                    .containsKey("OpenAI-Beta");
        }

        @Test
        void readsTheFileWhenNoInlineJsonIsGiven(@TempDir Path directory) throws IOException {
            var file = directory.resolve("auth.json");
            Files.writeString(file, AUTH_JSON);
            assertThat(new CodexCredentials(null, file).find("codex")).isPresent();
        }

        @Test
        void answersOnlyToItsOwnName() {
            assertThat(new CodexCredentials(AUTH_JSON, null).find("openai")).isEmpty();
        }

        @Test
        @DisplayName("a file without an access token is not a credential, however well-formed")
        void needsAnAccessToken() {
            assertThat(new CodexCredentials("{\"tokens\":{}}", null).find("codex")).isEmpty();
            assertThat(new CodexCredentials("not json", null).find("codex")).isEmpty();
            assertThat(new CodexCredentials(null, null).names()).isEmpty();
        }
    }

    @Nested
    class Store {

        @Test
        @DisplayName("sources are consulted in order, so a specific one shadows a general one")
        void resolutionFollowsSourceOrder() {
            var store = new CredentialStore(List.of(
                    new EnvironmentCredentials(Map.of("UNBI_LLM_KEY_CODEX", "from-env")),
                    new CodexCredentials("{\"tokens\":{\"access_token\":\"from-codex\"}}", null)));

            assertThat(store.require("codex").token()).isEqualTo("from-codex");
        }

        @Test
        void fallsThroughToTheNextSource() {
            var store = new CredentialStore(List.of(
                    new CodexCredentials(null, null),
                    new EnvironmentCredentials(Map.of("UNBI_LLM_KEY_OTHER", "v"))));

            assertThat(store.require("other").token()).isEqualTo("v");
        }

        @Test
        @DisplayName("a missing reference names itself and every place that was searched")
        void theMissingCredentialMessageIsActionable(@TempDir Path directory) {
            var store = new CredentialStore(List.of(
                    new EnvironmentCredentials(Map.of()),
                    new PropertiesFileCredentials(directory.resolve("credentials.properties"))));

            assertThatThrownBy(() -> store.require("nowhere"))
                    .isInstanceOfSatisfying(LlmFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.AUTH))
                    .hasMessageContaining("nowhere")
                    .hasMessageContaining("UNBI_LLM_KEY_")
                    .hasMessageContaining("credentials.properties");
        }

        @Test
        void theCatalogSaysWhichSourceAnswersForEachName() {
            var store = new CredentialStore(List.of(
                    new EnvironmentCredentials(Map.of("UNBI_LLM_KEY_ONE", "a")),
                    new CodexCredentials("{\"tokens\":{\"access_token\":\"t\"}}", null)));

            assertThat(store.catalog())
                    .containsEntry("one", "environment")
                    .containsEntry("codex", "codex");
        }

        @Test
        @DisplayName("a blank reference resolves to nothing rather than to the first key it finds")
        void aBlankReferenceResolvesToNothing() {
            var store = new CredentialStore(List.of(
                    new EnvironmentCredentials(Map.of("UNBI_LLM_KEY_ONE", "a"))));
            assertThat(store.find("")).isEmpty();
            assertThat(store.find(null)).isEmpty();
        }
    }

    @Nested
    class Redaction {

        @Test
        @DisplayName("a credential printed anywhere shows its length, never its value")
        void toStringNeverLeaksTheToken() {
            var credential = new Credential("openrouter", "sk-supersecret", Map.of("X", "y"));
            assertThat(credential.toString())
                    .doesNotContain("sk-supersecret")
                    .contains("redacted")
                    .contains("openrouter");
        }

        @Test
        @DisplayName("header values are not printed either — an account id is not public")
        void headerValuesAreNotPrinted() {
            assertThat(new Credential("codex", "t", Map.of("chatgpt-account-id", "acct-9")).toString())
                    .doesNotContain("acct-9")
                    .contains("chatgpt-account-id");
        }
    }
}
