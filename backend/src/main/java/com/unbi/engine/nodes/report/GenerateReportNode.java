package com.unbi.engine.nodes.report;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.PortType;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.nodes.util.ValueRendering;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import org.springframework.stereotype.Component;

/**
 * Turns the result of a run into a document.
 *
 * <p>The terminal node of the batch-processing use case. Accepts {@code Any} so it can summarise a
 * file list, a set of matches or a set of edits without three separate report nodes.
 */
@Component
public class GenerateReportNode implements NodeDefinition {

    private static final PortType DIRECTORY = PortType.primitive("Directory");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("report.generate", "Generate Report")
                .in("Report", "Output")
                .icon("document")
                .accent("emerald")
                .describedAs("Summarises results as Markdown, CSV or plain text, and optionally "
                        + "writes them to a file.")
                .socket("data", "Data", Types.ANY)
                .field("title", "Title", Types.TEXT, Widget.TextField.of("Processing Report"),
                        "Processing Report")
                .setting("format", "Format", Types.TEXT, Widget.Dropdown.of(
                        "markdown", "Markdown", "csv", "CSV", "text", "Plain Text"), "markdown")
                // A field rather than a socket-only input: a report node that can only be told
                // where to write by wiring a second node into it is a report node nobody uses.
                .field("outputDirectory", "Output Directory", DIRECTORY, new Widget.DirectoryPicker(), "")
                .setting("fileName", "File Name", Types.TEXT, Widget.TextField.of("report.md"), "report.md")
                .out("report", "Report", Types.TEXT)
                .out("path", "Written To", Types.TEXT)
                .build();
    }

    @Override
    public void execute(NodeContext context) throws IOException {
        var data = context.rawInput("data");
        var format = context.text("format");
        var body = switch (format) {
            case "csv" -> ValueRendering.toCsv(data);
            case "text" -> ValueRendering.toPlainText(data);
            default -> markdownDocument(context.text("title"), data);
        };

        context.output("report", body);
        context.output("path", writeIfRequested(context, body));
        context.progress(1, ValueRendering.describe(data));
    }

    private String markdownDocument(String title, Object data) {
        var out = new StringBuilder();
        out.append("# ").append(title.isBlank() ? "Processing Report" : title).append("\n\n");
        out.append("_Generated ").append(ZonedDateTime.now().format(STAMP)).append("_\n\n");
        if (data instanceof Collection<?> items) {
            out.append("**").append(items.size()).append("** ")
                    .append(items.size() == 1 ? "result" : "results").append("\n\n");
        }
        out.append(ValueRendering.toMarkdown(data)).append('\n');
        return out.toString();
    }

    /**
     * @return the file written, or an explanatory placeholder — the port is declared, so it must
     *     always carry something rather than leaving the downstream node with a null
     */
    private String writeIfRequested(NodeContext context, String body) throws IOException {
        var directory = context.optional("outputDirectory", String.class).orElse("").trim();
        if (directory.isEmpty()) {
            return "(not written)";
        }
        var fileName = context.text("fileName").isBlank() ? "report.md" : context.text("fileName").trim();
        var target = Path.of(directory).resolve(fileName).normalize();
        // resolve() on an absolute or traversing file name would escape the chosen directory.
        if (!target.startsWith(Path.of(directory).normalize())) {
            throw new IllegalStateException("File name must stay inside the output folder: " + fileName);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, body, StandardCharsets.UTF_8);
        context.log("Wrote " + target);
        return target.toString();
    }
}
