package com.unbi.engine.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.type.Types;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Discovery, ordering and duplicate detection. */
class NodeRegistryTest {

    private record StubNode(String id, String category, String subcategory) implements NodeDefinition {

        @Override
        public NodeDescriptor descriptor() {
            return NodeDescriptor.of(id, id)
                    .in(category, subcategory)
                    .out("out", "Out", Types.TEXT)
                    .build();
        }

        @Override
        public void execute(NodeContext context) {
            context.output("out", "value");
        }
    }

    @Test
    @DisplayName("the catalog is ordered by category, then section, then id")
    void catalogOrderIsDeterministicAndMeaningful() {
        // Deliberately shuffled relative to the expected output, and deliberately containing ids
        // whose hash order differs from their declared order.
        var registry = new NodeRegistry(List.of(
                new StubNode("zeta.one", "Text", "Processing"),
                new StubNode("alpha.two", "Files", "Processing"),
                new StubNode("beta.three", "Files", "Input"),
                new StubNode("alpha.one", "Files", "Input")));

        assertThat(registry.all())
                .extracting(NodeDescriptor::id)
                .containsExactly("alpha.one", "beta.three", "alpha.two", "zeta.one");
    }

    @Test
    @DisplayName("order survives the immutable copy the registry keeps")
    void orderIsStableAcrossRepeatedReads() {
        var registry = new NodeRegistry(List.of(
                new StubNode("b.node", "Files", "Input"),
                new StubNode("a.node", "Files", "Input"),
                new StubNode("c.node", "Files", "Input")));

        var first = registry.all().stream().map(NodeDescriptor::id).toList();
        var second = registry.all().stream().map(NodeDescriptor::id).toList();

        assertThat(first).containsExactly("a.node", "b.node", "c.node").isEqualTo(second);
    }

    @Test
    void resolvesADefinitionById() {
        var registry = new NodeRegistry(List.of(new StubNode("a.node", "Files", "Input")));

        assertThat(registry.find("a.node")).isPresent();
        assertThat(registry.find("missing.node")).isEmpty();
        assertThatThrownBy(() -> registry.require("missing.node"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing.node");
    }

    @Test
    @DisplayName("two nodes claiming one id fail at startup rather than shadowing each other")
    void duplicateIdsAreRefused() {
        assertThatThrownBy(() -> new NodeRegistry(List.of(
                        new StubNode("same.id", "Files", "Input"),
                        new StubNode("same.id", "Text", "Processing"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate node id");
    }
}
