package com.unbi.engine.core.node;

import com.unbi.engine.core.type.PortType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Everything the editor needs to draw a node and the validator needs to check one — and nothing
 * about how it runs.
 *
 * <p>This is deliberately data. It is serialised to the frontend at startup, which is what lets a
 * new node appear in the palette without the frontend being rebuilt or even knowing it exists.
 *
 * @param id          stable identity, {@code namespace.name}; persisted inside saved workflows
 * @param category    palette section, e.g. {@code Files}
 * @param subcategory divider label inside a section, e.g. {@code Input and Output}
 * @param accent      palette accent key; the frontend maps it to the category colour bar
 */
public record NodeDescriptor(
        String id,
        String label,
        String category,
        String subcategory,
        String icon,
        String accent,
        String description,
        List<NodeInput> inputs,
        List<NodeOutput> outputs) {

    public NodeDescriptor {
        if (id == null || !id.matches("[a-z0-9_]+\\.[a-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "Node id must look like namespace.name in lower snake case, got: " + id);
        }
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        requireUniqueKeys(inputs.stream().map(NodeInput::key).toList(), "input");
        requireUniqueKeys(outputs.stream().map(NodeOutput::key).toList(), "output");
    }

    public NodeInput input(String key) {
        return inputs.stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Node " + id + " has no input " + key));
    }

    public NodeOutput output(String key) {
        return outputs.stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Node " + id + " has no output " + key));
    }

    private static void requireUniqueKeys(List<String> keys, String what) {
        var seen = new HashSet<String>();
        for (var key : keys) {
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Duplicate " + what + " key: " + key);
            }
        }
    }

    public static Builder of(String id, String label) {
        return new Builder(id, label);
    }

    /**
     * Fluent construction, so that a node file reads as a declaration.
     *
     * <p>Every method here exists because it removed a line of noise from a real node. The goal is
     * that the descriptor block of any node stays scannable at a glance.
     */
    public static final class Builder {

        private final String id;
        private final String label;
        private final List<NodeInput> inputs = new ArrayList<>();
        private final List<NodeOutput> outputs = new ArrayList<>();
        private String category = "Utility";
        private String subcategory = "";
        private String icon = "node";
        private String accent = "slate";
        private String description = "";

        private Builder(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public Builder in(String category, String subcategory) {
            this.category = category;
            this.subcategory = subcategory;
            return this;
        }

        public Builder icon(String icon) {
            this.icon = icon;
            return this;
        }

        public Builder accent(String accent) {
            this.accent = accent;
            return this;
        }

        public Builder describedAs(String description) {
            this.description = description;
            return this;
        }

        /** A socket-only input: it must be wired. */
        public Builder socket(String key, String label, PortType type) {
            inputs.add(new NodeInput(key, label, type, true, true, null, null, null));
            return this;
        }

        /** An optional socket: leaving it unconnected is legal and yields null. */
        public Builder optionalSocket(String key, String label, PortType type) {
            inputs.add(new NodeInput(key, label, type, false, true, null, null, null));
            return this;
        }

        /** A value that can be typed into the node or overridden by an edge. */
        public Builder field(String key, String label, PortType type, Widget widget, Object defaultValue) {
            inputs.add(new NodeInput(key, label, type, false, true, widget, defaultValue, null));
            return this;
        }

        /** Pure configuration: rendered in the node, never wired. */
        public Builder setting(String key, String label, PortType type, Widget widget, Object defaultValue) {
            inputs.add(new NodeInput(key, label, type, false, false, widget, defaultValue, null));
            return this;
        }

        public Builder out(String key, String label, PortType type) {
            outputs.add(NodeOutput.of(key, label, type));
            return this;
        }

        public NodeDescriptor build() {
            return new NodeDescriptor(
                    id, label, category, subcategory, icon, accent, description, inputs, outputs);
        }
    }
}
