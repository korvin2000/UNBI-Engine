package com.unbi.engine.core.type;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The universal type vocabulary — the handful of types that mean the same thing to every node pack.
 *
 * <p>Domain types live with the pack that owns them (see {@code nodes.files.FileTypes}). A pack
 * bringing its own types alongside its own nodes is what makes packs self-contained; putting
 * {@code FileRef} here instead would make {@code core} grow every time someone adds a feature.
 */
public final class Types {

    private Types() {}

    public static final PortType TEXT = PortType.primitive("Text");
    public static final PortType NUMBER = PortType.primitive("Number");
    public static final PortType BOOLEAN = PortType.primitive("Boolean");
    public static final PortType ANY = PortType.any();

    /** Builds an ordered field map. Field order drives generated UI, so it is preserved. */
    public static LinkedHashMap<String, PortType> fields(Object... keysAndTypes) {
        if (keysAndTypes.length % 2 != 0) {
            throw new IllegalArgumentException("Expected key/type pairs, got " + keysAndTypes.length + " arguments");
        }
        var map = new LinkedHashMap<String, PortType>();
        for (int i = 0; i < keysAndTypes.length; i += 2) {
            map.put((String) keysAndTypes[i], (PortType) keysAndTypes[i + 1]);
        }
        return map;
    }

    /** Shorthand used by packs declaring a struct type. */
    public static PortType struct(String name, Map<String, PortType> fields) {
        return PortType.struct(name, fields);
    }
}
