package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.json.JsonValues;
import com.unbi.engine.nodes.llm.model.LlmResult;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the JSON a model wrote, including when it wrapped it in prose.
 *
 * <p>Fence-stripping is not a nicety. Even a model in JSON mode will occasionally open with
 * <code>```json</code>, and a model merely <em>asked</em> for JSON does it most of the time — which
 * is exactly the case that matters, because web search and JSON mode cannot be combined on the
 * gateways this pack talks to, so the search path always asks in the prompt.
 *
 * <p>The parsed value leaves as {@code Any}: it is a map, a list or a scalar depending on what the
 * model said, and claiming otherwise in the type system would be a lie the next node pays for.
 */
@Component
public class LlmParseJsonNode implements NodeDefinition {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A fenced block, with or without a language tag, anywhere in the answer. */
    private static final Pattern FENCE = Pattern.compile(
            "```[a-zA-Z0-9_+-]*\\s*\\R(.*?)\\R?\\s*```", Pattern.DOTALL);

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.parse_json", "Parse JSON")
                .in(LlmTypes.CATEGORY, "Result")
                .icon("braces")
                .accent(LlmTypes.ACCENT)
                .describedAs("Parses a JSON answer, stripping any code fence, and optionally "
                        + "reaching into it by path.")
                .socket("value", "Value", Types.ANY)
                .hint("An LLM Result or plain text.")
                .setting("path", "Path", Types.TEXT, Widget.TextField.of("data.items"), "")
                .hint("Dotted path into the parsed value; blank returns all of it. "
                        + "A number selects a list element.")
                .setting("strict", "Fail On Bad JSON", Types.BOOLEAN, new Widget.Toggle(), true)
                .hint("Off passes the raw text through with Parsed off, so a graph can branch "
                        + "on it instead of stopping.")
                .out("value", "Value", Types.ANY)
                .out("text", "Text", Types.TEXT)
                .out("parsed", "Parsed", Types.BOOLEAN)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var source = textOf(context.rawInput("value"));
        var strict = context.flag("strict");

        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(unfence(source));
        } catch (RuntimeException malformed) {
            if (strict) {
                throw new IllegalStateException(
                        "The answer is not JSON: %s. First 200 characters: %s"
                                .formatted(malformed.getMessage(), preview(source)));
            }
            context.log("Not JSON; passing the text through unchanged.");
            context.output("value", source);
            context.output("text", source);
            context.output("parsed", false);
            context.progress(1, "not JSON");
            return;
        }

        var path = context.text("path").trim();
        var selected = path.isEmpty() ? parsed : select(parsed, path, strict, context);
        if (selected == null) {
            context.output("value", source);
            context.output("text", source);
            context.output("parsed", false);
            context.progress(1, "path not found");
            return;
        }

        context.output("value", JsonValues.from(selected));
        context.output("text", selected.toPrettyString());
        context.output("parsed", true);
        context.progress(1, describe(selected));
    }

    /**
     * @return the node at the path, or null when it is not there and strictness allows carrying on
     */
    private static JsonNode select(JsonNode root, String path, boolean strict, NodeContext context) {
        var current = root;
        for (var segment : path.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            var next = segment.chars().allMatch(Character::isDigit) && current.isArray()
                    ? current.path(Integer.parseInt(segment))
                    : current.path(segment);
            if (next.isMissingNode()) {
                if (strict) {
                    throw new IllegalStateException(
                            "The answer has nothing at '%s'. It contains: %s"
                                    .formatted(path, fieldsOf(root)));
                }
                context.log("Nothing at '%s'.".formatted(path));
                return null;
            }
            current = next;
        }
        return current;
    }

    /** Accepts a result, a plain string, or anything else worth trying to read. */
    private static String textOf(Object value) {
        return switch (value) {
            case null -> throw new IllegalStateException("Nothing is wired into Value.");
            case LlmResult result -> result.text();
            case String text -> text;
            default -> String.valueOf(value);
        };
    }


    /** The first fenced block if there is one, otherwise the text as it came. */
    static String unfence(String text) {
        var matcher = FENCE.matcher(text);
        return matcher.find() ? matcher.group(1) : text.strip();
    }

    private static String fieldsOf(JsonNode root) {
        if (!root.isObject()) {
            return root.isArray() ? "a list of %d".formatted(root.size()) : "a single value";
        }
        var names = new java.util.ArrayList<String>();
        root.propertyNames().forEach(names::add);
        return names.isEmpty() ? "nothing" : String.join(", ", names);
    }

    private static String describe(JsonNode node) {
        if (node.isArray()) {
            return node.size() + (node.size() == 1 ? " item" : " items");
        }
        return node.isObject() ? fieldsOf(node) : node.asString("value");
    }

    private static String preview(String source) {
        var trimmed = source.strip().replace('\n', ' ');
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }
}
