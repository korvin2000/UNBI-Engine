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
        List<NodeOutput> outputs,
        List<NodeAction> actions) {

    public NodeDescriptor {
        if (id == null || !id.matches("[a-z0-9_]+\\.[a-z0-9_]+")) {
            throw new IllegalArgumentException(
                    "Node id must look like namespace.name in lower snake case, got: " + id);
        }
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        actions = List.copyOf(actions == null ? List.of() : actions);
        requireUniqueKeys(inputs.stream().map(NodeInput::key).toList(), "input");
        requireUniqueKeys(outputs.stream().map(NodeOutput::key).toList(), "output");
        requireUniqueKeys(actions.stream().map(NodeAction::key).toList(), "action");
    }

    /** Backwards-compatible shape for the nodes and tests that declare no actions. */
    public NodeDescriptor(
            String id,
            String label,
            String category,
            String subcategory,
            String icon,
            String accent,
            String description,
            List<NodeInput> inputs,
            List<NodeOutput> outputs) {
        this(id, label, category, subcategory, icon, accent, description, inputs, outputs, List.of());
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
        private final List<NodeAction> actions = new ArrayList<>();
        private String category = "Utility";
        private String subcategory = "";
        private String icon = "node";
        private String accent = "slate";
        private String description = "";
        private String section = "";
        private boolean lastDeclaredWasOutput;

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
            return addInput(new NodeInput(key, label, type, true, true, null, null, null));
        }

        /** An optional socket: leaving it unconnected is legal and yields null. */
        public Builder optionalSocket(String key, String label, PortType type) {
            return addInput(new NodeInput(key, label, type, false, true, null, null, null));
        }

        /** A value that can be typed into the node or overridden by an edge. */
        public Builder field(String key, String label, PortType type, Widget widget, Object defaultValue) {
            return addInput(new NodeInput(key, label, type, false, true, widget, defaultValue, null));
        }

        /** Pure configuration: rendered in the node, never wired. */
        public Builder setting(String key, String label, PortType type, Widget widget, Object defaultValue) {
            return addInput(new NodeInput(key, label, type, false, false, widget, defaultValue, null));
        }

        /**
         * Configuration that is correct as it stands, folded away until someone wants it.
         *
         * <p>The same as {@link #setting} plus a claim: leaving this alone produces a working node.
         * Ranking the settings here is what lets a node with twenty of them stay four rows tall, and
         * it has to be declared by whoever wrote the node — the editor cannot tell which two of a
         * gateway's parameters the user actually came for.
         */
        public Builder advancedSetting(
                String key, String label, PortType type, Widget widget, Object defaultValue) {
            return addInput(new NodeInput(key, label, type, false, false, widget, defaultValue, null, true));
        }

        /**
         * A fact the engine found out: drawn in the node, never typed into, never wired.
         *
         * <p>A widget value rather than an output, which is the decision worth explaining. An output
         * exists only during a run and vanishes with it; a value persists in the saved workflow and
         * is on screen without anything being run — which is what "how much credit is left on this
         * endpoint" has to be to be worth reading. The default is blank because blank is a fact of
         * its own: "this gateway does not publish it".
         */
        public Builder display(String key, String label, Widget.Display widget) {
            return display(key, label, com.unbi.engine.core.type.Types.TEXT, widget);
        }

        /** The same, for a row whose value is not text — a boolean, or a list drawn as chips. */
        public Builder display(String key, String label, PortType type, Widget.Display widget) {
            return addInput(new NodeInput(key, label, type, false, false, widget, "", null));
        }

        /** A display row folded away with the rest of the detail. */
        public Builder advancedDisplay(String key, String label, Widget.Display widget) {
            return advancedDisplay(key, label, com.unbi.engine.core.type.Types.TEXT, widget);
        }

        public Builder advancedDisplay(String key, String label, PortType type, Widget.Display widget) {
            return addInput(new NodeInput(key, label, type, false, false, widget, "", null, true));
        }

        /** Moves the setting just declared into the advanced section. */
        public Builder advanced() {
            if (inputs.isEmpty() || lastDeclaredWasOutput) {
                throw new IllegalStateException("advanced() must follow the setting it applies to");
            }
            inputs.add(inputs.removeLast().asAdvanced());
            return this;
        }

        /** Shows the setting just declared only while a sibling setting holds one of these values. */
        public Builder onlyWhen(String siblingKey, String... values) {
            if (inputs.isEmpty() || lastDeclaredWasOutput) {
                throw new IllegalStateException("onlyWhen() must follow the setting it applies to");
            }
            inputs.add(inputs.removeLast().shownWhen(NodeInput.ShowWhen.is(siblingKey, values)));
            return this;
        }

        /**
         * Puts every input declared after this call under one sub-heading, until the next call.
         *
         * <p>A positional mode rather than an argument on every factory, for the same reason
         * {@link #hint} is: a node with four groups of settings would otherwise carry the group name
         * on twenty lines, and the reader would have to diff those strings to see where one group
         * ends. Declared in order, the grouping reads as the outline it is.
         *
         * <p>{@code section("")} ends grouping, so the ungrouped settings of a node can follow its
         * grouped ones.
         */
        public Builder section(String name) {
            this.section = name == null ? "" : name.trim();
            return this;
        }

        /** A button the editor offers on this node before anything is run. */
        public Builder action(NodeAction action) {
            actions.add(action);
            return this;
        }

        private Builder addInput(NodeInput input) {
            inputs.add(section.isEmpty() ? input : input.withGroup(section));
            lastDeclaredWasOutput = false;
            return this;
        }

        public Builder out(String key, String label, PortType type) {
            outputs.add(NodeOutput.of(key, label, type));
            lastDeclaredWasOutput = true;
            return this;
        }

        /**
         * One line of explanation for the input or output just declared.
         *
         * <p>Attached to the previous entry rather than passed to every factory: hints are the
         * exception, and threading an extra argument through eight overloads to carry a null would
         * cost every node file a column of noise for the few that need it.
         */
        public Builder hint(String hint) {
            if (!outputs.isEmpty() && lastDeclaredWasOutput) {
                var last = outputs.removeLast();
                outputs.add(new NodeOutput(last.key(), last.label(), last.type(), hint));
                return this;
            }
            if (inputs.isEmpty()) {
                throw new IllegalStateException("hint() must follow the input or output it describes");
            }
            inputs.add(inputs.removeLast().withHint(hint));
            return this;
        }

        public NodeDescriptor build() {
            return new NodeDescriptor(
                    id, label, category, subcategory, icon, accent, description, inputs, outputs, actions);
        }
    }
}
