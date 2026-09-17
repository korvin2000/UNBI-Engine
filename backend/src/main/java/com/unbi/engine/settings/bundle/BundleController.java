package com.unbi.engine.settings.bundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Export and import of settings bundles over HTTP.
 *
 * <p>Export is a JSON request answered with the file, so the editor can post a checklist and a
 * password and receive a download. Import is two multipart requests: {@code inspect} reads only the
 * manifest and says what is inside and whether a password will be needed, {@code import} does the
 * work. Two uploads of a small file cost less than a staging area the engine would have to expire.
 */
@RestController
@RequestMapping("/api/settings")
public class BundleController {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SettingsBundle bundle;

    public BundleController(SettingsBundle bundle) {
        this.bundle = bundle;
    }

    /** The checklist: every section this engine has, with how many items each holds right now. */
    @GetMapping("/sections")
    public ArrayNode sections() {
        var array = NODES.arrayNode();
        for (var section : bundle.sections()) {
            array.addObject()
                    .put("id", section.id())
                    .put("label", section.label())
                    .put("description", section.description())
                    .put("sensitive", section.sensitive())
                    .put("count", section.count());
        }
        return array;
    }

    /** Body: {@code sections} (ids) and an optional {@code password}. Answers with the {@code .ucfg}. */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody JsonNode body) throws IOException {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected an export request");
        }
        var ids = new ArrayList<String>();
        body.path("sections").forEach(id -> ids.add(id.asString("")));
        byte[] bytes;
        try {
            bytes = bundle.export(ids, body.path("password").asString(""));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        var fileName = "unbi-settings-" + LocalDate.now() + SettingsBundle.EXTENSION;
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .body(bytes);
    }

    @PostMapping(value = "/import/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObjectNode inspect(@RequestPart("file") MultipartFile file) throws IOException {
        return withTemporaryCopy(file, path -> {
            var inspection = bundle.inspect(path);
            var root = NODES.objectNode();
            root.put("engine", inspection.manifest().engine());
            root.put("createdAt", inspection.manifest().createdAt().toString());
            root.put("encrypted", inspection.manifest().encrypted());
            var sections = root.putArray("sections");
            for (var entry : inspection.sections()) {
                var node = sections.addObject().put("id", entry.id()).put("count", entry.count()).put("known", entry.known());
                bundle.section(entry.id()).ifPresent(section -> node
                        .put("label", section.label())
                        .put("sensitive", section.sensitive()));
            }
            return root;
        });
    }

    /**
     * Form fields beside the file: {@code sections} (comma-separated ids), {@code conflicts}
     * ({@code skip}, {@code replace} or {@code keep-both}) and an optional {@code password}.
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ObjectNode importBundle(
            @RequestPart("file") MultipartFile file,
            @RequestParam("sections") String sections,
            @RequestParam(name = "conflicts", defaultValue = "skip") String conflicts,
            @RequestParam(name = "password", required = false) String password) throws IOException {
        var ids = Arrays.asList(sections.split(","));
        ConflictPolicy policy;
        try {
            policy = ConflictPolicy.parse(conflicts);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
        return withTemporaryCopy(file, path -> {
            var report = bundle.importFrom(path, ids, password, policy);
            var root = NODES.objectNode();
            var array = root.putArray("sections");
            for (var section : report.sections()) {
                var node = array.addObject()
                        .put("id", section.id())
                        .put("imported", section.imported())
                        .put("replaced", section.replaced())
                        .put("skipped", section.skipped());
                var problems = node.putArray("problems");
                section.problems().forEach(problems::add);
            }
            return root;
        });
    }

    private interface Work {
        ObjectNode apply(Path file) throws IOException;
    }

    /** zip4j reads from a file, and the upload is a stream: copy it, use it, delete it. */
    private static ObjectNode withTemporaryCopy(MultipartFile upload, Work work) throws IOException {
        if (upload == null || upload.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file was uploaded.");
        }
        var temporary = Files.createTempFile("unbi-bundle", SettingsBundle.EXTENSION);
        try {
            upload.transferTo(temporary);
            return work.apply(temporary);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
