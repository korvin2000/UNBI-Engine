package com.unbi.engine.transport;

import com.unbi.engine.registry.NodeRegistry;
import com.unbi.engine.transport.codec.CatalogCodec;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serves the node catalog.
 *
 * <p>Fetched once at editor startup. This endpoint is the reason the frontend contains no node
 * definitions of its own: the palette, the node bodies and the port colours are all rendered from
 * what this returns.
 */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final NodeRegistry registry;

    public CatalogController(NodeRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/catalog")
    public ObjectNode catalog() {
        return CatalogCodec.catalog(registry.all());
    }
}
