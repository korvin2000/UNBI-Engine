package com.unbi.engine.llm.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.nodes.files.model.FileRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PromptTemplateTest {

    @Nested
    class Rendering {

        @Test
        void substitutesEveryPlaceholder() {
            assertThat(PromptTemplate.render("Hello {{name}}, you are {{role}}.",
                            Map.of("name", "Ada", "role", "an engineer"), true))
                    .isEqualTo("Hello Ada, you are an engineer.");
        }

        @Test
        @DisplayName("whitespace inside the braces is allowed, because people write it")
        void toleratesWhitespaceInsideBraces() {
            assertThat(PromptTemplate.render("{{ name }}", Map.of("name", "Ada"), true)).isEqualTo("Ada");
        }

        @Test
        @DisplayName("a backslash escapes a placeholder, so a prompt can talk about templates")
        void escapedPlaceholderSurvivesLiterally() {
            assertThat(PromptTemplate.render("Write \\{{name}} to mean a variable", Map.of(), true))
                    .isEqualTo("Write {{name}} to mean a variable");
        }

        @Test
        void walksIntoMapsAndRecords() {
            var file = FileRef.of(java.nio.file.Path.of("docs", "guide.md"), 42);
            assertThat(PromptTemplate.render("{{doc.name}} is {{doc.size}} bytes", Map.of("doc", file), true))
                    .isEqualTo("guide.md is 42 bytes");
            assertThat(PromptTemplate.render("{{a.b.c}}",
                            Map.of("a", Map.of("b", Map.of("c", "deep"))), true))
                    .isEqualTo("deep");
        }

        @Test
        @DisplayName("a replacement containing $ or \\ is inserted literally, not read as a group reference")
        void replacementIsNotReinterpreted() {
            assertThat(PromptTemplate.render("{{cost}}", Map.of("cost", "$1.50 \\ 2"), true))
                    .isEqualTo("$1.50 \\ 2");
        }

        @Test
        @DisplayName("a list becomes one item per line, which is what wiring a file list in means")
        void listsRenderOnePerLine() {
            assertThat(PromptTemplate.render("{{files}}", Map.of("files", List.of("a.md", "b.md")), true))
                    .isEqualTo("a.md\nb.md");
        }

        @Test
        @DisplayName("a whole-number double loses its .0, because widget numbers are all doubles")
        void wholeNumbersRenderWithoutADecimalPoint() {
            assertThat(PromptTemplate.render("{{n}} files", Map.of("n", 5.0d), true)).isEqualTo("5 files");
            assertThat(PromptTemplate.render("{{n}}", Map.of("n", 2.5d), true)).isEqualTo("2.5");
        }

        @Test
        void emptyTemplateRendersEmpty() {
            assertThat(PromptTemplate.render("", Map.of(), true)).isEmpty();
        }
    }

    @Nested
    class MissingValues {

        @Test
        @DisplayName("strict rendering names both the missing variable and what was available")
        void strictFailsAndSaysWhat() {
            assertThatThrownBy(() -> PromptTemplate.render("{{a}} {{b}}", Map.of("a", 1), true))
                    .isInstanceOf(PromptTemplate.MissingVariableException.class)
                    .hasMessageContaining("b")
                    .hasMessageContaining("Available: a");
        }

        @Test
        void lenientRenderingLeavesAHole() {
            assertThat(PromptTemplate.render("[{{missing}}]", Map.of(), false)).isEqualTo("[]");
        }

        @Test
        @DisplayName("every missing name is reported at once, not one per run")
        void reportsAllMissingNames() {
            assertThatThrownBy(() -> PromptTemplate.render("{{a}}{{b}}{{c}}", Map.of(), true))
                    .isInstanceOfSatisfying(PromptTemplate.MissingVariableException.class,
                            failure -> assertThat(failure.missing()).containsExactly("a", "b", "c"));
        }

        @Test
        @DisplayName("a bound name whose value is null counts as missing, not as empty")
        void nullValueCountsAsMissing() {
            var bindings = new java.util.HashMap<String, Object>();
            bindings.put("a", null);
            assertThatThrownBy(() -> PromptTemplate.render("{{a}}", bindings, true))
                    .isInstanceOf(PromptTemplate.MissingVariableException.class);
        }
    }

    @Nested
    class Discovery {

        @Test
        void listsVariablesInFirstAppearanceOrder() {
            assertThat(PromptTemplate.variables("{{b}} {{a}} {{b}}")).containsExactly("b", "a");
        }

        @Test
        void ignoresEscapedPlaceholders() {
            assertThat(PromptTemplate.variables("\\{{a}} {{b}}")).containsExactly("b");
        }

        @Test
        void findsNothingInPlainText() {
            assertThat(PromptTemplate.variables("no variables here")).isEmpty();
            assertThat(PromptTemplate.variables(null)).isEmpty();
        }
    }
}
