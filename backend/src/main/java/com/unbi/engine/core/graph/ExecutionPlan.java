package com.unbi.engine.core.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A validated, ordered run plan.
 *
 * <p>Producing one is the only way to get a runnable order, and it cannot be produced for a cyclic
 * graph. That makes "is this graph acyclic?" unrepresentable as a forgotten check: the engine takes
 * an {@code ExecutionPlan}, not a {@code WorkflowGraph}.
 */
public record ExecutionPlan(WorkflowGraph graph, List<String> order) {

    public ExecutionPlan {
        order = List.copyOf(order);
    }

    /**
     * Orders the graph for execution using Kahn's algorithm.
     *
     * <p>Ties are broken by the order nodes appear in the document rather than by hash order, so a
     * given graph always runs in the same sequence. Non-determinism here would make failures
     * intermittent and progress reporting jump around between runs for no reason.
     *
     * @return the plan, or a cycle description if the graph cannot be ordered
     */
    public static Result order(WorkflowGraph graph) {
        var remainingDependencies = new HashMap<String, Integer>();
        var dependents = new HashMap<String, List<String>>();

        for (var node : graph.nodes()) {
            remainingDependencies.put(node.id(), 0);
            dependents.put(node.id(), new ArrayList<>());
        }

        for (var edge : graph.edges()) {
            // An edge referencing a missing node is a validation error, not an ordering error.
            // Skip it here so callers get the better message from GraphValidator.
            if (!remainingDependencies.containsKey(edge.sourceNode())
                    || !remainingDependencies.containsKey(edge.targetNode())) {
                continue;
            }
            remainingDependencies.merge(edge.targetNode(), 1, Integer::sum);
            dependents.get(edge.sourceNode()).add(edge.targetNode());
        }

        var ready = new ArrayDeque<String>();
        for (var node : graph.nodes()) {
            if (remainingDependencies.get(node.id()) == 0) {
                ready.add(node.id());
            }
        }

        var ordered = new ArrayList<String>(graph.nodes().size());
        while (!ready.isEmpty()) {
            var current = ready.removeFirst();
            ordered.add(current);
            for (var dependent : dependents.get(current)) {
                if (remainingDependencies.merge(dependent, -1, Integer::sum) == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (ordered.size() != graph.nodes().size()) {
            var stuck = new LinkedHashSet<String>();
            for (var node : graph.nodes()) {
                if (!ordered.contains(node.id())) {
                    stuck.add(node.id());
                }
            }
            return new Result(null, findCycle(graph, stuck).orElse(List.copyOf(stuck)));
        }
        return new Result(new ExecutionPlan(graph, ordered), List.of());
    }

    /**
     * Walks the unordered remainder to recover one concrete cycle.
     *
     * <p>Kahn's algorithm tells us a cycle exists but not where. Reporting the actual loop instead
     * of every node caught behind it is the difference between a usable error and a shrug.
     */
    private static Optional<List<String>> findCycle(WorkflowGraph graph, Set<String> candidates) {
        var visiting = new LinkedHashSet<String>();
        var settled = new java.util.HashSet<String>();
        for (var start : candidates) {
            var cycle = walk(graph, start, candidates, visiting, settled);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<String>> walk(
            WorkflowGraph graph,
            String current,
            Set<String> candidates,
            LinkedHashSet<String> visiting,
            Set<String> settled) {

        if (visiting.contains(current)) {
            var path = new ArrayList<>(visiting);
            return Optional.of(List.copyOf(path.subList(path.indexOf(current), path.size())));
        }
        if (settled.contains(current)) {
            return Optional.empty();
        }
        visiting.add(current);
        for (var edge : graph.outgoing(current)) {
            if (!candidates.contains(edge.targetNode())) {
                continue;
            }
            var cycle = walk(graph, edge.targetNode(), candidates, visiting, settled);
            if (cycle.isPresent()) {
                return cycle;
            }
        }
        visiting.remove(current);
        settled.add(current);
        return Optional.empty();
    }

    /**
     * Either a plan, or the node ids forming a cycle.
     *
     * @param cycle the loop in traversal order; empty when {@code plan} is present
     */
    public record Result(ExecutionPlan plan, List<String> cycle) {

        public boolean isOrdered() {
            return plan != null;
        }
    }
}
