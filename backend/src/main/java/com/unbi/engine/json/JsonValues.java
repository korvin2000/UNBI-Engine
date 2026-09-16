package com.unbi.engine.json;

import java.util.Collection;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Converts loose engine values to and from JSON nodes.
 *
 * <p>Widget defaults, graph values, saved presets and a parsed model answer are all "whatever the
 * node declared", so a small explicit converter is safer than handing arbitrary objects to a
 * general-purpose mapper and hoping.
 *
 * <p>Its own package, above transport and below everything else, because four places need this one
 * rule: the catalog encoder, the graph decoder, the preset store and the JSON-parsing node. Four
 * hand-written copies of "how does JSON become a graph value" is the textbook way for two of them to
 * start disagreeing about whether 5 is an Integer.
 */
public final class JsonValues {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private JsonValues() {}

    public static JsonNode of(Object value) {
        return switch (value) {
            case null -> NODES.nullNode();
            case String text -> NODES.stringNode(text);
            case Boolean flag -> NODES.booleanNode(flag);
            case Integer number -> NODES.numberNode(number);
            case Long number -> NODES.numberNode(number);
            case Double number -> NODES.numberNode(number);
            case Number number -> NODES.numberNode(number.doubleValue());
            case Collection<?> items -> {
                var array = NODES.arrayNode();
                items.forEach(item -> array.add(of(item)));
                yield array;
            }
            case Map<?, ?> map -> {
                var object = NODES.objectNode();
                map.forEach((key, entry) -> object.set(String.valueOf(key), of(entry)));
                yield object;
            }
            // Records reaching here are node-pack values being previewed; their string form is
            // adequate and does not require the transport to know the pack.
            default -> NODES.stringNode(String.valueOf(value));
        };
    }

    /**
     * Reads a JSON value into the loosest Java shape that preserves it.
     *
     * <p>Numbers always come back as {@code Double}: JSON does not distinguish 5 from 5.0, and
     * letting the type slip between Integer and Double is how "expected Number, got Integer" bugs
     * appear later. {@code InputResolver} coerces from here against the declared port type.
     */
    public static Object from(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            return node.asString();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            var items = new java.util.ArrayList<Object>();
            node.forEach(item -> items.add(from(item)));
            return java.util.Collections.unmodifiableList(items);
        }
        if (node.isObject()) {
            var map = new java.util.LinkedHashMap<String, Object>();
            node.propertyNames().forEach(name -> map.put(name, from(node.get(name))));
            return java.util.Collections.unmodifiableMap(map);
        }
        return node.asString();
    }
}
