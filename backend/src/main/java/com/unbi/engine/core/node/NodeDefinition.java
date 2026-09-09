package com.unbi.engine.core.node;

/**
 * A node type: what it looks like, and what it does.
 *
 * <p>Implementations are discovered by dependency injection — every bean of this type is indexed by
 * the node registry at startup. Adding a node means adding one file: there is no manifest to edit,
 * no registration call to remember, and no list to keep in sync.
 *
 * <p>{@code execute} is allowed to throw. Node authors do file and network work, and forcing them
 * to wrap every checked exception buys nothing: the engine catches, reports the failure against the
 * node that raised it, and fails the run.
 */
public interface NodeDefinition {

    NodeDescriptor descriptor();

    void execute(NodeContext context) throws Exception;

    /** Convenience for the registry and for log messages. */
    default String id() {
        return descriptor().id();
    }
}
