package com.unbi.engine.core.node;

import com.unbi.engine.core.type.PortType;

/**
 * One input on a node: a socket, a widget, or both.
 *
 * <p>Both is the common case and the reason this is not simply a port. A tile size is a dropdown
 * until someone wires a value into it; a "recursive" flag is a checkbox that never accepts an edge
 * ({@code connectable = false}). Modelling that here keeps the distinction out of the UI code.
 *
 * @param connectable whether an edge may terminate here at all
 * @param widget      how to render it when unconnected; {@code null} means socket-only
 * @param defaultValue value used when nothing is connected and the user has not typed anything
 * @param advanced    fine tuning: correct by default, and folded away until someone wants it. A node
 *                    with twenty settings on screen is unreadable and, worse, says nothing about
 *                    which two of them matter — so the ranking is declared here, by the author who
 *                    knows, rather than guessed by the editor or left to the user to discover.
 * @param showWhen    hides this setting while it cannot apply; null to always show it. A JSON schema
 *                    box above a response format of "Text" is not merely wasted height, it is a
 *                    question with no right answer — and every one of those a node asks is a reason
 *                    to distrust the ones that matter.
 * @param group       a sub-heading this input belongs under, blank for none. Presentation only: the
 *                    inspector panel draws the groups as sub-sections and the node body ignores
 *                    them, because twenty settings in one list is a list nobody reads even when
 *                    every one of them is folded away. Declared here rather than derived from key
 *                    prefixes, so renaming a setting cannot silently move it.
 */
public record NodeInput(
        String key,
        String label,
        PortType type,
        boolean required,
        boolean connectable,
        Widget widget,
        Object defaultValue,
        String hint,
        boolean advanced,
        ShowWhen showWhen,
        String group) {

    public NodeInput {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Input key must not be blank");
        }
        group = group == null ? "" : group.trim();
        if (!connectable && widget == null) {
            throw new IllegalArgumentException(
                    "Input '" + key + "' can neither be connected nor edited, so it can never receive a value");
        }
        if (widget instanceof Widget.Display && (connectable || required)) {
            // A display is something the engine found out, not something anyone can supply. A port
            // into it would offer to overwrite a fact, and "required" would make a node invalid
            // until a gateway had been asked — which is a probe, not a graph.
            throw new IllegalArgumentException(
                    "Input '" + key + "' displays a discovered fact, so it can be neither connectable nor required");
        }
        if (showWhen != null && connectable) {
            // Same reason as below: a port that is not on screen loses its geometry.
            throw new IllegalArgumentException(
                    "Input '" + key + "' is connectable, so it cannot be conditional");
        }
        if (advanced && connectable) {
            // A connectable input carries a port, and a port that is folded away loses its geometry
            // — the flow library then drags its edges to the corner of the node (Foblex FF1006).
            // Restricting "advanced" to settings sidesteps that entirely rather than working around it.
            throw new IllegalArgumentException(
                    "Input '" + key + "' is connectable, so it cannot be advanced: its port must stay on screen");
        }
    }

    public NodeInput(
            String key,
            String label,
            PortType type,
            boolean required,
            boolean connectable,
            Widget widget,
            Object defaultValue,
            String hint) {
        this(key, label, type, required, connectable, widget, defaultValue, hint, false, null, "");
    }

    public NodeInput(
            String key,
            String label,
            PortType type,
            boolean required,
            boolean connectable,
            Widget widget,
            Object defaultValue,
            String hint,
            boolean advanced) {
        this(key, label, type, required, connectable, widget, defaultValue, hint, advanced, null, "");
    }

    public NodeInput(
            String key,
            String label,
            PortType type,
            boolean required,
            boolean connectable,
            Widget widget,
            Object defaultValue,
            String hint,
            boolean advanced,
            ShowWhen showWhen) {
        this(key, label, type, required, connectable, widget, defaultValue, hint, advanced, showWhen, "");
    }

    public boolean hasWidget() {
        return widget != null;
    }

    public NodeInput withHint(String replacement) {
        return new NodeInput(
                key, label, type, required, connectable, widget, defaultValue, replacement, advanced,
                showWhen, group);
    }

    public NodeInput asAdvanced() {
        return new NodeInput(
                key, label, type, required, connectable, widget, defaultValue, hint, true, showWhen, group);
    }

    public NodeInput shownWhen(ShowWhen condition) {
        return new NodeInput(
                key, label, type, required, connectable, widget, defaultValue, hint, advanced, condition, group);
    }

    /** The same input under a sub-heading. Blank removes it. */
    public NodeInput withGroup(String replacement) {
        return new NodeInput(
                key, label, type, required, connectable, widget, defaultValue, hint, advanced, showWhen,
                replacement);
    }

    /**
     * "Only while the sibling setting {@code key} holds one of {@code values}."
     *
     * <p>A sibling, never an upstream node: a rule the editor can evaluate from the node in front of
     * it is a rule that cannot go stale, and one setting deciding whether another applies is the
     * only relationship a node body actually needs to express.
     */
    public record ShowWhen(String key, java.util.List<String> values) {

        public ShowWhen {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("A condition needs the setting it reads");
            }
            values = java.util.List.copyOf(values == null ? java.util.List.of() : values);
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                        "A condition with no values can never be true, so the input could never be used");
            }
        }

        public static ShowWhen is(String key, String... values) {
            return new ShowWhen(key, java.util.List.of(values));
        }
    }
}
