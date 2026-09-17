package com.unbi.engine.profiles;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.settings.bundle.FileTreeSection;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Profiles in a settings bundle: {@code profiles/<schema>/<id>.json}, exactly as they sit on disk.
 *
 * <p>Safe to export without a password by construction — a profile holds a credential's
 * <em>name</em>, never its value — which is why this section is not marked sensitive and the
 * credentials section is.
 */
@Component
public class ProfilesSection extends FileTreeSection {

    private static final Pattern FILE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,80}/[a-z0-9][a-z0-9._-]{0,80}\\.json");

    private final DataDirectory data;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProfilesSection(DataDirectory data) {
        super("profiles", "LLM endpoints & gateways",
                "Endpoint profiles: gateway kind, base URL, credential names, pacing. Never a key.", 10);
        this.data = data;
    }

    @Override
    protected Path root() {
        return data.file("profiles");
    }

    @Override
    protected boolean accepts(String relative) {
        return FILE.matcher(relative).matches();
    }

    @Override
    protected void validate(String relative, byte[] content) {
        var root = mapper.readTree(content);
        if (!root.isObject() || !root.path("name").isString()) {
            throw new IllegalArgumentException("not a profile");
        }
    }
}
