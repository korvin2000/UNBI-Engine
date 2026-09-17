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
import com.unbi.engine.llm.spec.LlmFailure;
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
        var endpoint = model.endpoint();
        endpoint.validateApiFormat(model.apiFormat());
        if (endpoint.responsesDialect() == com.unbi.engine.llm.spec.EndpointSpec.ResponsesDialect.CODEX
                && (!stream || !endpoint.stream())) {
            throw new LlmFailure(LlmFailure.Kind.UNSUPPORTED,
                    "The Codex Responses dialect requires streaming");
        }
        var body = NODES.objectNode();
        body.put("model", model.name());

        var input = body.putArray("input");
        var instructions = new StringBuilder();
        call.messages().stream()
                .filter(message -> !message.isEmpty())
                .forEach(message -> {
                    if (endpoint.responsesDialect()
                            == com.unbi.engine.llm.spec.EndpointSpec.ResponsesDialect.CODEX
                            && message.role() == ChatMessage.Role.SYSTEM) {
                        if (!message.attachments().isEmpty()) {
                            throw new LlmFailure(LlmFailure.Kind.UNSUPPORTED,
                                    "System message attachments are not supported by the Codex Responses dialect");
                        }
                        if (!instructions.isEmpty()) {
                            instructions.append("\n\n");
                        }
                        instructions.append(message.text());
                    } else {
                        input.add(message(message, endpoint.responsesPromptCache()));
                    }
                });

        body.put("stream", stream);
        // Nothing here needs the provider to retain a conversation, and storing one is a privacy
        // decision this pack has no business taking on a user's behalf.
        body.put("store", false);
        if (endpoint.responsesDialect() == com.unbi.engine.llm.spec.EndpointSpec.ResponsesDialect.CODEX) {
            body.put("instructions", instructions.toString());
        } else {
            var outputLimit = call.effectiveMaxOutputTokens();
            if (outputLimit > 0 && model.outputLimitField().isPresent()) {
                body.put("max_output_tokens", outputLimit);
            }
        }

        if (endpoint.responsesDialect() != com.unbi.engine.llm.spec.EndpointSpec.ResponsesDialect.CODEX) {
            sampling(body, call.sampling());
        }

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
            body.putArray("include").add("web_search_call.action.sources");
        }
        if (endpoint.responsesPromptCache() && !call.cacheKey().isBlank()) {
            body.put("prompt_cache_key", call.cacheKey());
        }
        var routing = ChatWire.provider(model.routing());
        if (routing != null) {
            body.set("provider", routing);
        }
        mergeExtraBody(body, model.extraBody(), endpoint.responsesDialect(), model.name(), stream,
                endpoint.responsesDialect() == com.unbi.engine.llm.spec.EndpointSpec.ResponsesDialect.CODEX
                        ? instructions.toString() : null);
        return body;
    }

    private static void mergeExtraBody(
            ObjectNode body,
            java.util.Map<String, Object> extra,
            com.unbi.engine.llm.spec.EndpointSpec.ResponsesDialect dialect,
            String model,
            boolean stream,
            String instructions) {
        extra.forEach((key, value) -> {
            var replacement = ChatWire.JsonValue.of(value);
            if (key.equals("model") && (!replacement.isTextual() || !model.equals(replacement.asString()))) {
                throw new IllegalArgumentException("Extra Request Body cannot override the model");
            }
            if (key.equals("stream") && (!replacement.isBoolean() || stream != replacement.asBoolean())) {
                throw new IllegalArgumentException("Extra Request Body cannot override streaming");
            }
            if (dialect == com.unbi.engine.llm.spec.EndpointSpec.ResponsesDialect.CODEX) {
                if (key.equals("temperature") || key.equals("top_p") || key.equals("max_output_tokens")) {
                    return;
                }
                if (key.equals("store") && (!replacement.isBoolean() || replacement.asBoolean())) {
                    throw new IllegalArgumentException("The Codex Responses dialect requires store=false");
                }
                if (key.equals("instructions")
                        && (!replacement.isTextual() || !instructions.equals(replacement.asString()))) {
                    throw new IllegalArgumentException("Extra Request Body cannot override Codex instructions");
                }
            }
            body.set(key, replacement);
        });
    }

    public static ObjectNode message(ChatMessage message, boolean explicitCacheControls) {
        var node = NODES.objectNode();
        // Standard Responses uses the documented developer spelling for system messages.
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
     * The Responses spelling of the same reasoning intent.
     *
     * <p>Responses carries only the effort field. Chat/OpenRouter-only max_tokens and exclude
     * controls are intentionally not projected into this dialect.
     */
    public static ObjectNode reasoning(Reasoning reasoning) {
        if (reasoning.dialect() != Reasoning.Dialect.REASONING_EFFORT
                && reasoning.dialect() != Reasoning.Dialect.REASONING) {
            return null;
        }
        if (!reasoning.enabled()) {
            return NODES.objectNode().put("effort", "none");
        }
        return NODES.objectNode().put("effort", reasoning.effort().wireName());
    }

    // --- Response -----------------------------------------------------------

    public static ChatResult parse(JsonNode response, long latencyMillis) {
        requireObject(response, "The Responses response was not an object");
        if (response.hasNonNull("error")) throwIfFailed(response);
        var status = response.path("status").asString("");
        switch (status) {
            case "completed", "incomplete" -> {
                if (!response.path("output").isArray()) {
                    throw responseFormat("The Responses response did not contain output");
                }
            }
            case "failed" -> throwIfFailed(response);
            default -> throw responseFormat("The Responses response had no terminal status");
        }
        throwIfUnsupportedOutput(response);
        var finish = finishReason(response);
        if (finish == FinishReason.CONTENT_FILTER)
            throw new LlmFailure(LlmFailure.Kind.CONTENT_FILTER, "The Responses request was refused");
        if (status.equals("incomplete") && finish != FinishReason.LENGTH)
            throw responseFormat("The Responses request did not complete");
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
     * <p>Every terminal event carries the whole response object. A stream without one is not a
     * successful incomplete answer: the connection may have been truncated before the provider
     * recorded usage or a finish state.
     */
    public static final class Accumulator {

        private final StringBuilder text = new StringBuilder();
        private JsonNode terminal;

        /** @return the text added by this event */
        public String accept(JsonNode event) {
            requireObject(event, "The Responses stream event was not an object");
            var type = event.path("type").asString("");
            if (type.equals("error") || event.hasNonNull("error")) throwIfFailed(event);
            if (type.startsWith("response.refusal."))
                throw new LlmFailure(LlmFailure.Kind.CONTENT_FILTER, "The Responses request was refused");
            if (type.equals("response.output_text.delta")) {
                var delta = event.path("delta");
                if (!delta.isString()) {
                    throw responseFormat("The Responses text delta was not text");
                }
                var value = delta.asString("");
                text.append(value);
                return value;
            }
            if (isTerminal(type)) {
                if (!event.path("response").isObject()) {
                    throw responseFormat("The Responses terminal event did not contain a response");
                }
                terminal = event.path("response");
            }
            return "";
        }

        public JsonNode response() {
            if (terminal == null) {
                throw responseFormat("The Responses stream ended before a terminal response");
            }
            return terminal;
        }

        public String textSoFar() {
            return text.toString();
        }
    }

    static boolean isTerminal(JsonNode event) {
        return event != null && isTerminal(event.path("type").asString(""));
    }

    private static boolean isTerminal(String type) {
        return type.equals("response.completed") || type.equals("response.incomplete") || type.equals("response.failed");
    }

    private static void throwIfFailed(JsonNode response) {
        var error = response.path("type").asString("").equals("error") ? response : response.path("error");
        var code = error.path("code").asString(error.path("type").asString(""));
        var kind = LlmFailure.classify(400, code);
        throw new LlmFailure(kind, "The Responses request failed");
    }
    private static void requireObject(JsonNode node, String message) {
        if (node == null || !node.isObject()) {
            throw responseFormat(message);
        }
    }


    private static void throwIfUnsupportedOutput(JsonNode response) {
        response.path("output").forEach(item -> {
            var type = item.path("type").asString("");
            if (type.equals("refusal") || item.path("refusal").isString()
                    || java.util.stream.StreamSupport.stream(item.path("content").spliterator(), false)
                            .anyMatch(part -> part.path("type").asString("").equals("refusal"))) {
                throw new com.unbi.engine.llm.spec.LlmFailure(
                        com.unbi.engine.llm.spec.LlmFailure.Kind.CONTENT_FILTER,
                        "The Responses request was refused");
            }
            if (type.equals("function_call")
                    || type.equals("computer_call")
                    || type.equals("tool_call")
                    || type.equals("custom_tool_call")) {
                throw new com.unbi.engine.llm.spec.LlmFailure(
                        com.unbi.engine.llm.spec.LlmFailure.Kind.UNSUPPORTED,
                        "The Responses response requested a tool call, but this engine has no tool loop");
            }
        });
    }

    private static LlmFailure responseFormat(String message) {
        return new LlmFailure(LlmFailure.Kind.RESPONSE_FORMAT, message);
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
