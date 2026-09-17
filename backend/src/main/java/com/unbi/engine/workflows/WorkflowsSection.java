package com.unbi.engine.workflows;

import com.unbi.engine.settings.bundle.FileTreeSection;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The workflow library in a settings bundle: {@code workflows/<name>.unbi.json}, with the
 * favourites under {@code workflows/favorites/} — the same layout as the folder, so a bundle
 * unzipped by hand is a library.
 */
@Component
public class WorkflowsSection extends FileTreeSection {

    private final WorkflowStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowsSection(WorkflowStore store) {
        super("workflows", "Workflows", "Every workflow in the library, favourites included.", 40);
        this.store = store;
    }

    @Override
    protected Path root() {
        return store.root();
    }

    @Override
    protected boolean accepts(String relative) {
        var fileName = relative.startsWith(WorkflowStore.FAVORITES_DIRECTORY + "/")
                ? relative.substring(WorkflowStore.FAVORITES_DIRECTORY.length() + 1)
                : relative;
        if (fileName.contains("/") || !fileName.endsWith(StoredWorkflow.EXTENSION)) {
            return false;
        }
        try {
            StoredWorkflow.validName(fileName.substring(0, fileName.length() - StoredWorkflow.EXTENSION.length()));
            return true;
        } catch (IllegalArgumentException unusable) {
            return false;
        }
    }

    @Override
    protected void validate(String relative, byte[] content) {
        WorkflowStore.validDocument(mapper.readTree(content));
    }
}
