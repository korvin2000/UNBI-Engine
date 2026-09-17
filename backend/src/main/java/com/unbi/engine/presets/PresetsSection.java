package com.unbi.engine.presets;

import com.unbi.engine.config.DataDirectory;
import com.unbi.engine.settings.bundle.FileTreeSection;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Presets — and therefore saved prompts — in a settings bundle: {@code presets/<id>.json}. */
@Component
public class PresetsSection extends FileTreeSection {

    private static final Pattern FILE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,80}\\.json");

    private final DataDirectory data;
    private final ObjectMapper mapper = new ObjectMapper();

    public PresetsSection(DataDirectory data) {
        super("presets", "Node presets & saved prompts",
                "Saved node configurations, including every prompt saved from the prompt editor.", 30);
        this.data = data;
    }

    @Override
    protected Path root() {
        return data.file("presets");
    }

    @Override
    protected boolean accepts(String relative) {
        return FILE.matcher(relative).matches();
    }

    @Override
    protected void validate(String relative, byte[] content) {
        var root = mapper.readTree(content);
        if (!root.isObject() || !root.path("nodeType").isString() || !root.path("name").isString()) {
            throw new IllegalArgumentException("not a preset");
        }
    }
}
