package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.nodes.files.FileTypes;
import com.unbi.engine.nodes.files.model.FileRef;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns files into things a model can actually read.
 *
 * <p>The bridge between the file pack and this one: Scan Directory → Filter Files → Attach → LLM
 * Request is the whole of "run a prompt over a folder of documents", and none of those four nodes
 * had to learn about the others.
 *
 * <p>The size limits are not decoration. Attachments are base64-encoded into the request body, so a
 * folder of scans becomes a multi-megabyte POST that a gateway will reject in a way that reads like
 * an outage. Failing here, naming the file and the limit, is the difference.
 */
@Component
public class LlmAttachNode implements NodeDefinition {

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.attach", "Attach Files")
                .in(LlmTypes.CATEGORY, "Prompt")
                .icon("paperclip")
                .accent(LlmTypes.ACCENT)
                .describedAs("Reads files into prompt attachments: images inline, documents as "
                        + "files, and text as text.")
                .socket("files", "Files", FileTypes.FILE_LIST)
                .setting("textAsText", "Send Text Files As Text", Types.BOOLEAN,
                        new Widget.Toggle(), true)
                .hint("Cheaper than a file part, and understood by every model rather than only "
                        + "those declaring file input.")
                .advancedSetting("maxFileKb", "Max File Size", Types.NUMBER,
                        new Widget.NumberField(1, 100_000, 64, "KB", false), 8192d)
                .advancedSetting("maxTotalMb", "Max Total Size", Types.NUMBER,
                        new Widget.NumberField(1, 512, 1, "MB", false), 32d)
                .hint("Attachments are base64-encoded into the request body, so this is the real "
                        + "ceiling on what one call can carry.")
                .advancedSetting("skipOversized", "Skip Oversized Files", Types.BOOLEAN,
                        new Widget.Toggle(), false)
                .hint("Off means an oversized file fails the node rather than quietly never "
                        + "reaching the model.")
                .out("attachments", "Attachments", LlmTypes.ATTACHMENT_LIST)
                .out("count", "Count", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) throws IOException {
        var files = context.listOf("files", FileRef.class);
        var maxFileBytes = (long) (context.number("maxFileKb") * 1024);
        var maxTotalBytes = (long) (context.number("maxTotalMb") * 1024 * 1024);
        var textAsText = context.flag("textAsText");
        var skipOversized = context.flag("skipOversized");

        var attachments = new ArrayList<Attachment>(files.size());
        var totalBytes = 0L;
        var skipped = 0;

        for (int index = 0; index < files.size(); index++) {
            context.checkCancelled();
            var file = files.get(index);
            var size = Files.isReadable(file.toPath()) ? Files.size(file.toPath()) : -1;
            if (size < 0) {
                throw new IllegalStateException("Cannot read " + file.path());
            }
            if (size > maxFileBytes) {
                if (!skipOversized) {
                    throw new IllegalStateException("%s is %s, over the %s limit.".formatted(
                            file.name(),
                            AttachmentReader.humanSize(size),
                            AttachmentReader.humanSize(maxFileBytes)));
                }
                context.log("Skipped %s (%s)".formatted(file.name(), AttachmentReader.humanSize(size)));
                skipped++;
                continue;
            }
            totalBytes += size;
            if (totalBytes > maxTotalBytes) {
                throw new IllegalStateException(
                        "Attachments passed the %s total limit at %s. Filter the list, or raise the limit."
                                .formatted(AttachmentReader.humanSize(maxTotalBytes), file.name()));
            }
            attachments.add(AttachmentReader.read(file, textAsText));
            context.progress((index + 1d) / Math.max(1, files.size()), file.name());
        }

        context.log("%d attachment%s, %s%s".formatted(
                attachments.size(),
                attachments.size() == 1 ? "" : "s",
                AttachmentReader.humanSize(totalBytes),
                skipped == 0 ? "" : " (%d skipped)".formatted(skipped)));
        context.output("attachments", List.copyOf(attachments));
        context.output("count", (double) attachments.size());
    }
}
