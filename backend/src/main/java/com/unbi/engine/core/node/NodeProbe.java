package com.unbi.engine.core.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A node that can answer questions about its own configuration without being run.
 *
 * <p>A second, deliberately separate interface rather than more methods on {@link NodeDefinition}:
 * most nodes have nothing to check, and a node that has nothing to check should not have to say so.
 * Implementing this is opt-in, one {@code implements} away, exactly like adding a node is one file
 * away.
 *
 * <p>The request carries widget values, never a running graph. A probe must be safe to press: it is
 * allowed to ask a gateway what it offers, and it is not allowed to execute the graph that leads to
 * it. That line is the reason the upstream nodes arrive as {@link Source} values — their settings,
 * flattened — rather than as something the engine could be tempted to run.
 */
public interface NodeProbe {

    /**
     * @param action one of the keys this node declared as a {@link NodeAction}
     * @return what to show, and what to fill in; never null
     */
    Result probe(String action, Request request) throws Exception;

    /**
     * This node's settings, plus those of whatever is wired into it.
     *
     * <p>Transitive, because the question "can this model be reached?" is asked on a Model node and
     * answered with an Endpoint node's base URL, one hop further up. The editor walks the graph and
     * sends the closure; nothing here has to know how far the answer lives.
     */
    record Request(String nodeType, Map<String, Object> values, Map<String, Source> sources) {

        public Request {
            values = settings(values);
            sources = Map.copyOf(sources == null ? Map.of() : sources);
        }

        public String text(String key) {
            var value = values.get(key);
            return value == null ? "" : String.valueOf(value);
        }

        /** The node wired into one socket, or an empty source — never null, so probes stay linear. */
        public Source source(String socket) {
            return sources.getOrDefault(socket, Source.NONE);
        }
    }

    /** One upstream node's settings, and in turn whatever is wired into <em>it</em>. */
    record Source(String nodeType, Map<String, Object> values, Map<String, Source> sources) {

        public static final Source NONE = new Source("", Map.of(), Map.of());

        public Source {
            nodeType = nodeType == null ? "" : nodeType;
            values = settings(values);
            sources = Map.copyOf(sources == null ? Map.of() : sources);
        }

        public boolean isPresent() {
            return !nodeType.isBlank();
        }

        /**
         * One setting of the upstream node, as text — mirroring {@link Request#text}.
         *
         * <p>Here because the question a probe asks is often two hops up: "which model?" is a value
         * on the Model node, read by a node wired downstream of it. Without this, every such probe
         * reaches into {@code values()} and re-implements the null handling.
         */
        public String text(String key) {
            var value = values.get(key);
            return value == null ? "" : String.valueOf(value);
        }

        public Source source(String socket) {
            return sources.getOrDefault(socket, NONE);
        }
    }

    /**
     * A defensive copy in which a null value and an absent key are the same thing.
     *
     * <p>They have to be: an input whose descriptor default is null arrives as a null, and a
     * settings map that refused to hold one would fail every probe of every node with an optional
     * field in it. {@code Map.copyOf} does exactly that, which is how this was found.
     */
    private static Map<String, Object> settings(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        var copy = new LinkedHashMap<String, Object>(values.size());
        values.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * What the editor shows, and what it may fill in.
     *
     * @param ok      lights the indicator green or red; the one thing the user reads at a glance
     * @param message one sentence, shown beside the indicator
     * @param details the evidence — what was asked, what answered, what it costs
     * @param options per input key, the choices discovered for it
     * @param values  per input key, a value to write into the node
     */
    record Result(
            boolean ok,
            String message,
            List<String> details,
            Map<String, List<Widget.Option>> options,
            Map<String, Object> values) {

        public Result {
            message = message == null ? "" : message;
            details = List.copyOf(details == null ? List.of() : details);
            options = Map.copyOf(options == null ? Map.of() : options);
            values = Map.copyOf(values == null ? Map.of() : values);
        }

        public static Result failed(String message) {
            return new Result(false, message, List.of(), Map.of(), Map.of());
        }

        public static Builder ok(String message) {
            return new Builder(true, message);
        }

        public static Builder problem(String message) {
            return new Builder(false, message);
        }

        public static final class Builder {

            private final boolean ok;
            private final String message;
            private final List<String> details = new ArrayList<>();
            private final Map<String, List<Widget.Option>> options = new LinkedHashMap<>();
            private final Map<String, Object> values = new LinkedHashMap<>();

            private Builder(boolean ok, String message) {
                this.ok = ok;
                this.message = message;
            }

            public Builder detail(String line) {
                if (line != null && !line.isBlank()) {
                    details.add(line);
                }
                return this;
            }

            public Builder options(String inputKey, List<Widget.Option> choices) {
                options.put(inputKey, List.copyOf(choices));
                return this;
            }

            /** A value to write into the node; null is dropped so "nothing found" stays silent. */
            public Builder value(String inputKey, Object value) {
                if (value != null) {
                    values.put(inputKey, value);
                }
                return this;
            }

            public Result build() {
                return new Result(ok, message, details, options, values);
            }
        }
    }
}
