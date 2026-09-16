package com.unbi.engine.nodes.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.core.type.PortType;
import com.unbi.engine.nodes.files.FileTypes;
import com.unbi.engine.nodes.files.model.FileEdit;
import com.unbi.engine.nodes.files.model.FileRef;
import com.unbi.engine.nodes.files.model.TextMatch;
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
