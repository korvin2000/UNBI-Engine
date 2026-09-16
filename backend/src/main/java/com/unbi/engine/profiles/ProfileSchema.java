package com.unbi.engine.profiles;

import com.unbi.engine.core.node.NodeInput;
import com.unbi.engine.core.node.NodeProbe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shape of one kind of saved, named configuration — an endpoint, say — that nodes refer to by
 * id rather than carry inside themselves.
 *
 * <p>A profile is what a preset is not. A preset is <em>copied</em> into a node when it is dropped
 * on the canvas, and from then on the node owns the values; a profile is <em>referenced</em>, and
 * resolved on the engine at the moment it is used. The difference is the point: a workflow that says
 * "the endpoint called openrouter" runs unchanged on a machine where that name means a different URL
 * and a different key, which is exactly what an environment-specific setting has to allow.
 *
 * <p>Discovered by injection, like nodes and credential sources: a bean implementing this is a
 * schema the editor can list, edit and save, and the editor renders its fields with the very same
 * widgets a node uses. Nothing in the browser is written about any particular schema.
 */
public interface ProfileSchema {

    /** Stable identity, {@code namespace.name}; what a {@code Widget.Profile} names. */
    String id();

    /** What the editor calls one of these — "LLM Endpoint". */
    String label();

    /**
     * The fields a profile holds, in the order the editor should draw them.
     *
     * <p>Plain {@link NodeInput}s so the editor needs no second form vocabulary. An input marked
     * {@code advanced} is drawn below a divider rather than folded away — a dialog has the room.
     * Connectable inputs make no sense here and are refused by {@link ProfileStore} at startup.
     */
    List<NodeInput> fields();

    /**
     * Whether this profile can be tried out before it is saved.
     *
     * <p>Optional: a schema with nothing to test returns empty, and the editor shows no button. One
     * that can — an endpoint can be asked whether it answers — gets a Test button in the dialog that
     * runs against the <em>draft</em>, which is the only moment a wrong URL is cheap to find.
     */
    default java.util.Optional<NodeProbe.Result> test(Map<String, Object> values) {
        return java.util.Optional.empty();
    }

    /** Whether {@link #test} does anything, so the editor can offer the button without asking. */
    default boolean testable() {
        return false;
    }

    /**
     * The values, with every declared field present: the stored value where there is one, else the
     * field's default.
     *
     * <p>A profile file written by hand, or saved by an older engine, may lack a field added since.
     * Filling in here, once, means every reader sees a complete configuration.
     */
    default Map<String, Object> withDefaults(Map<String, Object> values) {
        var resolved = new LinkedHashMap<String, Object>();
        for (var field : fields()) {
            var stored = values == null ? null : values.get(field.key());
            var value = stored == null ? field.defaultValue() : stored;
            if (value != null) {
                resolved.put(field.key(), value);
            }
        }
        return resolved;
    }
}
