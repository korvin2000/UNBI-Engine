package com.unbi.engine.transport;

import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.presets.PresetStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dropdown options the engine knows and the descriptor cannot.
 *
 * <p>A node descriptor is built once at startup, so any list that changes while the engine runs —
 * which credentials exist, which preset groups are in use — cannot live in it. A widget names a
 * catalog key instead and the editor merges what this returns.
 *
 * <p>That keeps the property the whole design rests on: the frontend still knows no node types. It
 * learns that a field offers a list of credentials from the descriptor, and what is in that list
 * from here, and never from code written about a particular node.
 *
 * <p><b>Names only.</b> Credential values never leave the engine, and there is no endpoint that
 * could return one.
 */
@RestController
@RequestMapping("/api")
public class OptionsController {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final CredentialStore credentials;
    private final PresetStore presets;

    public OptionsController(CredentialStore credentials, PresetStore presets) {
        this.credentials = credentials;
        this.presets = presets;
    }

    @GetMapping("/options")
    public ObjectNode options() {
        var root = NODES.objectNode();

        var credentialOptions = root.putArray("llm.credentials");
        credentials.catalog().forEach((name, source) -> credentialOptions.add(
                NODES.objectNode().put("value", name).put("label", "%s (%s)".formatted(name, source))));

        var groupOptions = root.putArray("preset.groups");
        presets.groups().forEach(group ->
                groupOptions.add(NODES.objectNode().put("value", group).put("label", group)));
        return root;
    }
}
