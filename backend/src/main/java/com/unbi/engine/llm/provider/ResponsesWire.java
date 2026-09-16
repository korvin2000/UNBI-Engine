package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatMessage;
import com.unbi.engine.llm.spec.ChatResult;
import com.unbi.engine.llm.spec.FinishReason;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.Reasoning;
import com.unbi.engine.llm.spec.ResponseFormat;
import com.unbi.engine.llm.spec.SamplingParams;
import com.unbi.engine.llm.spec.TokenUsage;
import com.unbi.engine.llm.spec.WebSearchMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Responses dialect.
 *
 * <p>Carried alongside Chat Completions rather than mapped onto it because the two differ where it
 * matters: hosted web search and its verifiable evidence exist only here, the Codex endpoint speaks
 * only this, and the terminal states are not the same set. Pretending they are one format is how a
 * truncated answer gets billed as zero tokens and classified as a parse error.
 *
 * <p>Pure functions, for the same reason as {@link ChatWire}.
 */
public final class ResponsesWire {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** Terminal events. {@code incomplete} is the one that carries a truncated answer's usage. */
    private static final Set<String> TERMINAL_EVENTS =
            Set.of("response.completed", "response.incomplete", "response.failed");

    private ResponsesWire() {}

    // --- Request ------------------------------------------------------------

    public static ObjectNode request(ChatCall call, boolean stream) {
        var model = call.model();
        var body = NODES.objectNode();
        body.put("model", model.name());

        var input = body.putArray("input");
        call.messages().stream()
                .filter(message -> !message.isEmpty())
                .forEach(message -> input.add(message(message, model.endpoint().responsesPromptCache())));

        body.put("stream", stream);
        // Nothing here needs the provider to retain a conversation, and storing one is a privacy
        // decision this pack has no business taking on a user's behalf.
        body.put("store", false);
        var outputLimit = call.effectiveMaxOutputTokens();
        if (outputLimit > 0 && model.outputLimitField().isPresent()) {
            body.put("max_output_tokens", outputLimit);
        }

        sampling(body, call.sampling());

        var format = textFormat(call.responseFormat(), model);
        if (format != null) {
            body.set("text", NODES.objectNode().set("format", format));
        }
        var reasoning = reasoning(model.reasoning());
        if (reasoning != null) {
            body.set("reasoning", reasoning);
        }
        if (call.webSearch().enabled() && model.webSearchMode() == WebSearchMode.RESPONSES_TOOL) {
            var tool = NODES.objectNode().put("type", "web_search");
            if (!call.webSearch().contextSize().isBlank()) {
                tool.put("search_context_size", call.webSearch().contextSize());
            }
            body.putArray("tools").add(tool);
            body.put("tool_choice", call.webSearch().required() ? "required" : "auto");
            // Without this the search call comes back with no sources, and "did it search?" becomes
            // unanswerable from the response alone.
            body.putArray("include").add("web_search_call.action.sources");
        }
        if (model.endpoint().responsesPromptCache() && !call.cacheKey().isBlank()) {
            body.put("prompt_cache_key", call.cacheKey());
        }
        var routing = ChatWire.provider(model.routing());
        if (routing != null) {
            body.set("provider", routing);
        }
        model.extraBody().forEach((key, value) -> body.set(key, ChatWire.JsonValue.of(value)));
        return body;
    }

    public static ObjectNode message(ChatMessage message, boolean explicitCacheControls) {
        var node = NODES.objectNode();
        // Responses renames the system role and rejects the old spelling.
        node.put("role", message.role() == ChatMessage.Role.SYSTEM ? "developer" : message.role().wireName());
        var content = node.putArray("content");
        if (!message.text().isBlank()) {
            var text = NODES.objectNode().put("type", "input_text").put("text", message.text());
            if (explicitCacheControls && message.cacheBreakpoint()) {
                text.set("prompt_cache_breakpoint", NODES.objectNode().put("mode", "explicit"));
            }
            content.add(text);
        }
        message.attachments().forEach(attachment -> content.add(part(attachment)));
        return node;
    }

    private static ObjectNode part(Attachment attachment) {
        return switch (attachment.kind()) {
            case TEXT -> NODES.objectNode()
                    .put("type", "input_text")
                    .put("text", "%s:\n%s".formatted(attachment.name(), attachment.text()));
            case IMAGE -> NODES.objectNode()
                    .put("type", "input_image")
                    .put("image_url", attachment.dataUrl());
            case DOCUMENT -> NODES.objectNode()
                    .put("type", "input_file")
                    .put("filename", attachment.name())
                    .put("file_data", attachment.dataUrl());
        };
    }

    /**
     * Only the samplers this API accepts.
     *
     * <p>{@code top_k} and {@code min_p} are deliberately absent: Responses rejects them outright
     * rather than ignoring them, so sending them would turn a working target into a 400 every time
     * someone filled in a field that looked available.
     */
    public static void sampling(ObjectNode body, SamplingParams params) {
        if (params.temperature() != null) {
            body.put("temperature", params.temperature());
        }
        if (params.topP() != null) {
            body.put("top_p", params.topP());
        }
    }

    public static ObjectNode textFormat(ResponseFormat format, ModelSpec model) {
        return switch (format) {
            case ResponseFormat.Text ignored -> null;
            case ResponseFormat.JsonObject ignored -> model.can(Capability.JSON_OBJECT)
                    ? NODES.objectNode().put("type", "json_object")
                    : null;
            case ResponseFormat.JsonSchema schema -> {
                if (!model.can(Capability.JSON_SCHEMA)) {
                    yield null;
                }
                var node = NODES.objectNode()
                        .put("type", "json_schema")
                        .put("name", schema.name())
                        .put("strict", schema.strict());
                node.set("schema", ChatWire.parseSchema(schema.schema()));
                yield node;
            }
        };
    }

    /**
     * The Responses spelling of the same reasoning intent — but only for the dialects that have one.
     *
     * <p>Budgeted thinking has no analogue here, and emitting an effort in its place would look like
     * it worked while asking for something else. A target that needs it says so through its extra
     * body, where what goes on the wire is visible in the node.
     */
    public static ObjectNode reasoning(Reasoning reasoning) {
        if (reasoning.dialect() != Reasoning.Dialect.REASONING_EFFORT
                && reasoning.dialect() != Reasoning.Dialect.REASONING) {
            return null;
        }
        if (!reasoning.enabled()) {
            return NODES.objectNode().put("effort", "none");
        }
        var node = NODES.objectNode().put("effort", reasoning.effort().wireName());
        if (reasoning.dialect() == Reasoning.Dialect.REASONING) {
            if (reasoning.maxTokens() > 0) {
                node.put("max_tokens", reasoning.maxTokens());
            }
            node.put("exclude", reasoning.exclude());
        }
        return node;
    }

    // --- Response -----------------------------------------------------------

    public static ChatResult parse(JsonNode response, long latencyMillis) {
        var searchCalls = outputsOfType(response, "web_search_call");
        return new ChatResult(
                text(response),
                finishReason(response),
                usage(response.path("usage")),
                response.path("model").asString(""),
                latencyMillis,
                response.path("usage").path("cost").asDouble(-1),
                searchCalls.stream().anyMatch(ResponsesWire::isCompleted),
                sources(response, searchCalls));
    }

    public static String text(JsonNode response) {
        var out = new StringBuilder();
        response.path("output").forEach(item -> item.path("content").forEach(content -> {
            var type = content.path("type").asString("output_text");
            if (type.equals("output_text") || type.equals("text")) {
                out.append(content.path("text").asString(""));
            }
        }));
        return out.toString();
    }

    public static FinishReason finishReason(JsonNode response) {
        if ("completed".equals(response.path("status").asString(""))) {
            return FinishReason.STOP;
        }
        var reason = response.path("incomplete_details").path("reason").asString("");
        if (reason.contains("max_output_tokens") || reason.contains("length")) {
            return FinishReason.LENGTH;
        }
        if (reason.contains("content_filter") || reason.contains("safety")) {
            return FinishReason.CONTENT_FILTER;
        }
        return FinishReason.UNKNOWN;
    }

    public static TokenUsage usage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return TokenUsage.NONE;
        }
        var input = usage.path("input_tokens").asLong(0);
        var output = usage.path("output_tokens").asLong(0);
        return new TokenUsage(
                input,
                output,
                usage.path("input_tokens_details").path("cached_tokens").asLong(0),
                usage.path("output_tokens_details").path("reasoning_tokens").asLong(0),
                usage.path("total_tokens").asLong(input + output));
    }

    public static List<ChatResult.Source> sources(JsonNode response, List<JsonNode> searchCalls) {
        var found = new LinkedHashMap<String, ChatResult.Source>();
        for (var call : searchCalls) {
            ChatWire.collect(found, call.path("action").path("url"));
            call.path("action").path("sources").forEach(source -> ChatWire.collect(found, source));
        }
        response.path("output").forEach(item -> item.path("content").forEach(content ->
                content.path("annotations").forEach(annotation -> ChatWire.collect(found, annotation))));
        return List.copyOf(found.values());
    }

    /**
     * Reassembles a streamed Responses call.
     *
     * <p>Every terminal event carries the whole response object, so the work is catching all three
     * of them. {@code response.incomplete} is the easiest to forget and the most expensive to miss:
     * it is how a call that hit its output ceiling ends, and it carries both the usage block and the
     * truncation reason. Dropping it bills a long answer as zero and classifies the cut as a parse
     * failure — which is retryable, so the identical cut gets bought again on every attempt.
     */
    public static final class Accumulator {

        private final StringBuilder text = new StringBuilder();
        private JsonNode terminal;

        /** @return the text added by this event */
        public String accept(JsonNode event) {
            var type = event.path("type").asString("");
            if (type.equals("response.output_text.delta")) {
                var delta = event.path("delta").asString("");
                text.append(delta);
                return delta;
            }
            if (TERMINAL_EVENTS.contains(type) && event.path("response").isObject()) {
                terminal = event.path("response");
            }
            return "";
        }

        public JsonNode response() {
            if (terminal != null) {
                return terminal;
            }
            // A stream that ended with no terminal event at all can only carry its text, so it says
            // incomplete rather than claiming a clean stop it never saw.
            var node = NODES.objectNode().put("status", "incomplete");
            var content = NODES.objectNode().put("type", "output_text").put("text", text.toString());
            var message = NODES.objectNode().put("type", "message");
            message.putArray("content").add(content);
            node.putArray("output").add(message);
            return node;
        }

        public String textSoFar() {
            return text.toString();
        }
    }

    private static List<JsonNode> outputsOfType(JsonNode response, String type) {
        var found = new java.util.ArrayList<JsonNode>();
        response.path("output").forEach(item -> {
            if (type.equals(item.path("type").asString(""))) {
                found.add(item);
            }
        });
        return found;
    }

    private static boolean isCompleted(JsonNode call) {
        var status = call.path("status").asString("");
        return status.isEmpty() || status.equals("completed");
    }
}
