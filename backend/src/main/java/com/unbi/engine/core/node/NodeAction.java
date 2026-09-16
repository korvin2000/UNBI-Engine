package com.unbi.engine.core.node;

/**
 * Something a node can be asked to do <em>before</em> a run, from the editor.
 *
 * <p>Configuring a gateway is otherwise a guess you only get graded on at the end of a run: a typo
 * in a base URL, a credential the engine cannot see, a model name that endpoint has never heard of
 * — all look identical until something fails halfway through a batch. An action is the answer to
 * "is this right?" asked at the moment the user is still looking at the field.
 *
 * <p>Declared in the descriptor, so the editor renders it without knowing what any node does, and
 * answered by {@link NodeProbe}. Two kinds, because two is what an indicator can show honestly: a
 * {@code CHECK} lights up, and a {@code DISCOVER} also fills fields and option lists in.
 *
 * <p>An action is either a button in the node's header or, when {@code automatic}, no button at
 * all: the editor runs it by itself the first time the field it applies to is opened, and again
 * when what is wired upstream changes. That is how a model list arrives in a dropdown without a
 * magnifier to press — asking is the editor's job, and it asks once per configuration.
 *
 * @param key       what {@link NodeProbe#probe} is called with
 * @param label     the button's tooltip; it has no room for anything longer
 * @param icon      an icon name the frontend already has
 * @param appliesTo the input key this action feeds — where discovered options land — or blank
 * @param automatic run by the editor on its own for {@code appliesTo}, rather than from a button
 */
public record NodeAction(String key, String label, String icon, String appliesTo, Kind kind, boolean automatic) {

    public NodeAction {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("An action needs a key");
        }
        label = label == null ? key : label;
        icon = icon == null || icon.isBlank() ? "bolt" : icon;
        appliesTo = appliesTo == null ? "" : appliesTo;
        kind = kind == null ? Kind.CHECK : kind;
        if (automatic && appliesTo.isBlank()) {
            throw new IllegalArgumentException(
                    "Action '" + key + "' is automatic, so it needs the input whose opening triggers it");
        }
    }

    public NodeAction(String key, String label, String icon, String appliesTo, Kind kind) {
        this(key, label, icon, appliesTo, kind, false);
    }

    /** A node-level check: a button in the node header that lights an indicator. */
    public static NodeAction check(String key, String label, String icon) {
        return new NodeAction(key, label, icon, "", Kind.CHECK, false);
    }

    /** A node-level check that also fills fields in from what it found. */
    public static NodeAction discover(String key, String label, String icon) {
        return new NodeAction(key, label, icon, "", Kind.DISCOVER, false);
    }

    /**
     * Options for one field, fetched by the editor on its own when that field is opened.
     *
     * <p>No button: a list the user has to ask for is a list they forget to ask for, and a stale
     * one is worse than none. The editor caches the answer per node instance and per upstream
     * configuration, so opening the same list twice asks once.
     */
    public static NodeAction automatic(String key, String label, String appliesTo) {
        return new NodeAction(key, label, "search", appliesTo, Kind.DISCOVER, true);
    }

    public enum Kind {
        /** Answers "does this work?" and nothing else. */
        CHECK,
        /**
         * Answers, and hands back values and option lists to apply.
         *
         * <p>Applied through the ordinary edit command, so a discovery that guessed wrong is one
         * Ctrl+Z away — which is the only reason filling a user's fields in for them is acceptable.
         */
        DISCOVER
    }
}
