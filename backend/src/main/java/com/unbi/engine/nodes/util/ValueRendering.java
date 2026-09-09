package com.unbi.engine.nodes.util;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns whatever came down an edge into something a person can read.
 *
 * <p>Reflects over record components rather than switching on known types. That is what lets the
 * preview and report nodes accept {@code Any} and still render a proper table for a node pack that
 * did not exist when they were written — the alternative is a formatter with a case per domain type,
 * which every new pack would have to remember to extend.
 */
public final class ValueRendering {

    /** Past this a report stops informing and starts being a data dump. */
    private static final int MAX_ROWS = 500;

    private ValueRendering() {}

    public static String describe(Object value) {
        if (value == null) {
            return "nothing";
        }
        if (value instanceof Collection<?> items) {
            return items.size() + (items.size() == 1 ? " item" : " items");
        }
        return String.valueOf(value);
    }

    /** Renders as a Markdown table when the shape allows it, and as a list when it does not. */
    public static String toMarkdown(Object value) {
        return render(value, "|", true);
    }

    public static String toCsv(Object value) {
        return render(value, ",", false);
    }

    public static String toPlainText(Object value) {
        if (!(value instanceof Collection<?> items) || items.isEmpty()) {
            return String.valueOf(value);
        }
        return items.stream().limit(MAX_ROWS).map(ValueRendering::flatten).collect(Collectors.joining("\n"));
    }

    private static String render(Object value, String separator, boolean markdown) {
        if (!(value instanceof Collection<?> items)) {
            return String.valueOf(value);
        }
        if (items.isEmpty()) {
            return markdown ? "_No results._" : "";
        }
        var first = items.iterator().next();
        var components = componentsOf(first);
        if (components.isEmpty()) {
            return toPlainText(value);
        }

        var out = new StringBuilder();
        var headers = components.stream().map(RecordComponent::getName).toList();
        appendRow(out, headers, separator, markdown);
        if (markdown) {
            appendRow(out, headers.stream().map(header -> "---").toList(), separator, true);
        }

        var shown = 0;
        for (var item : items) {
            if (shown++ == MAX_ROWS) {
                out.append(markdown
                        ? "\n_... and %d more._".formatted(items.size() - MAX_ROWS)
                        : "\n... and %d more".formatted(items.size() - MAX_ROWS));
                break;
            }
            appendRow(out, valuesOf(item, components, markdown), separator, markdown);
        }
        return out.toString();
    }

    private static List<RecordComponent> componentsOf(Object item) {
        if (item == null || !item.getClass().isRecord()) {
            return List.of();
        }
        return List.of(item.getClass().getRecordComponents());
    }

    private static List<String> valuesOf(Object item, List<RecordComponent> components, boolean markdown) {
        var values = new ArrayList<String>(components.size());
        for (var component : components) {
            Object read;
            try {
                read = component.getAccessor().invoke(item);
            } catch (ReflectiveOperationException unreadable) {
                read = "?";
            }
            var text = String.valueOf(read);
            values.add(markdown ? escapeForTableCell(text) : escapeForCsv(text));
        }
        return values;
    }

    private static void appendRow(StringBuilder out, List<String> cells, String separator, boolean markdown) {
        if (markdown) {
            out.append("| ").append(String.join(" | ", cells)).append(" |\n");
        } else {
            out.append(String.join(separator, cells)).append('\n');
        }
    }

    /** An unescaped pipe from matched source code would silently break every following column. */
    private static String escapeForTableCell(String text) {
        return text.replace("|", "\\|").replace("\n", " ").replace("\r", "");
    }

    private static String escapeForCsv(String text) {
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return '"' + text.replace("\"", "\"\"").replace("\n", " ") + '"';
        }
        return text;
    }

    private static String flatten(Object item) {
        var components = componentsOf(item);
        if (components.isEmpty()) {
            return String.valueOf(item);
        }
        return valuesOf(item, components, false).stream().collect(Collectors.joining("  "));
    }
}
