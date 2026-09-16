package com.unbi.engine.llm.prompt;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitution, and nothing else.
 *
 * <p>{@code {{name}}} is replaced by a bound value; {@code {{a.b}}} walks into a map or a record;
 * {@code \{{} is a literal brace. There is no control flow and there will not be: a prompt with an
 * {@code if} in it is a prompt whose behaviour is decided somewhere nobody looks, and the graph
 * already has branching in a form the user can see.
 *
 * <p>Strict rendering is the default because the alternative fails quietly in the worst possible
 * place — a typo in a variable name becomes an empty hole in the middle of an instruction, the model
 * answers something plausible, and nothing anywhere says a value went missing.
 */
public final class PromptTemplate {

    /** A placeholder, with an optional escape immediately before it. */
    private static final Pattern PLACEHOLDER = Pattern.compile("(\\\\?)\\{\\{\\s*([\\w.\\-]+)\\s*}}");

    /** Bounded so a runaway list cannot turn one prompt into a hundred megabytes. */
    private static final int MAX_JOINED_ITEMS = 500;

    private PromptTemplate() {}

    /** Every variable the template reads, in first-appearance order. */
    public static SequencedSet<String> variables(String source) {
        var found = new LinkedHashSet<String>();
        if (source == null) {
            return found;
        }
        var matcher = PLACEHOLDER.matcher(source);
        while (matcher.find()) {
            if (matcher.group(1).isEmpty()) {
                found.add(matcher.group(2));
            }
        }
        return found;
    }

    /**
     * @param strict throw when the template reads a name nothing bound; otherwise leave it empty
     * @throws MissingVariableException naming the variable and what was actually available, which is
     *     the only form of this message anyone can act on
     */
    public static String render(String source, Map<String, ?> variables, boolean strict) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        var missing = new ArrayList<String>();
        var matcher = PLACEHOLDER.matcher(source);
        var out = new StringBuilder();
        while (matcher.find()) {
            if (!matcher.group(1).isEmpty()) {
                // Escaped: emit the braces literally and drop the backslash.
                matcher.appendReplacement(out, Matcher.quoteReplacement("{{" + matcher.group(2) + "}}"));
                continue;
            }
            var name = matcher.group(2);
            var resolved = resolve(variables, name);
            if (resolved == null) {
                missing.add(name);
                matcher.appendReplacement(out, "");
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(stringify(resolved)));
        }
        matcher.appendTail(out);

        if (strict && !missing.isEmpty()) {
            throw new MissingVariableException(missing, variables.keySet());
        }
        return out.toString();
    }

    /** Walks {@code a.b.c} through maps and record components. */
    private static Object resolve(Map<String, ?> variables, String path) {
        Object current = null;
        var segments = path.split("\\.");
        for (int index = 0; index < segments.length; index++) {
            current = index == 0 ? variables.get(segments[0]) : field(current, segments[index]);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static Object field(Object holder, String name) {
        if (holder instanceof Map<?, ?> map) {
            return map.get(name);
        }
        if (holder.getClass().isRecord()) {
            for (RecordComponent component : holder.getClass().getRecordComponents()) {
                if (component.getName().equals(name)) {
                    try {
                        return component.getAccessor().invoke(holder);
                    } catch (ReflectiveOperationException unreadable) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * How a bound value reads inside a prompt.
     *
     * <p>A list becomes one item per line rather than a Java {@code toString}: wiring a file list
     * into a prompt is the obvious thing to do, and {@code [FileRef[path=...], FileRef[...]]} is not
     * what anyone meant by it.
     */
    public static String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Collection<?> items) {
            var lines = items.stream().limit(MAX_JOINED_ITEMS).map(PromptTemplate::stringify).toList();
            var joined = String.join("\n", lines);
            return items.size() > MAX_JOINED_ITEMS
                    ? joined + "\n… and %d more".formatted(items.size() - MAX_JOINED_ITEMS)
                    : joined;
        }
        if (value instanceof Double number && number == Math.floor(number) && !number.isInfinite()) {
            // Widget numbers arrive as doubles; "5.0 files" in a prompt is noise the model has to
            // interpret, and occasionally misinterprets.
            return String.valueOf(number.longValue());
        }
        return String.valueOf(value);
    }

    /** Raised by strict rendering. Carries both halves of the answer: what is missing, and what is not. */
    public static final class MissingVariableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient List<String> missing;

        MissingVariableException(List<String> missing, Collection<String> available) {
            super(describe(missing, available));
            this.missing = List.copyOf(missing);
        }

        public List<String> missing() {
            return missing;
        }

        private static String describe(List<String> missing, Collection<String> available) {
            var names = String.join(", ", missing);
            return available.isEmpty()
                    ? "The template reads %s, and nothing is bound. Wire a Variables node in, or bind a value."
                            .formatted(names)
                    : "The template reads %s, which nothing bound. Available: %s."
                            .formatted(names, String.join(", ", available));
        }
    }
}
