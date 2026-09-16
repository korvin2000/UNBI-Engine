package com.unbi.engine.transport.codec;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payload the frontend draws a node from.
 *
 * <p>Worth its own test for one reason: this file is the whole of what the browser knows about any
 * node, so a field encoded under the wrong name is a control that silently does not appear — and it
 * appears nowhere else as a compile error, because the consumer is in another language.
 */
class CatalogCodecTest {

    @Test
    @DisplayName("a display input encodes its style and unit, and stays unconnectable")
    void displayWidget() {
        var node = CatalogCodec.descriptor(NodeDescriptor.of("test.display", "Display")
                .display("fetchedAt", "Fetched", Widget.Display.line())
                .display("contextWindow", "Context Window", Widget.Display.line("tok"))
                .display("description", "Description", Widget.Display.block())
                .display("modalities", "Modalities", Types.NUMBER, Widget.Display.chips())
                .out("out", "Out", Types.TEXT)
                .build());

        var inputs = node.path("inputs");
        assertThat(inputs.get(0).path("widget").path("kind").asString("")).isEqualTo("display");
        assertThat(inputs.get(0).path("widget").path("style").asString("")).isEqualTo("line");
        assertThat(inputs.get(0).path("widget").path("unit").asString("x")).isEmpty();
        assertThat(inputs.get(1).path("widget").path("unit").asString("")).isEqualTo("tok");
        assertThat(inputs.get(2).path("widget").path("style").asString("")).isEqualTo("block");
        assertThat(inputs.get(3).path("widget").path("style").asString("")).isEqualTo("chips");

        // A fact is not a port and not a required field: the editor must never draw either.
        assertThat(inputs).allSatisfy(input -> {
            assertThat(input.path("connectable").asBoolean(true)).isFalse();
            assertThat(input.path("required").asBoolean(true)).isFalse();
            assertThat(input.path("default").asString("x")).isEmpty();
        });
    }

    @Test
    @DisplayName("every input carries a group, blank when it is in none")
    void groupsAreCarriedOnEveryInput() {
        var node = CatalogCodec.descriptor(NodeDescriptor.of("test.groups", "Groups")
                .setting("plain", "Plain", Types.TEXT, Widget.TextField.of(""), "")
                .section("Pricing")
                .advancedSetting("inputPrice", "Input Price", Types.NUMBER,
                        Widget.NumberField.of(0, 10), 0d)
                .advancedSetting("outputPrice", "Output Price", Types.NUMBER,
                        Widget.NumberField.of(0, 10), 0d)
                .section("Routing")
                .advancedSetting("order", "Order", Types.TEXT, Widget.TextField.of(""), "")
                .section("")
                .setting("ungrouped", "Ungrouped", Types.TEXT, Widget.TextField.of(""), "")
                .out("out", "Out", Types.TEXT)
                .build());

        assertThat(node.path("inputs"))
                .extracting(input -> input.path("key").asString("") + "=" + input.path("group").asString("?"))
                .containsExactly(
                        "plain=",
                        "inputPrice=Pricing",
                        "outputPrice=Pricing",
                        "order=Routing",
                        "ungrouped=");
    }

    @Test
    @DisplayName("the modifiers applied after a setting keep its group")
    void groupSurvivesTheTrailingModifiers() {
        var descriptor = NodeDescriptor.of("test.modifiers", "Modifiers")
                .section("Protocol")
                .setting("dialect", "Dialect", Types.TEXT, Widget.Dropdown.of("a", "A", "b", "B"), "a")
                .advanced()
                .setting("effort", "Effort", Types.TEXT, Widget.TextField.of(""), "")
                .onlyWhen("dialect", "b")
                .hint("Only while the dialect says something.")
                .out("out", "Out", Types.TEXT)
                .build();

        assertThat(descriptor.input("dialect").group()).isEqualTo("Protocol");
        assertThat(descriptor.input("dialect").advanced()).isTrue();
        assertThat(descriptor.input("effort").group()).isEqualTo("Protocol");
        assertThat(descriptor.input("effort").showWhen().values()).containsExactly("b");
        assertThat(descriptor.input("effort").hint()).startsWith("Only while");
    }

    @Test
    @DisplayName("a display is never a port: a fact with a socket would offer to overwrite itself")
    void aDisplayCannotBeConnectableOrRequired() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new com.unbi.engine.core.node.NodeInput(
                                "fact", "Fact", Types.TEXT, false, true, Widget.Display.line(), "", null))
                .hasMessageContaining("neither connectable nor required");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new com.unbi.engine.core.node.NodeInput(
                                "fact", "Fact", Types.TEXT, true, false, Widget.Display.line(), "", null))
                .hasMessageContaining("neither connectable nor required");
    }
}
