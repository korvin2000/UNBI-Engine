package com.unbi.engine.nodes.files;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.nodes.files.model.FileEdit;
import com.unbi.engine.nodes.files.model.FileRef;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/**
 * Search and replace across a list of files.
 *
 * <p><b>Dry run is on by default and that is not an accident.</b> This is the only node in the pack
 * that destroys information, and the cost of the two states is wildly asymmetric: a needless dry run
 * costs one click, while an unintended write costs a directory tree. The user turns writing on
 * deliberately, having already seen the counts a dry run reports.
 */
@Component
public class ReplaceInFilesNode implements NodeDefinition {

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("text.replace_in_files", "Replace In Files")
                .in("Text", "Processing")
                .icon("replace")
                .accent("rose")
                .describedAs("Replaces text across many files. Starts in dry-run mode, which reports "
                        + "what would change without writing anything.")
                .socket("files", "Files", FileTypes.FILE_LIST)
                .field("find", "Find", Types.TEXT, Widget.TextField.of("text to replace"), "")
                .field("replaceWith", "Replace With", Types.TEXT, Widget.TextField.of("replacement"), "")
                .setting("regex", "Regular Expression", Types.BOOLEAN, new Widget.Toggle(), false)
                .setting("caseSensitive", "Case Sensitive", Types.BOOLEAN, new Widget.Toggle(), true)
                .setting("dryRun", "Dry Run", Types.BOOLEAN, new Widget.Toggle(), true)
                .out("edits", "Edits", FileTypes.EDIT_LIST)
                .out("files", "Changed Files", FileTypes.FILE_LIST)
                .out("count", "Replacements", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) throws IOException {
        var files = context.listOf("files", FileRef.class);
        var find = context.text("find");
        if (find.isEmpty()) {
            throw new IllegalStateException("Enter the text to find");
        }
        var dryRun = context.flag("dryRun");
        var pattern = compile(find, context.flag("regex"), context.flag("caseSensitive"));
        // A literal replacement must not be reinterpreted: a Windows path in the replacement box
        // is full of backslashes, and $1 is a perfectly ordinary thing to want written out.
        var replacement = context.flag("regex")
                ? context.text("replaceWith")
                : Matcher.quoteReplacement(context.text("replaceWith"));

        var edits = new ArrayList<FileEdit>();
        var changed = new ArrayList<FileRef>();
        var total = 0;

        for (int index = 0; index < files.size(); index++) {
            context.checkCancelled();
            context.progress((double) index / Math.max(1, files.size()), null);

            var file = files.get(index);
            var content = TextFiles.read(file.toPath());
            if (content.isEmpty()) {
                continue;
            }
            var matcher = pattern.matcher(content.get());
            var rewritten = new StringBuilder();
            var occurrences = 0;
            while (matcher.find()) {
                matcher.appendReplacement(rewritten, replacement);
                occurrences++;
            }
            if (occurrences == 0) {
                continue;
            }
            matcher.appendTail(rewritten);

            if (!dryRun) {
                TextFiles.write(file.toPath(), rewritten.toString());
            }
            edits.add(new FileEdit(file.path(), occurrences, !dryRun));
            changed.add(file);
            total += occurrences;
        }

        var summary = "%d replacement%s in %d file%s%s".formatted(
                total, total == 1 ? "" : "s",
                changed.size(), changed.size() == 1 ? "" : "s",
                dryRun ? " (dry run, nothing written)" : "");
        context.log(summary);
        context.progress(1, summary);
        context.output("edits", List.copyOf(edits));
        context.output("files", List.copyOf(changed));
        context.output("count", (double) total);
    }

    private static Pattern compile(String find, boolean regex, boolean caseSensitive) {
        var flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (!regex) {
            return Pattern.compile(Pattern.quote(find), flags);
        }
        try {
            return Pattern.compile(find, flags);
        } catch (PatternSyntaxException invalid) {
            throw new IllegalStateException("Invalid regular expression: " + invalid.getDescription());
        }
    }
}
