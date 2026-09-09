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
 */
public record NodeInput(
        String key,
        String label,
        PortType type,
        boolean required,
        boolean connectable,
        Widget widget,
        Object defaultValue,
        String hint) {

    public NodeInput {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Input key must not be blank");
        }
        if (!connectable && widget == null) {
            throw new IllegalArgumentException(
                    "Input '" + key + "' can neither be connected nor edited, so it can never receive a value");
        }
    }

    public boolean hasWidget() {
        return widget != null;
    }
}
