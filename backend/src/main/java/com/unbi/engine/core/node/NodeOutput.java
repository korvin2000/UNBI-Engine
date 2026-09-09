package com.unbi.engine.core.node;

import com.unbi.engine.core.type.PortType;

/** One output socket. Outputs are always connectable and never carry a widget. */
public record NodeOutput(String key, String label, PortType type, String hint) {

    public NodeOutput {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Output key must not be blank");
        }
    }

    public static NodeOutput of(String key, String label, PortType type) {
        return new NodeOutput(key, label, type, null);
    }
}
