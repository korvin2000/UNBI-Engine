package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Reading widget values that the standard accessors cannot express.
 *
 * <p>{@code NodeContext} deliberately offers {@code text}, {@code number} and {@code flag} and
 * nothing else, because those are what a node usually needs. This pack needs three more shapes —
 * a number that may be absent, a list, and a map — and they belong beside the nodes that use them
 * rather than in the SPI every other pack has to read.
 *
 * <p>The absent number is the one that matters. A sampling parameter left blank and a sampling
 * parameter set to zero are different requests, and collapsing them is how a target that validates
 * and then ignores a temperature ends up recorded as having honoured one.
 */
final class NodeValues {

    private NodeValues() {}

    /** @return null when the field is blank, which the specs read as "do not send this" */
    static Double optionalDouble(NodeContext context, String key) {
        var raw = context.rawInput(key);
        return switch (raw) {
            case null -> null;
            case Number number -> number.doubleValue();
            case String text -> text.isBlank() ? null : parseDouble(text, key);
            default -> parseDouble(String.valueOf(raw), key);
        };
    }

    static Integer optionalInt(NodeContext context, String key) {
        var value = optionalDouble(context, key);
        return value == null ? null : (int) Math.round(value);
    }

    static int intOr(NodeContext context, String key, int fallback) {
        var value = optionalInt(context, key);
        return value == null ? fallback : value;
    }

    /**
     * A list of strings, from a multi-select list or from a comma-separated field.
     *
     * <p>Both, because the same reader serves a closed set the user picks from and an open one they
     * type — and a node should not care which widget the descriptor chose.
     */
    static List<String> strings(NodeContext context, String key) {
        var raw = context.rawInput(key);
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> items) {
            var found = new ArrayList<String>(items.size());
            for (var item : items) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    found.add(String.valueOf(item).trim());
                }
            }
            return List.copyOf(found);
        }
        return csv(String.valueOf(raw));
    }

    static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** A string map from a key/value widget. Blank keys are dropped: they cannot mean anything. */
    static SequencedMap<String, String> stringMap(NodeContext context, String key) {
        var found = new LinkedHashMap<String, String>();
        if (context.rawInput(key) instanceof Map<?, ?> map) {
            map.forEach((name, value) -> {
                var text = String.valueOf(name).trim();
                if (!text.isEmpty() && value != null) {
                    found.put(text, String.valueOf(value));
                }
            });
        }
        return found;
    }

    static String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred.trim() : fallback;
    }

    /** A three-way switch whose third state is "whatever the profile says". */
    static boolean tristate(String raw, boolean fromProfile) {
        return switch (raw == null ? "" : raw.trim()) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> fromProfile;
        };
    }

    private static Double parseDouble(String text, String key) {
        try {
            return Double.valueOf(text.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalStateException("%s should be a number, but is '%s'".formatted(key, text.trim()));
        }
    }
}
