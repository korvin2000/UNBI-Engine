package com.unbi.engine.nodes.files;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.PortType;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.json.JsonValues;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads one file into a list of items — the thing a batch iterates over.
 *
 * <p>JSON arrays, JSON Lines, CSV and plain lines all become the same shape: a list whose entries
 * are records (maps) or strings. Downstream, a record's columns are readable from a prompt template
 * by name, so a spreadsheet of questions becomes one request per row with no node in between.
 *
 * <p>Format is sniffed from the extension and then from the content, and can be pinned. Sniffing is
 * only a first guess — a {@code .txt} holding JSON is a normal thing to have.
 */
@Component
public class LoadDatasetNode implements NodeDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A file this large is not a dataset for a batch of LLM requests; it is a different problem. */
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("io.load_dataset", "Load Dataset")
                .in("Files", "Input & Output")
                .icon("table")
                .accent("amber")
                .describedAs("Reads a JSON, JSON Lines, CSV or plain text file into a list of items, "
                        + "one per record or line, ready to iterate over.")
                .setting("path", "File", Types.TEXT,
                        new Widget.FilePicker(List.of("json", "jsonl", "ndjson", "csv", "tsv", "txt", "md")), "")
                .setting("format", "Format", Types.TEXT, Widget.Dropdown.of(
                        "auto", "Detect",
                        "json", "JSON array",
                        "jsonl", "JSON Lines",
                        "csv", "CSV / TSV",
                        "lines", "One item per line"), "auto")
                .advancedSetting("header", "First Row Is A Header", Types.BOOLEAN, new Widget.Toggle(), true)
                .hint("For CSV: named columns become {{column}} in a prompt. Off gives each row as a "
                        + "list of cells.")
                .advancedSetting("limit", "Limit", Types.NUMBER,
                        Widget.NumberField.optional(1, 1_000_000, 1, "", "all"), null)
                .hint("Only the first so many items — for trying a prompt before running everything.")
                .out("items", "Items", PortType.list(Types.ANY))
                .out("count", "Count", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) throws Exception {
        var raw = context.text("path").trim();
        if (raw.isEmpty()) {
            throw new IllegalStateException("This node needs a file to read.");
        }
        var path = Path.of(raw);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("There is no file at " + path);
        }
        if (Files.size(path) > MAX_BYTES) {
            throw new IllegalStateException("%s is over the %d MB limit for a dataset."
                    .formatted(path.getFileName(), MAX_BYTES / (1024 * 1024)));
        }
        var text = TextFiles.read(path).orElseThrow(() -> new IllegalStateException(
                path.getFileName() + " is not a text file this node can read."));

        var format = detect(context.text("format"), path, text);
        var items = switch (format) {
            case "json" -> json(text, path);
            case "jsonl" -> jsonl(text);
            case "csv" -> Csv.parse(text, context.flag("header"));
            default -> lines(text);
        };

        var limit = context.rawInput("limit") instanceof Number number ? number.intValue() : 0;
        if (limit > 0 && items.size() > limit) {
            context.log("Limited to the first %d of %d.".formatted(limit, items.size()));
            items = items.subList(0, limit);
        }
        var kind = items.isEmpty() ? "" : items.getFirst() instanceof java.util.Map<?, ?> ? " records" : " values";
        context.log("%s: %d%s (%s)".formatted(path.getFileName(), items.size(), kind, format));
        context.output("items", List.copyOf(items));
        context.output("count", (double) items.size());
        context.progress(1, items.size() + " items");
    }

    /** The extension is the first guess and the first byte the second; a pinned format overrides both. */
    private static String detect(String chosen, Path path, String text) {
        if (!chosen.isBlank() && !"auto".equals(chosen)) {
            return chosen;
        }
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jsonl") || name.endsWith(".ndjson")) {
            return "jsonl";
        }
        if (name.endsWith(".csv") || name.endsWith(".tsv")) {
            return "csv";
        }
        var head = text.stripLeading();
        if (name.endsWith(".json") || head.startsWith("[")) {
            return "json";
        }
        if (head.startsWith("{")) {
            // A file of objects one per line is JSON Lines; a single object is a JSON document.
            return text.strip().lines().count() > 1 && text.strip().lines().allMatch(line -> line.isBlank() || line.stripLeading().startsWith("{"))
                    ? "jsonl"
                    : "json";
        }
        return "lines";
    }

    private static List<Object> json(String text, Path path) {
        Object parsed;
        try {
            parsed = JsonValues.from(MAPPER.readTree(text));
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("%s is not valid JSON: %s".formatted(path.getFileName(), malformed.getMessage()));
        }
        if (parsed instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (parsed instanceof java.util.Map<?, ?> object) {
            // {"items": [...]} and friends: the first array-valued property is the dataset.
            for (var value : object.values()) {
                if (value instanceof List<?> list) {
                    return new ArrayList<>(list);
                }
            }
            return new ArrayList<>(List.of(object));
        }
        return parsed == null ? new ArrayList<>() : new ArrayList<>(List.of(parsed));
    }

    private static List<Object> jsonl(String text) {
        var items = new ArrayList<Object>();
        var number = 0;
        for (var line : text.lines().toList()) {
            number++;
            if (line.isBlank()) {
                continue;
            }
            try {
                items.add(JsonValues.from(MAPPER.readTree(line)));
            } catch (RuntimeException malformed) {
                throw new IllegalStateException("Line %d is not valid JSON: %s".formatted(number, malformed.getMessage()));
            }
        }
        return items;
    }

    private static List<Object> lines(String text) {
        return text.lines().map(String::strip).filter(line -> !line.isEmpty()).map(Object.class::cast).toList();
    }

    /**
     * A small CSV reader: quoted fields, doubled quotes, embedded newlines, and a delimiter sniffed
     * from the header line. Small on purpose — a dataset for a batch is hundreds of rows, not a
     * data-warehouse export, and a dependency for this would be the wrong trade.
     */
    static final class Csv {

        private Csv() {}

        static List<Object> parse(String text, boolean header) {
            var delimiter = sniffDelimiter(text);
            var rows = rows(text, delimiter);
            if (rows.isEmpty()) {
                return List.of();
            }
            if (!header) {
                return rows.stream().map(row -> (Object) List.copyOf(row)).toList();
            }
            var columns = rows.getFirst().stream().map(String::strip).toList();
            var records = new ArrayList<Object>(rows.size() - 1);
            for (var row : rows.subList(1, rows.size())) {
                var record = new LinkedHashMap<String, Object>();
                for (int column = 0; column < columns.size(); column++) {
                    var name = columns.get(column).isEmpty() ? "column" + (column + 1) : columns.get(column);
                    record.put(name, column < row.size() ? row.get(column) : "");
                }
                records.add(java.util.Collections.unmodifiableMap(record));
            }
            return records;
        }

        private static char sniffDelimiter(String text) {
            var firstLine = text.lines().findFirst().orElse("");
            var best = ',';
            var bestCount = -1;
            for (var candidate : new char[] {',', ';', '\t', '|'}) {
                var count = firstLine.chars().filter(c -> c == candidate).count();
                if (count > bestCount) {
                    best = candidate;
                    bestCount = (int) count;
                }
            }
            return best;
        }

        private static List<List<String>> rows(String text, char delimiter) {
            var rows = new ArrayList<List<String>>();
            var row = new ArrayList<String>();
            var cell = new StringBuilder();
            var quoted = false;
            for (int i = 0; i < text.length(); i++) {
                var c = text.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                            cell.append('"');
                            i++;
                        } else {
                            quoted = false;
                        }
                    } else {
                        cell.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == delimiter) {
                    row.add(cell.toString());
                    cell.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    row.add(cell.toString());
                    cell.setLength(0);
                    if (row.stream().anyMatch(value -> !value.isBlank())) {
                        rows.add(row);
                    }
                    row = new ArrayList<>();
                } else {
                    cell.append(c);
                }
            }
            if (!cell.isEmpty() || !row.isEmpty()) {
                row.add(cell.toString());
                if (row.stream().anyMatch(value -> !value.isBlank())) {
                    rows.add(row);
                }
            }
            return rows;
        }
    }
}
