package com.unbi.engine.transport.codec;

import com.unbi.engine.core.type.PortType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Wire encoding for {@link PortType}.
 *
 * <p>Lives in {@code transport} rather than {@code core} so the domain stays free of Jackson. The
 * encoding is a tagged union on {@code kind}, and it is the same shape the frontend parses and the
 * same shape {@code contract/type-assignability.json} is written in — one format, three readers.
 */
public final class PortTypeCodec {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private PortTypeCodec() {}

    public static ObjectNode write(PortType type) {
        var node = NODES.objectNode();
        switch (type) {
            case PortType.Primitive primitive -> {
                node.put("kind", "primitive");
                node.put("name", primitive.name());
            }
            case PortType.ListOf list -> {
                node.put("kind", "list");
                node.set("element", write(list.element()));
            }
            case PortType.Struct struct -> {
                node.put("kind", "struct");
                node.put("name", struct.name());
                var fields = NODES.objectNode();
                struct.fields().forEach((key, value) -> fields.set(key, write(value)));
                node.set("fields", fields);
            }
            case PortType.Union union -> {
                node.put("kind", "union");
                var members = NODES.arrayNode();
                union.members().forEach(member -> members.add(write(member)));
                node.set("members", members);
            }
            case PortType.Any ignored -> node.put("kind", "any");
        }
        return node;
    }

    public static PortType read(JsonNode node) {
        var kind = requireText(node, "kind");
        return switch (kind) {
            case "primitive" -> PortType.primitive(requireText(node, "name"));
            case "any" -> PortType.any();
            case "list" -> PortType.list(read(require(node, "element")));
            case "struct" -> {
                var fields = new LinkedHashMap<String, PortType>();
                var raw = require(node, "fields");
                raw.propertyNames().forEach(name -> fields.put(name, read(raw.get(name))));
                yield PortType.struct(requireText(node, "name"), fields);
            }
            case "union" -> {
                var members = new ArrayList<PortType>();
                if (!(require(node, "members") instanceof ArrayNode array)) {
                    throw new IllegalArgumentException("union 'members' must be an array");
                }
                array.forEach(member -> members.add(read(member)));
                yield new PortType.Union(members);
            }
            default -> throw new IllegalArgumentException("Unknown port type kind: " + kind);
        };
    }

    private static JsonNode require(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Port type is missing required field '" + field + "': " + node);
        }
        return value;
    }

    private static String requireText(JsonNode node, String field) {
        return require(node, field).asString();
    }
}
