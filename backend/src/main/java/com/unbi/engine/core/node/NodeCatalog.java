package com.unbi.engine.core.node;

import java.util.Collection;
import java.util.Optional;

/**
 * Read-only view of the known node types.
 *
 * <p>Exists so that {@code core} can validate a graph without knowing how nodes were discovered.
 * The production implementation is populated by dependency injection; tests hand the validator a
 * two-line map. That is the whole reason this interface is not simply the registry class.
 */
public interface NodeCatalog {

    Optional<NodeDescriptor> find(String typeId);

    Collection<NodeDescriptor> all();
}
