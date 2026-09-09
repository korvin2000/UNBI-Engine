package com.unbi.engine.core.type;

import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * The type of a value that can travel along an edge.
 *
 * <p>Deliberately a five-case lattice rather than a general type language. It exists to answer
 * exactly one question — {@link TypeSystem#assignable} — and every case earns its place by being
 * required to express the shipped node packs.
 *
 * <p>The wire encoding of these cases is mirrored in the frontend, and both implementations are
 * held to the same table in {@code contract/type-assignability.json}.
 */
public sealed interface PortType {

    /** Human-readable form, used in tooltips and in connection-rejection messages. */
    String describe();

    /** A named leaf: {@code String}, {@code Number}, {@code Directory}. */
    record Primitive(String name) implements PortType {
        public Primitive {
            requireName(name);
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /**
     * A named record. Assignability is structural width-subtyping: the name is documentation, the
     * fields are the contract. Field order is preserved so generated UI is stable.
     */
    record Struct(String name, SequencedMap<String, PortType> fields) implements PortType {
        public Struct {
            requireName(name);
            fields = java.util.Collections.unmodifiableSequencedMap(new java.util.LinkedHashMap<>(fields));
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /** A homogeneous sequence. Covariant in its element type. */
    record ListOf(PortType element) implements PortType {
        public ListOf {
            java.util.Objects.requireNonNull(element, "element");
        }

        @Override
        public String describe() {
            return element.describe() + "[]";
        }
    }

    /** Any-of. Flattened and de-duplicated on construction so equality stays meaningful. */
    record Union(List<PortType> members) implements PortType {
        public Union {
            var flattened = new java.util.LinkedHashSet<PortType>();
            for (var member : members) {
                switch (member) {
                    case Union nested -> flattened.addAll(nested.members());
                    default -> flattened.add(member);
                }
            }
            if (flattened.size() < 2) {
                throw new IllegalArgumentException("A union needs at least two distinct members, got " + flattened);
            }
            members = List.copyOf(flattened);
        }

        @Override
        public String describe() {
            return members.stream().map(PortType::describe).reduce((a, b) -> a + " | " + b).orElseThrow();
        }
    }

    /**
     * Top type, and the dynamic escape hatch.
     *
     * <p>Assignable in <em>both</em> directions on purpose: pass-through and reporting nodes accept
     * whatever they are given, and a node that produces a genuinely dynamic value must be able to
     * feed a typed port. This is the one unsound rule in the lattice and it is deliberate — the
     * alternative is that {@code report.generate} needs an overload per input type.
     */
    record Any() implements PortType {
        @Override
        public String describe() {
            return "Any";
        }
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Type name must not be blank");
        }
    }

    // --- Construction helpers. Node files read far better with these than with `new`. ---

    static PortType primitive(String name) {
        return new Primitive(name);
    }

    static PortType list(PortType element) {
        return new ListOf(element);
    }

    static PortType union(PortType... members) {
        return new Union(List.of(members));
    }

    static PortType any() {
        return new Any();
    }

    static PortType struct(String name, Map<String, PortType> fields) {
        return new Struct(name, new java.util.LinkedHashMap<>(fields));
    }
}
