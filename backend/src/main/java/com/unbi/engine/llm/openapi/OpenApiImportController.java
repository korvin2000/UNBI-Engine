package com.unbi.engine.llm.openapi;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/** Import is a reviewable projection only: this endpoint never saves, authenticates, or calls a gateway. */
@RestController
@RequestMapping("/api/profiles/llm.endpoint/import")
public class OpenApiImportController {
    private final OpenApiEndpointImporter importer;

    public OpenApiImportController(OpenApiEndpointImporter importer) {
        this.importer = importer;
    }

    @PostMapping(value = "/openapi", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ObjectNode> preview(@RequestBody OpenApiEndpointImporter.Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(importer.preview(request));
    }
}
