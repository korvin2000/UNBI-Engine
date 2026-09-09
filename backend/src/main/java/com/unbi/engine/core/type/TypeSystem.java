package com.unbi.engine.core.type;

import com.unbi.engine.core.type.PortType.Any;
import com.unbi.engine.core.type.PortType.ListOf;
import com.unbi.engine.core.type.PortType.Primitive;
import com.unbi.engine.core.type.PortType.Struct;
import com.unbi.engine.core.type.PortType.Union;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single authority on whether an edge is legal.
 *
 * <p>Pure, total and memoized. Every connection check in the backend and — via the shared contract
 * table — in the frontend resolves here. Keeping this the only implementation of the rule is what
 * stops "can I connect these?" from drifting between the validator, the editor and the engine.
 */
public final class TypeSystem {

    private record Pair(PortType from, PortType to) {}

    private final Map<Pair, Boolean> memo = new ConcurrentHashMap<>();

    /**
     * Can a value of {@code from} be delivered to a port declaring {@code to}?
     *
     * <p>Rules, in the order they are applied:
     * <ol>
     *   <li>{@link Any} on either side matches — see the note on {@code Any}.</li>
     *   <li>A union target is satisfied by matching <em>any</em> member.</li>
     *   <li>A union source must satisfy the target through <em>every</em> member.</li>
     *   <li>Lists are covariant in their element.</li>
     *   <li>Structs use width subtyping: the source must carry at least the target's fields.</li>
     *   <li>Primitives match by name.</li>
     * </ol>
     */
    public boolean assignable(PortType from, PortType to) {
        if (from.equals(to)) {
            return true;
        }
        var key = new Pair(from, to);
        var cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        // Deliberately get-then-put rather than computeIfAbsent: `compute` recurses for lists,
        // structs and unions, and ConcurrentHashMap forbids a recursive computeIfAbsent on the same
        // map. The function is pure, so a lost race only costs a repeated computation.
        var result = compute(from, to);
        memo.put(key, result);
        return result;
    }

    private boolean compute(PortType from, PortType to) {
        if (from instanceof Any || to instanceof Any) {
            return true;
        }
        // Source union first, and the order matters. Every member of the source must find a home in
        // the target -- which may itself be a union, resolved by the recursive call. Checking the
        // target first instead would ask "does `Text|Number` fit in `Text`?", then "...in `Number`?",
        // and wrongly refuse `Text|Number -> Text|Number|Boolean`.
        if (from instanceof Union union) {
            return union.members().stream().allMatch(member -> assignable(member, to));
        }
        // Target union second: a non-union source needs to satisfy just one member.
        if (to instanceof Union union) {
            return union.members().stream().anyMatch(member -> assignable(from, member));
        }
        return switch (from) {
            case Primitive source -> to instanceof Primitive target && source.name().equals(target.name());
            case ListOf source -> to instanceof ListOf target && assignable(source.element(), target.element());
            case Struct source -> to instanceof Struct target && satisfiesWidth(source, target);
            case Union ignored -> throw new IllegalStateException("unions handled above");
            case Any ignored -> throw new IllegalStateException("Any handled above");
        };
    }

    private boolean satisfiesWidth(Struct source, Struct target) {
        for (var required : target.fields().entrySet()) {
            var present = source.fields().get(required.getKey());
            if (present == null || !assignable(present, required.getValue())) {
                return false;
            }
        }
        return true;
    }

    /** Why a connection was refused, phrased for a user rather than for a log. */
    public String explainRejection(PortType from, PortType to) {
        if (assignable(from, to)) {
            throw new IllegalArgumentException("These types are compatible; there is nothing to explain");
        }
        if (from instanceof Struct source && to instanceof Struct target) {
            for (var required : target.fields().entrySet()) {
                var present = source.fields().get(required.getKey());
                if (present == null) {
                    return "%s has no '%s' field, which %s requires"
                            .formatted(source.name(), required.getKey(), target.name());
                }
                if (!assignable(present, required.getValue())) {
                    return "%s.%s is %s, but %s needs %s".formatted(
                            source.name(), required.getKey(), present.describe(),
                            target.name(), required.getValue().describe());
                }
            }
        }
        if (from instanceof ListOf source && to instanceof ListOf target) {
            return "list elements do not match: %s cannot become %s"
                    .formatted(source.element().describe(), target.element().describe());
        }
        if (to instanceof ListOf target) {
            return "expected a list of %s, got a single %s".formatted(target.element().describe(), from.describe());
        }
        if (from instanceof ListOf source) {
            return "expected a single %s, got a list of %s".formatted(to.describe(), source.element().describe());
        }
        return "%s cannot be connected to %s".formatted(from.describe(), to.describe());
    }
}
