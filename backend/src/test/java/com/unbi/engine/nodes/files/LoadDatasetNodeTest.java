package com.unbi.engine.nodes.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.support.RecordingContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadDatasetNodeTest {

    private static Path write(Path directory, String name, String content) throws IOException {
        var file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static RecordingContext load(Path file) {
        return RecordingContext.with("path", file.toString(), "format", "auto", "header", true);
    }

    @Test
    void aJsonArrayBecomesItems(@TempDir Path directory) throws Exception {
        var context = load(write(directory, "rows.json", "[{\"title\":\"A\"},{\"title\":\"B\"}]"));
        new LoadDatasetNode().execute(context);

        List<Object> items = context.output("items");
        assertThat(items).hasSize(2);
        assertThat(items.getFirst()).isEqualTo(Map.of("title", "A"));
        assertThat(context.rawOutput("count")).isEqualTo(2d);
    }

    @Test
    @DisplayName("an object wrapping the array is unwrapped, which is how most APIs export")
    void anEnvelopeIsUnwrapped(@TempDir Path directory) throws Exception {
        var context = load(write(directory, "export.json", "{\"total\":2,\"items\":[\"x\",\"y\"]}"));
        new LoadDatasetNode().execute(context);
        List<Object> items = context.output("items");
        assertThat(items).containsExactly("x", "y");
    }

    @Test
    void jsonLinesAreOnePerLine(@TempDir Path directory) throws Exception {
        var context = load(write(directory, "rows.jsonl", "{\"a\":1}\n\n{\"a\":2}\n"));
        new LoadDatasetNode().execute(context);
        List<Object> items = context.output("items");
        assertThat(items).hasSize(2);
    }

    @Test
    @DisplayName("CSV: quoted commas, doubled quotes, embedded newlines, a sniffed delimiter")
    void csvWithAHeaderBecomesRecords(@TempDir Path directory) throws Exception {
        var csv = "title;body\n\"One; only\";\"He said \"\"hi\"\"\nand left\"\nTwo;plain\n";
        var context = load(write(directory, "rows.csv", csv));
        new LoadDatasetNode().execute(context);

        List<Object> items = context.output("items");
        assertThat(items).hasSize(2);
        assertThat(items.getFirst()).isEqualTo(Map.of("title", "One; only", "body", "He said \"hi\"\nand left"));
    }

    @Test
    void csvWithoutAHeaderGivesRowsAsLists(@TempDir Path directory) throws Exception {
        var context = load(write(directory, "rows.csv", "a,b\nc,d\n")).and("header", false);
        new LoadDatasetNode().execute(context);
        List<Object> items = context.output("items");
        assertThat(items).containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void plainLinesAreItemsAndTheLimitApplies(@TempDir Path directory) throws Exception {
        var context = load(write(directory, "questions.txt", "one\n\ntwo\nthree\n")).and("limit", 2d);
        new LoadDatasetNode().execute(context);
        List<Object> items = context.output("items");
        assertThat(items).containsExactly("one", "two");
    }

    @Test
    void aMissingFileIsNamed(@TempDir Path directory) {
        assertThatThrownBy(() -> new LoadDatasetNode().execute(load(directory.resolve("nope.json"))))
                .hasMessageContaining("nope.json");
    }

    @Test
    void badJsonIsReportedAsSuch(@TempDir Path directory) throws Exception {
        var context = load(write(directory, "bad.json", "[1, 2"));
        assertThatThrownBy(() -> new LoadDatasetNode().execute(context)).hasMessageContaining("not valid JSON");
    }
}
