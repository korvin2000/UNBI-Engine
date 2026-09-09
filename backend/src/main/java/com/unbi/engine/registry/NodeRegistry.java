package com.unbi.engine.registry;

import com.unbi.engine.core.node.NodeCatalog;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The node index — and the whole of the plugin system.
 *
 * <p>Spring hands us every {@link NodeDefinition} bean in the context. There is no scanning, no
 * manifest, no service-loader file and no registration call for a node author to forget. Dropping a
 * new {@code @Component} node class into any package makes it appear here, in the catalog endpoint,
 * and in the palette, with no other edit anywhere.
 *
 * <p>Consequences worth stating: an extension jar on the classpath contributes nodes by existing,
 * and a deployment can replace one node with {@code @Primary} without touching the original.
 */
@Component
public class NodeRegistry implements NodeCatalog {

    private static final Logger log = LoggerFactory.getLogger(NodeRegistry.class);

    private final Map<String, NodeDefinition> byId;

    public NodeRegistry(List<NodeDefinition> discovered) {
        var index = new LinkedHashMap<String, NodeDefinition>();
        for (var definition : sortedForStableCatalog(discovered)) {
            var previous = index.put(definition.id(), definition);
            if (previous != null) {
                // Two nodes claiming one id would make saved workflows ambiguous. Fail at startup
                // rather than resolve it arbitrarily and surprise someone months later.
                throw new IllegalStateException("Duplicate node id %s claimed by %s and %s".formatted(
                        definition.id(),
                        previous.getClass().getName(),
                        definition.getClass().getName()));
            }
        }
        // Not Map.copyOf: that returns an unordered map and would throw away the sort above, leaving
        // the palette in hash order. Unmodifiable LinkedHashMap keeps both the immutability and the
        // ordering the catalog endpoint promises.
        this.byId = Collections.unmodifiableMap(index);
        log.info("Registered {} node types: {}", byId.size(), byId.keySet());
    }

    /** Stable catalog order so the palette does not reshuffle between restarts. */
    private static List<NodeDefinition> sortedForStableCatalog(List<NodeDefinition> discovered) {
        return discovered.stream()
                .sorted(Comparator
                        .comparing((NodeDefinition definition) -> definition.descriptor().category())
                        .thenComparing(definition -> definition.descriptor().subcategory())
                        .thenComparing(NodeDefinition::id))
                .toList();
    }

    public Optional<NodeDefinition> definition(String typeId) {
        return Optional.ofNullable(byId.get(typeId));
    }

    public NodeDefinition require(String typeId) {
        return definition(typeId)
                .orElseThrow(() -> new IllegalArgumentException("No node type registered as " + typeId));
    }

    @Override
    public Optional<NodeDescriptor> find(String typeId) {
        return definition(typeId).map(NodeDefinition::descriptor);
    }

    @Override
    public Collection<NodeDescriptor> all() {
        return byId.values().stream().map(NodeDefinition::descriptor).toList();
    }
}
