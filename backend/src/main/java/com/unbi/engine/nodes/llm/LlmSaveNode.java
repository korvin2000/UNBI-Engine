package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.prompt.PromptTemplate;
import com.unbi.engine.nodes.files.FileTypes;
import com.unbi.engine.nodes.files.model.FileRef;
import com.unbi.engine.nodes.llm.model.LlmResult;
import com.unbi.engine.nodes.util.ValueRendering;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Writes answers to disk, and hands the files back to the file pack.
 *
 * <p>Outputting {@code FileRef} rather than a path string is what closes the loop: a saved batch can
 * be filtered, searched or reported on by nodes that were written before this pack existed.
 *
 * <p>A list in means one file per item, which is what a batch of answers always wants. The file name
 * is a template for the same reason — a hundred answers into one file is not a saved batch.
 */
@Component
public class LlmSaveNode implements NodeDefinition {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Illegal in a Windows path, and a mess in a POSIX one. */
    private static final String UNSAFE = "[\\\\/:*?\"<>|\\p{Cntrl}]";

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.save", "Save Result")
                .in(LlmTypes.CATEGORY, "Result")
                .icon("save")
                .accent(LlmTypes.ACCENT)
                .describedAs("Writes an answer — or a whole batch of them — to files the rest of "
                        + "the graph can keep working with.")
                .socket("value", "Value", Types.ANY)
                .field("directory", "Directory", FileTypes.DIRECTORY, new Widget.DirectoryPicker(), "")
                .setting("fileName", "File Name", Types.TEXT,
                        Widget.TextField.of("answer-{{index}}.md"), "answer-{{index}}.md")
                .hint("{{index}} {{stamp}} {{name}} are bound; {{name}} comes from a file list "
                        + "wired into Names.")
                .optionalSocket("names", "Names", FileTypes.FILE_LIST)
                .hint("Names each answer after the file it came from, position by position.")
                .advancedSetting("overwrite", "Overwrite Existing", Types.BOOLEAN, new Widget.Toggle(), false)
                .hint("Off adds a numeric suffix instead, so a second run cannot erase the first.")
                .out("files", "Files", FileTypes.FILE_LIST)
                .out("count", "Count", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) throws IOException {
        var directory = context.text("directory").trim();
        if (directory.isEmpty()) {
            throw new IllegalStateException("This node needs a directory to write into.");
        }
        var root = Path.of(directory).normalize();
        Files.createDirectories(root);

        var items = itemsOf(context.rawInput("value"));
        var names = context.rawInput("names") == null
                ? List.<FileRef>of()
                : context.listOf("names", FileRef.class);
        var template = context.text("fileName").isBlank() ? "answer-{{index}}.md" : context.text("fileName");
        var overwrite = context.flag("overwrite");
        var stamp = LocalDateTime.now().format(STAMP);

        var written = new ArrayList<FileRef>(items.size());
        for (int index = 0; index < items.size(); index++) {
            context.checkCancelled();
            var body = render(items.get(index));
            var target = resolve(root, fileNameFor(template, index, items.size(), stamp, names), overwrite);
            Files.writeString(target, body, StandardCharsets.UTF_8);
            written.add(FileRef.of(target, Files.size(target)));
            context.progress((index + 1d) / items.size(), target.getFileName().toString());
        }

        context.log("Wrote %d file%s to %s".formatted(
                written.size(), written.size() == 1 ? "" : "s", root));
        context.output("files", List.copyOf(written));
        context.output("count", (double) written.size());
    }

    private static String fileNameFor(
            String template, int index, int total, String stamp, List<FileRef> names) {

        var bindings = new LinkedHashMap<String, Object>();
        // One-based, and zero-padded to the width of the batch so the files sort the way they ran.
        var width = String.valueOf(total).length();
        bindings.put("index", ("%0" + width + "d").formatted(index + 1));
        bindings.put("stamp", stamp);
        bindings.put("count", total);
        if (index < names.size()) {
            bindings.put("name", stripExtension(names.get(index).name()));
            bindings.put("fullName", names.get(index).name());
        }
        var rendered = PromptTemplate.render(template, bindings, false).trim();
        // A template that resolved to nothing, or to a path, would write outside the chosen folder.
        var safe = rendered.replaceAll(UNSAFE, "_");
        return safe.isBlank() ? "answer-%d".formatted(index + 1) : safe;
    }

    /**
     * Resolves the target, never outside {@code root} and never over an existing file unless asked.
     *
     * <p>Both halves matter. This node is pointed at a folder by a user who is watching a model
     * produce text, and the two ways to lose work here are writing somewhere unexpected and writing
     * over yesterday's run.
     */
    private static Path resolve(Path root, String fileName, boolean overwrite) {
        var target = root.resolve(fileName).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalStateException("The file name must stay inside the folder: " + fileName);
        }
        if (overwrite || !Files.exists(target)) {
            return target;
        }
        var base = stripExtension(fileName);
        var extension = fileName.length() > base.length() ? fileName.substring(base.length()) : "";
        for (int suffix = 2; suffix < 1000; suffix++) {
            var candidate = root.resolve("%s-%d%s".formatted(base, suffix, extension));
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Too many files named like " + fileName);
    }

    /** A result renders as its text; anything else falls back to the shared renderer. */
    private static String render(Object item) {
        return switch (item) {
            case null -> "";
            case LlmResult result -> result.text();
            case String text -> text;
            default -> ValueRendering.toPlainText(item);
        };
    }

    private static List<Object> itemsOf(Object value) {
        return switch (value) {
            case null -> throw new IllegalStateException("Nothing is wired into Value.");
            case Collection<?> items -> {
                if (items.isEmpty()) {
                    throw new IllegalStateException("The value wired in is an empty list.");
                }
                yield List.copyOf(items);
            }
            default -> List.of(value);
        };
    }

    private static String stripExtension(String name) {
        var dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
