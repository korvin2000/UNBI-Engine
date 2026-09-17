package com.unbi.engine.settings;

import com.unbi.engine.json.JsonValues;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The engine's settings over HTTP: where things are, what the editor prefers, and what the engine is.
 *
 * <p>Two writes rather than one, because they are different promises. A preference change is a
 * merge of scalars that cannot fail in any interesting way; a path change can copy a directory
 * tree and has to say what it did. Each path is a separate request so the editor can ask about one
 * directory at a time and show the outcome beside it.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final SettingsStore store;
    private final String version;

    public SettingsController(SettingsStore store, ObjectProvider<BuildProperties> build) {
        this.store = store;
        var properties = build.getIfAvailable();
        this.version = properties == null ? "development" : properties.getVersion();
    }

    @GetMapping
    public ObjectNode get() {
        return describe(store.current());
    }

    /** Merges the given preferences: an object of scalar values, where {@code null} removes a key. */
    @PostMapping("/preferences")
    public ObjectNode preferences(@RequestBody JsonNode body) throws IOException {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected an object of preferences");
        }
        var changes = new LinkedHashMap<String, Object>();
        body.propertyNames().forEach(name -> changes.put(name, JsonValues.from(body.get(name))));
        try {
            return describe(store.updatePreferences(changes));
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    /**
     * Points one of the relocatable directories somewhere else.
     *
     * <p>Body: {@code target} ("data" or "workflows"), {@code directory} (blank for the default) and
     * {@code copyExisting}. The answer is the settings as {@code GET} would return them, plus a
     * {@code relocation} block saying what was copied and what was left alone.
     */
    @PostMapping("/paths")
    public ObjectNode paths(@RequestBody JsonNode body) throws IOException {
        if (body == null || !body.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected a relocation request");
        }
        var target = switch (body.path("target").asString("").trim().toLowerCase(Locale.ROOT)) {
            case "data" -> SettingsStore.Target.DATA;
            case "workflows" -> SettingsStore.Target.WORKFLOWS;
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "target must be \"data\" or \"workflows\"");
        };
        try {
            var done = store.relocate(
                    target, body.path("directory").asString(""), body.path("copyExisting").asBoolean(false));
            var response = describe(done.settings());
            var relocation = response.putObject("relocation");
            relocation.put("target", target.name().toLowerCase(Locale.ROOT));
            relocation.put("from", done.from().toString());
            relocation.put("to", done.to().toString());
            relocation.put("copied", done.filesCopied());
            relocation.put("skipped", done.filesSkipped());
            return response;
        } catch (SettingsStore.DataBusyException busy) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, busy.getMessage(), busy);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage(), invalid);
        }
    }

    private ObjectNode describe(Settings settings) {
        var root = NODES.objectNode();
        root.put("format", SettingsStore.FORMAT);
        root.put("version", SettingsStore.VERSION);
        root.put("file", store.file().toString());
        root.put("error", store.loadError());

        var paths = root.putObject("paths");
        describePath(paths.putObject("data"), settings.dataDirectory(), store.dataRoot(), store.defaultDataRoot());
        describePath(paths.putObject("workflows"), settings.workflowsDirectory(), store.workflowsRoot(),
                store.defaultWorkflowsRoot());

        var preferences = root.putObject("preferences");
        settings.preferences().forEach((key, value) -> preferences.set(key, JsonValues.of(value)));

        var engine = root.putObject("engine");
        engine.put("version", version);
        engine.put("java", Runtime.version().toString());
        engine.put("os", System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""));
        return root;
    }

    private static void describePath(ObjectNode node, String configured, Path effective, Path fallback) {
        node.put("configured", configured);
        node.put("effective", effective.toString());
        node.put("default", fallback.toString());
    }
}
