package com.unbi.engine.nodes.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.nodes.files.model.FileEdit;
import com.unbi.engine.nodes.files.model.FileRef;
import com.unbi.engine.nodes.files.model.TextMatch;
import com.unbi.engine.support.RecordingContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behaviour of the batch file-processing pack, against a real temporary directory. */
class FileNodesTest {

    @TempDir
    Path workspace;

    private List<FileRef> scanned;

    @BeforeEach
    void layOutFiles() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "hello world\nhello again\n");
        Files.writeString(workspace.resolve("readme.md"), "# Title\nhello there\n");
        Files.write(workspace.resolve("image.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 1, 2});

        var nested = Files.createDirectory(workspace.resolve("nested"));
        Files.writeString(nested.resolve("deep.txt"), "nothing to see\n");

        scanned = List.of(
                refFor(workspace.resolve("notes.txt")),
                refFor(workspace.resolve("readme.md")),
                refFor(workspace.resolve("image.png")),
                refFor(nested.resolve("deep.txt")));
    }

    private FileRef refFor(Path path) throws IOException {
        return FileRef.of(path, Files.size(path));
    }

    @Nested
    @DisplayName("Scan Directory")
    class Scanning {

        private final ScanDirectoryNode node = new ScanDirectoryNode();

        @Test
        void findsEveryFileBelowTheFolder() throws Exception {
            var context = baseScan().and("recursive", true);
            node.execute(context);

            assertThat(context.<List<FileRef>>output("files"))
                    .extracting(FileRef::name)
                    .containsExactlyInAnyOrder("notes.txt", "readme.md", "image.png", "deep.txt");
            assertThat(context.<Double>output("count")).isEqualTo(4d);
        }

        @Test
        void staysInTheTopFolderWhenNotRecursive() throws Exception {
            var context = baseScan().and("recursive", false);
            node.execute(context);

            assertThat(context.<List<FileRef>>output("files"))
                    .extracting(FileRef::name)
                    .doesNotContain("deep.txt")
                    .hasSize(3);
        }

        @Test
        void appliesTheNamePattern() throws Exception {
            var context = baseScan().and("recursive", true).and("pattern", "*.txt");
            node.execute(context);

            assertThat(context.<List<FileRef>>output("files"))
                    .extracting(FileRef::name)
                    .containsExactlyInAnyOrder("notes.txt", "deep.txt");
        }

        @Test
        void stopsAtTheFileLimit() throws Exception {
            var context = baseScan().and("recursive", true).and("limit", 2d);
            node.execute(context);

            assertThat(context.<List<FileRef>>output("files")).hasSize(2);
            assertThat(context.logs()).anyMatch(line -> line.contains("limit"));
        }

        @Test
        void refusesAFolderThatIsNotThere() {
            var context = RecordingContext.with(
                    "directory", workspace.resolve("absent").toString(),
                    "recursive", true, "pattern", "*", "maxDepth", 8d, "limit", 100d);

            assertThatThrownBy(() -> node.execute(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Not a folder");
        }

        @Test
        void stopsWhenTheRunIsCancelled() {
            var context = baseScan().and("recursive", true).cancelledFromTheStart();

            assertThatThrownBy(() -> node.execute(context))
                    .isInstanceOf(NodeContext.CancellationSignal.class);
        }

        private RecordingContext baseScan() {
            return RecordingContext.with(
                    "directory", workspace.toString(),
                    "pattern", "*",
                    "maxDepth", 8d,
                    "limit", 100d);
        }
    }

    @Nested
    @DisplayName("Filter Files")
    class Filtering {

        private final FilterFilesNode node = new FilterFilesNode();

        @Test
        void keepsOnlyTheNamedExtensions() {
            var context = RecordingContext.with(
                    "files", scanned, "extensions", "txt", "mode", "keep", "minSize", 0d);
            node.execute(context);

            assertThat(context.<List<FileRef>>output("files"))
                    .extracting(FileRef::name)
                    .containsExactlyInAnyOrder("notes.txt", "deep.txt");
        }

        @Test
        void removesTheNamedExtensions() {
            var context = RecordingContext.with(
                    "files", scanned, "extensions", ".txt, png", "mode", "drop", "minSize", 0d);
            node.execute(context);

            assertThat(context.<List<FileRef>>output("files"))
                    .extracting(FileRef::name)
                    .containsExactly("readme.md");
        }

        @Test
        @DisplayName("an empty extension list filters nothing rather than everything")
        void emptyExtensionListIsANoOp() {
            var context = RecordingContext.with(
                    "files", scanned, "extensions", "  ", "mode", "keep", "minSize", 0d);
            node.execute(context);

            assertThat(context.<List<FileRef>>output("files")).hasSize(scanned.size());
        }
    }

    @Nested
    @DisplayName("Search In Files")
    class Searching {

        private final SearchInFilesNode node = new SearchInFilesNode();

        @Test
        void reportsEveryOccurrenceWithItsLineAndColumn() {
            var context = RecordingContext.with(
                    "files", scanned, "query", "hello", "regex", false, "caseSensitive", false);
            node.execute(context);

            List<TextMatch> matches = context.output("matches");
            assertThat(matches).hasSize(3);
            assertThat(matches).extracting(TextMatch::line).containsExactly(1, 2, 2);
            assertThat(matches).extracting(TextMatch::column).containsOnly(1);
            assertThat(context.<Double>output("count")).isEqualTo(3d);
            assertThat(context.<List<FileRef>>output("files"))
                    .extracting(FileRef::name)
                    .containsExactly("notes.txt", "readme.md");
        }

        @Test
        void skipsBinaryFilesInsteadOfFailing() {
            var context = RecordingContext.with(
                    "files", scanned, "query", "PNG", "regex", false, "caseSensitive", true);
            node.execute(context);

            assertThat(context.<List<TextMatch>>output("matches")).isEmpty();
            assertThat(context.logs()).anyMatch(line -> line.contains("binary"));
        }

        @Test
        void honoursCaseSensitivity() {
            var sensitive = RecordingContext.with(
                    "files", scanned, "query", "HELLO", "regex", false, "caseSensitive", true);
            node.execute(sensitive);
            assertThat(sensitive.<List<TextMatch>>output("matches")).isEmpty();

            var insensitive = RecordingContext.with(
                    "files", scanned, "query", "HELLO", "regex", false, "caseSensitive", false);
            node.execute(insensitive);
            assertThat(insensitive.<List<TextMatch>>output("matches")).hasSize(3);
        }

        @Test
        void treatsALiteralQueryAsLiteralEvenWhenItLooksLikeARegex() {
            var context = RecordingContext.with(
                    "files", scanned, "query", "h.llo", "regex", false, "caseSensitive", false);
            node.execute(context);

            assertThat(context.<List<TextMatch>>output("matches")).isEmpty();
        }

        @Test
        void supportsRegularExpressions() {
            var context = RecordingContext.with(
                    "files", scanned, "query", "h.llo", "regex", true, "caseSensitive", false);
            node.execute(context);

            assertThat(context.<List<TextMatch>>output("matches")).hasSize(3);
        }

        @Test
        void explainsABrokenRegexInsteadOfLeakingTheCaretDiagram() {
            var context = RecordingContext.with(
                    "files", scanned, "query", "(unclosed", "regex", true, "caseSensitive", false);

            assertThatThrownBy(() -> node.execute(context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invalid regular expression")
                    .hasMessageNotContaining("^");
        }

        @Test
        void insistsOnSomethingToSearchFor() {
            var context = RecordingContext.with(
                    "files", scanned, "query", "", "regex", false, "caseSensitive", false);

            assertThatThrownBy(() -> node.execute(context)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Replace In Files")
    class Replacing {

        private final ReplaceInFilesNode node = new ReplaceInFilesNode();

        @Test
        @DisplayName("a dry run counts replacements and writes nothing")
        void dryRunLeavesFilesUntouched() throws Exception {
            var before = Files.readString(workspace.resolve("notes.txt"));
            var context = replaceContext("hello", "goodbye").and("dryRun", true);
            node.execute(context);

            assertThat(Files.readString(workspace.resolve("notes.txt"))).isEqualTo(before);
            assertThat(context.<Double>output("count")).isEqualTo(3d);
            assertThat(context.<List<FileEdit>>output("edits"))
                    .allMatch(edit -> !edit.applied());
            assertThat(context.logs()).anyMatch(line -> line.contains("dry run"));
        }

        @Test
        void writesWhenDryRunIsTurnedOff() throws Exception {
            var context = replaceContext("hello", "goodbye").and("dryRun", false);
            node.execute(context);

            assertThat(Files.readString(workspace.resolve("notes.txt")))
                    .isEqualTo("goodbye world\ngoodbye again\n");
            assertThat(Files.readString(workspace.resolve("readme.md")))
                    .isEqualTo("# Title\ngoodbye there\n");
            assertThat(context.<List<FileEdit>>output("edits")).allMatch(FileEdit::applied);
        }

        @Test
        @DisplayName("a literal replacement containing $ or \\ is written out as typed")
        void literalReplacementIsNotReinterpreted() throws Exception {
            var context = replaceContext("hello", "C:\\temp\\$1").and("dryRun", false);
            node.execute(context);

            assertThat(Files.readString(workspace.resolve("notes.txt")))
                    .contains("C:\\temp\\$1 world");
        }

        @Test
        void leavesBinaryFilesAlone() throws Exception {
            var before = Files.readAllBytes(workspace.resolve("image.png"));
            var context = replaceContext("PNG", "JPG").and("dryRun", false).and("caseSensitive", true);
            node.execute(context);

            assertThat(Files.readAllBytes(workspace.resolve("image.png"))).isEqualTo(before);
        }

        @Test
        void insistsOnSomethingToFind() {
            var context = replaceContext("", "x").and("dryRun", true);

            assertThatThrownBy(() -> node.execute(context)).isInstanceOf(IllegalStateException.class);
        }

        private RecordingContext replaceContext(String find, String replaceWith) {
            return RecordingContext.with(
                    "files", scanned,
                    "find", find,
                    "replaceWith", replaceWith,
                    "regex", false,
                    "caseSensitive", false);
        }
    }

    @Nested
    @DisplayName("Text file reading")
    class Reading {

        @Test
        void rejectsInvalidUtf8RatherThanProducingReplacementCharacters() throws IOException {
            var broken = workspace.resolve("broken.txt");
            Files.write(broken, new byte[] {(byte) 0xC3, (byte) 0x28, 'a', 'b'});

            assertThat(TextFiles.read(broken)).isEmpty();
        }

        @Test
        void readsOrdinaryUtf8IncludingNonAsciiCharacters() throws IOException {
            var file = workspace.resolve("accents.txt");
            Files.writeString(file, "café ☕", StandardCharsets.UTF_8);

            assertThat(TextFiles.read(file)).contains("café ☕");
        }
    }
}
