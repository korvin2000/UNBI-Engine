package com.unbi.engine.nodes.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.core.type.PortType;
import com.unbi.engine.nodes.files.FileTypes;
import com.unbi.engine.nodes.files.model.FileEdit;
import com.unbi.engine.nodes.files.model.FileRef;
import com.unbi.engine.nodes.files.model.TextMatch;
import com.unbi.engine.nodes.llm.model.LlmEndpointInfo;
import com.unbi.engine.nodes.llm.model.LlmModelInfo;
import com.unbi.engine.nodes.llm.model.LlmResult;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Struct declarations and the records behind them must not drift.
 *
 * <p>A struct type is what the editor colours ports by and what the validator checks edges with; the
 * record is what actually travels. Nothing connects the two but a programmer's memory, and the
 * failure when they diverge is silent — a downstream node reads a field the struct promised and
 * finds nothing, three nodes and one gateway bill later.
 */
class LlmTypeShapeTest {

    @Test
    @DisplayName("LlmResult declares exactly the fields its record carries, in the same order")
    void resultStructMatchesItsRecord() {
        assertThat(fieldsOf(LlmTypes.RESULT)).containsExactly(componentsOf(LlmResult.class));
    }

    @Test
    @DisplayName("the info structs declare exactly the fields their records carry, in the same order")
    void infoStructsMatchTheirRecords() {
        assertThat(fieldsOf(LlmTypes.ENDPOINT_INFO)).containsExactly(componentsOf(LlmEndpointInfo.class));
        assertThat(fieldsOf(LlmTypes.MODEL_INFO)).containsExactly(componentsOf(LlmModelInfo.class));
    }

    @Test
    @DisplayName("every info field is a scalar, because a table cell is one line")
    void infoStructsHoldNoNestedShapes() {
        // The reason these records are flat. Preview and Generate Report reflect over components and
        // lay them out as columns; a nested record renders as "LlmKeyUsage[daily=0.42, …]" inside
        // one, which is a worse answer than the four columns it replaced.
        for (var struct : java.util.List.of(LlmTypes.ENDPOINT_INFO, LlmTypes.MODEL_INFO)) {
            assertThat(((PortType.Struct) struct).fields().values())
                    .describedAs(struct.describe())
                    .allSatisfy(field -> assertThat(field).isInstanceOf(PortType.Primitive.class));
        }
    }

    @Test
    @DisplayName("toString is the report rather than a record dump — the LlmResult precedent")
    void infoRecordsRenderAsReports() {
        var endpoint = new LlmEndpointInfo(
                "OpenRouter", "https://openrouter.ai/api/v1", true, "2026-09-16T13:42:07Z", 443,
                "dev key", 50, 37.66, 12.34, 0.42, false, 3, 200, 272, 311, 374, 360, 169,
                "text, image, file");

        // Every count on its own placeholder, and no placeholder left showing: a format string
        // split across concatenated literals applies only to the last of them, silently.
        assertThat(endpoint.toString())
                .startsWith("OpenRouter — https://openrouter.ai/api/v1")
                .contains("443 models served.")
                .contains("272 with vision")
                .contains("311 with reasoning")
                .contains("374 with tools")
                .contains("360 with structured output")
                .contains("169 taking files")
                .doesNotContain("%s")
                .doesNotContain("LlmEndpointInfo[");

        var model = new LlmModelInfo(
                "vendor/model", "Vendor: Model", "vendor/model-2026", 262_144, 65_536, 0.1625, 1.3,
                "$0.1625 in / $1.30 out per M", "tools, vision", "text, image", "text", 7,
                "max_tokens", "2026-02-25", "", false, "", "Vendor/Model", "Qwen3", "", "A model.");

        assertThat(model.toString())
                .startsWith("Vendor: Model — vendor/model")
                .contains("Context 262,144 tok")
                .contains("$0.1625 in / $1.30 out per M")
                .doesNotContain("%s")
                .doesNotContain("LlmModelInfo[");
    }

    @Test
    @DisplayName("an unquoted price leaves no price line, and a free one says free")
    void aPriceThatWasNotPublishedStaysOut() {
        // The two facts a Number field cannot tell apart, which is why the pair travels as text.
        assertThat(priced("").toString()).doesNotContain("Priced at");
        assertThat(priced("").summaryLine()).isEqualTo("vendor/model");
        assertThat(priced("free in / free out per M").toString())
                .contains("Priced at free in / free out per M.");
        assertThat(priced("free in / free out per M").summaryLine())
                .isEqualTo("vendor/model · free in / free out per M");
    }

    private static LlmModelInfo priced(String pricePerM) {
        return new LlmModelInfo(
                "vendor/model", "", "", 0, 0, 0, 0, pricePerM, "", "", "", 0, "", "", "", false,
                "", "", "", "", "");
    }

    @Test
    @DisplayName("the file pack's structs match their records too — the same rule, older code")
    void fileStructsMatchTheirRecords() {
        assertThat(fieldsOf(FileTypes.FILE_REF)).containsExactly(componentsOf(FileRef.class));
        assertThat(fieldsOf(FileTypes.TEXT_MATCH)).containsExactly(componentsOf(TextMatch.class));
        assertThat(fieldsOf(FileTypes.FILE_EDIT)).containsExactly(componentsOf(FileEdit.class));
    }

    @Test
    @DisplayName("handles stay opaque: a primitive has no fields for anyone to depend on")
    void handlesAreNotStructs() {
        assertThat(LlmTypes.ENDPOINT).isInstanceOf(PortType.Primitive.class);
        assertThat(LlmTypes.MODEL).isInstanceOf(PortType.Primitive.class);
        assertThat(LlmTypes.SAMPLING).isInstanceOf(PortType.Primitive.class);
        assertThat(LlmTypes.VARIABLES).isInstanceOf(PortType.Primitive.class);
        assertThat(LlmTypes.ATTACHMENT).isInstanceOf(PortType.Primitive.class);
    }

    @Test
    @DisplayName("a handle cannot be wired into a differently-named handle")
    void handlesAreNotInterchangeable() {
        var types = new com.unbi.engine.core.type.TypeSystem();
        assertThat(types.assignable(LlmTypes.MODEL, LlmTypes.ENDPOINT)).isFalse();
        assertThat(types.assignable(LlmTypes.ENDPOINT, LlmTypes.MODEL)).isFalse();
        assertThat(types.assignable(LlmTypes.MODEL, LlmTypes.MODEL)).isTrue();
    }

    private static String[] fieldsOf(PortType type) {
        if (!(type instanceof PortType.Struct struct)) {
            throw new AssertionError(type.describe() + " is not a struct");
        }
        return struct.fields().keySet().toArray(String[]::new);
    }

    private static String[] componentsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toArray(String[]::new);
    }
}
