package com.unbi.engine.nodes.files;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.nodes.files.model.FileRef;
import com.unbi.engine.nodes.files.model.TextMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/**
 * Finds a string, or a regular expression, across a list of files.
 *
 * <p>Reports one match per occurrence with a 1-based line and column, which is what an editor
 * expects and what makes the report node's output navigable.
 */
@Component
public class SearchInFilesNode implements NodeDefinition {

    /** Enough to be useful in a report, few enough that the browser stays responsive. */
    private static final int MAX_MATCHES = 20_000;

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("text.search_in_files", "Search In Files")
                .in("Text", "Processing")
                .icon("search")
                .accent("violet")
                .describedAs("Searches every file for a string or a regular expression.")
                .socket("files", "Files", FileTypes.FILE_LIST)
                .field("query", "Search For", Types.TEXT, Widget.TextField.of("text to find"), "")
                .setting("regex", "Regular Expression", Types.BOOLEAN, new Widget.Toggle(), false)
                .setting("caseSensitive", "Case Sensitive", Types.BOOLEAN, new Widget.Toggle(), false)
                .out("matches", "Matches", FileTypes.MATCH_LIST)
                .out("files", "Matched Files", FileTypes.FILE_LIST)
                .out("count", "Match Count", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var files = context.listOf("files", FileRef.class);
        var query = context.text("query");
        if (query.isEmpty()) {
            throw new IllegalStateException("Enter something to search for");
        }
        var pattern = compile(query, context.flag("regex"), context.flag("caseSensitive"));

        var matches = new ArrayList<TextMatch>();
        var matchedFiles = new ArrayList<FileRef>();
        var skipped = 0;

        for (int index = 0; index < files.size(); index++) {
            context.checkCancelled();
            context.progress((double) index / Math.max(1, files.size()), null);

            var file = files.get(index);
            var content = TextFiles.read(file.toPath());
            if (content.isEmpty()) {
                skipped++;
                continue;
            }
            var before = matches.size();
            collectMatches(file, content.get(), pattern, matches);
            if (matches.size() > before) {
                matchedFiles.add(file);
            }
            if (matches.size() >= MAX_MATCHES) {
                context.log("Stopped at " + MAX_MATCHES + " matches");
                break;
            }
        }

        if (skipped > 0) {
            context.log("Skipped " + skipped + " binary or unreadable files");
        }
        context.progress(1, matches.size() + " matches in " + matchedFiles.size() + " files");
        context.output("matches", List.copyOf(matches));
        context.output("files", List.copyOf(matchedFiles));
        context.output("count", (double) matches.size());
    }

    private static Pattern compile(String query, boolean regex, boolean caseSensitive) {
        var flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        if (!regex) {
            return Pattern.compile(Pattern.quote(query), flags);
        }
        try {
            return Pattern.compile(query, flags);
        } catch (PatternSyntaxException invalid) {
            // The raw message spans three lines with a caret diagram; useless in a node badge.
            throw new IllegalStateException("Invalid regular expression: " + invalid.getDescription());
        }
    }

    /**
     * Walks matches once, tracking line starts as it goes.
     *
     * <p>Deriving line and column by counting newlines from the beginning per match would be
     * quadratic on a large file with many hits; this stays linear because {@code Matcher} only ever
     * moves forward.
     */
    private static void collectMatches(FileRef file, String content, Pattern pattern, List<TextMatch> into) {
        Matcher matcher = pattern.matcher(content);
        int line = 1;
        int lineStart = 0;
        int scanned = 0;

        while (matcher.find()) {
            var start = matcher.start();
            while (scanned < start) {
                if (content.charAt(scanned) == '\n') {
                    line++;
                    lineStart = scanned + 1;
                }
                scanned++;
            }
            var lineEnd = content.indexOf('\n', lineStart);
            var text = content.substring(lineStart, lineEnd < 0 ? content.length() : lineEnd);
            into.add(new TextMatch(file.path(), line, start - lineStart + 1, text.strip()));

            if (into.size() >= MAX_MATCHES) {
                return;
            }
            // A zero-width match would otherwise spin forever on the same offset.
            if (matcher.end() == matcher.start() && matcher.end() >= content.length()) {
                return;
            }
        }
    }
}
