package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatMessage;
import com.unbi.engine.llm.spec.ChatResult;
import com.unbi.engine.llm.spec.FinishReason;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.ProviderRouting;
import com.unbi.engine.llm.spec.Reasoning;
import com.unbi.engine.llm.spec.ResponseFormat;
import com.unbi.engine.llm.spec.SamplingParams;
import com.unbi.engine.llm.spec.TokenUsage;
import com.unbi.engine.llm.spec.WebSearchMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Chat Completions dialect: what goes on the wire, and what comes back.
 *
 * <p>Every method here is a pure function of its arguments and every one is public, which is the
 * whole design. A request body that can only be observed by opening a socket cannot be tested, and
 * the difference between a parameter that was <em>configured</em> and one that was <em>sent</em> is
 * exactly the class of bug this pack exists to avoid: gateways accept unknown fields and silently
 * drop what they do not implement, so the same call looks identical whether it worked or not.
 */
public final class ChatWire {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatWire() {}

    // --- Request ------------------------------------------------------------

    public static ObjectNode request(ChatCall call, boolean stream) {
        var model = call.model();
        var body = NODES.objectNode();
        body.put("model", model.name());

        var messages = body.putArray("messages");
        call.messages().stream().filter(message -> !message.isEmpty()).forEach(message -> messages.add(message(message)));

        body.put("stream", stream);
        if (stream) {
            // Streaming otherwise drops the usage block, and with it every token count and the
            // cost of the call. A gateway that rejects the field can drop it through extraBody.
            body.set("stream_options", NODES.objectNode().put("include_usage", true));
        }
        // Omitted entirely when there is no ceiling: sending the field with a zero in it is a
        // request for zero tokens on some gateways and a 400 on others, and neither is "no limit".
        var outputLimit = call.effectiveMaxOutputTokens();
        if (outputLimit > 0) {
            model.outputLimitField().ifPresent(field -> body.put(field, outputLimit));
        }

        sampling(body, call.sampling());
        if (!call.correlationId().isBlank()) {
            body.put("user", call.correlationId());
        }

        var format = responseFormat(call.responseFormat(), model);
        if (format != null) {
            body.set("response_format", format);
        }
        var routing = provider(model.routing());
        if (routing != null) {
            body.set("provider", routing);
        }
        if (call.webSearch().enabled() && model.webSearchMode() == WebSearchMode.ONLINE) {
            var options = NODES.objectNode();
            if (!call.webSearch().contextSize().isBlank()) {
                options.put("search_context_size", call.webSearch().contextSize());
            }
            body.set("web_search_options", options);
        }

        reasoning(body, model.reasoning());
        // Model extras last: the escape hatch has to be able to override anything above it, which
        // is what makes it an escape hatch rather than a suggestion.
        model.extraBody().forEach((key, value) -> body.set(key, JsonValue.of(value)));
        return body;
    }

    /**
     * One message, as a plain string when it is only text.
     *
     * <p>The array form is universally understood by hosted gateways and not by every local server,
     * so it is used only when there is genuinely something beside the prose to carry.
     */
    public static ObjectNode message(ChatMessage message) {
        var node = NODES.objectNode();
        node.put("role", message.role().wireName());
        if (message.attachments().isEmpty()) {
            node.put("content", message.text());
            return node;
        }
        var content = node.putArray("content");
        if (!message.text().isBlank()) {
            content.add(NODES.objectNode().put("type", "text").put("text", message.text()));
        }
        message.attachments().forEach(attachment -> content.add(part(attachment)));
        return node;
    }

    private static ObjectNode part(Attachment attachment) {
        return switch (attachment.kind()) {
            case TEXT -> NODES.objectNode()
                    .put("type", "text")
                    .put("text", "%s:\n%s".formatted(attachment.name(), attachment.text()));
            case IMAGE -> {
                var node = NODES.objectNode().put("type", "image_url");
                node.set("image_url", NODES.objectNode().put("url", attachment.dataUrl()));
                yield node;
            }
            case DOCUMENT -> {
                var node = NODES.objectNode().put("type", "file");
                node.set("file", NODES.objectNode()
                        .put("filename", attachment.name())
                        .put("file_data", attachment.dataUrl()));
                yield node;
            }
        };
    }

    public static void sampling(ObjectNode body, SamplingParams params) {
        putIfPresent(body, "temperature", params.temperature());
        putIfPresent(body, "top_p", params.topP());
        // Not OpenAI fields, and every gateway in this pack's world takes them anyway. First-class
        // rather than extras because they are the two most often accepted and then ignored — which
        // is what provider routing's requireParameters exists to make audible.
        putIfPresent(body, "top_k", params.topK());
        putIfPresent(body, "min_p", params.minP());
        putIfPresent(body, "frequency_penalty", params.frequencyPenalty());
        putIfPresent(body, "presence_penalty", params.presencePenalty());
        putIfPresent(body, "seed", params.seed());
        if (!params.stop().isEmpty()) {
            var stop = body.putArray("stop");
            params.stop().forEach(stop::add);
        }
    }

    /**
     * {@code response_format}, but only for a target that declares it understands one.
     *
     * <p>A gateway that merely ignores an unknown field survives being sent one; a gateway that
     * rejects it fails every call while looking like an ordinary provider error. Dropping the field
     * is the right degradation rather than a workaround — JSON mode is a belt on top of a prompt
     * that already asks for JSON, and the result parser strips a code fence either way.
     */
    public static ObjectNode responseFormat(ResponseFormat format, ModelSpec model) {
        return switch (format) {
            case ResponseFormat.Text ignored -> null;
            case ResponseFormat.JsonObject ignored -> model.can(Capability.JSON_OBJECT)
                    ? NODES.objectNode().put("type", "json_object")
                    : null;
            case ResponseFormat.JsonSchema schema -> {
                if (!model.can(Capability.JSON_SCHEMA)) {
                    yield null;
                }
                var node = NODES.objectNode().put("type", "json_schema");
                node.set("json_schema", NODES.objectNode()
                        .put("name", schema.name())
                        .put("strict", schema.strict())
                        .set("schema", parseSchema(schema.schema())));
                yield node;
            }
        };
    }

    /**
     * The provider-preference block, or nothing at all.
     *
     * <p>Nothing at all is the important half: an endpoint that has never heard of provider routing
     * must not be sent an empty object. Configuration is camelCase like the rest of this pack; the
     * wire is snake_case because that is what the gateway reads.
     */
    public static ObjectNode provider(ProviderRouting routing) {
        if (routing == null || routing.isEmpty()) {
            return null;
        }
        var node = NODES.objectNode();
        putArrayIfAny(node, "order", routing.order());
        putArrayIfAny(node, "only", routing.only());
        putArrayIfAny(node, "ignore", routing.ignore());
        if (routing.allowFallbacks() != null) {
            node.put("allow_fallbacks", routing.allowFallbacks());
        }
        if (routing.requireParameters() != null) {
            node.put("require_parameters", routing.requireParameters());
        }
        if (routing.sort() != null) {
            node.put("sort", routing.sort().wireName());
        }
        return node;
    }

    /**
     * Each gateway family spells "think harder" differently, and the dialect says which — including
     * whether to say anything at all.
     */
    public static void reasoning(ObjectNode body, Reasoning reasoning) {
        switch (reasoning.dialect()) {
            case NONE -> {
                // Deliberate silence: the model's own default stands.
            }
            case REASONING_EFFORT -> body.put(
                    "reasoning_effort", reasoning.enabled() ? reasoning.effort().wireName() : "none");
            case REASONING -> {
                var node = NODES.objectNode();
                if (reasoning.enabled()) {
                    node.put("effort", reasoning.effort().wireName());
                    if (reasoning.maxTokens() > 0) {
                        node.put("max_tokens", reasoning.maxTokens());
                    }
                    node.put("exclude", reasoning.exclude());
                } else {
                    node.put("enabled", false);
                }
                body.set("reasoning", node);
            }
            case THINKING -> {
                var node = NODES.objectNode();
                if (reasoning.enabled()) {
                    node.put("type", "enabled");
                    node.put("budget_tokens", reasoning.maxTokens() > 0
                            ? reasoning.maxTokens()
                            : reasoning.effort().budgetTokens());
                } else {
                    node.put("type", "disabled");
                }
                body.set("thinking", node);
            }
        }
    }

    // --- Response -----------------------------------------------------------

    public static ChatResult parse(JsonNode completion, TokenUsage.CachedTokenMode mode, long latencyMillis) {
        var choice = completion.path("choices").path(0);
        var evidence = webSearchEvidence(completion, choice);
        return new ChatResult(
                text(choice.path("message").path("content")),
                FinishReason.of(choice.path("finish_reason").asString(null)),
                usage(completion.path("usage"), mode),
                completion.path("model").asString(""),
                latencyMillis,
                completion.path("usage").path("cost").asDouble(-1),
                !evidence.isEmpty(),
                evidence);
    }

    public static TokenUsage usage(JsonNode usage, TokenUsage.CachedTokenMode mode) {
        if (usage == null || !usage.isObject()) {
            return TokenUsage.NONE;
        }
        var reported = usage.path("prompt_tokens").asLong(0);
        var completion = usage.path("completion_tokens").asLong(0);
        var cached = firstPresent(
                usage.path("prompt_tokens_details").path("cached_tokens"),
                usage.path("cache_read_input_tokens"));
        var reasoning = usage.path("completion_tokens_details").path("reasoning_tokens").asLong(0);
        // A gateway that adds cached tokens on top of prompt_tokens would otherwise make a cache
        // hit — the thing that makes a call cheaper — read as a larger, more expensive call.
        var prompt = mode == TokenUsage.CachedTokenMode.ADDITIONAL ? Math.max(0, reported - cached) : reported;
        var total = mode == TokenUsage.CachedTokenMode.ADDITIONAL
                ? prompt + completion
                : usage.path("total_tokens").asLong(prompt + completion);
        return new TokenUsage(prompt, completion, cached, reasoning, total);
    }

    /** Content may be a string or an array of typed parts; both mean the same answer. */
    public static String text(JsonNode content) {
        if (content == null || content.isNull() || content.isMissingNode()) {
            return "";
        }
        if (content.isString()) {
            return content.asString("");
        }
        if (content.isArray()) {
            var out = new StringBuilder();
            content.forEach(part -> {
                var type = part.path("type").asString("text");
                if (type.equals("text") || type.equals("output_text")) {
                    out.append(part.path("text").asString(""));
                }
            });
            return out.toString();
        }
        return "";
    }

    /**
     * Reassembles a streamed completion into the shape the buffered path returns.
     *
     * <p>Everything downstream — text, usage, finish reason, cost — is then identical whichever
     * transport the endpoint asked for, which is the only way {@code stream} stays a transport
     * setting rather than a second code path.
     */
    public static final class Accumulator {

        private final StringBuilder delta = new StringBuilder();
        private final StringBuilder whole = new StringBuilder();
        private String finishReason;
        private String model;
        private JsonNode usage;
        private final List<JsonNode> citations = new ArrayList<>();

        /** @return the text added by this event, which is what a live view wants */
        public String accept(JsonNode event) {
            if (event.hasNonNull("model")) {
                model = event.path("model").asString(model);
            }
            if (event.path("usage").isObject()) {
                usage = event.path("usage");
            }
            event.path("citations").forEach(citations::add);

            var choice = event.path("choices").path(0);
            if (choice.hasNonNull("finish_reason")) {
                finishReason = choice.path("finish_reason").asString(finishReason);
            }
            choice.path("delta").path("annotations").forEach(citations::add);
            choice.path("message").path("annotations").forEach(citations::add);

            var chunk = text(choice.path("delta").path("content"));
            if (!chunk.isEmpty()) {
                delta.append(chunk);
                return chunk;
            }
            // Some gateways emit the accumulated message on their final chunk instead of a delta.
            // Kept separate so a gateway that sends both does not have every token counted twice.
            var message = text(choice.path("message").path("content"));
            if (!message.isEmpty()) {
                whole.setLength(0);
                whole.append(message);
            }
            return "";
        }

        public ObjectNode completion() {
            var node = NODES.objectNode();
            if (model != null) {
                node.put("model", model);
            }
            if (usage != null) {
                node.set("usage", usage);
            }
            if (!citations.isEmpty()) {
                var array = node.putArray("citations");
                citations.forEach(array::add);
            }
            var message = NODES.objectNode()
                    .put("content", !delta.isEmpty() ? delta.toString() : whole.toString());
            var choice = NODES.objectNode();
            choice.put("finish_reason", finishReason);
            choice.set("message", message);
            node.putArray("choices").add(choice);
            return node;
        }

        public String textSoFar() {
            return !delta.isEmpty() ? delta.toString() : whole.toString();
        }
    }

    /**
     * Sources the provider says it visited.
     *
     * <p>Read from {@code citations} and message {@code annotations} only — never scraped out of the
     * answer's prose. A model that invents a URL and writes it in a sentence is exactly the failure
     * this is meant to catch, so prose is not evidence.
     */
    public static List<ChatResult.Source> webSearchEvidence(JsonNode completion, JsonNode choice) {
        var sources = new LinkedHashMap<String, ChatResult.Source>();
        completion.path("citations").forEach(citation -> collect(sources, citation));
        choice.path("message").path("annotations").forEach(annotation -> collect(sources, annotation));
        return List.copyOf(sources.values());
    }

    static void collect(LinkedHashMap<String, ChatResult.Source> into, JsonNode candidate) {
        if (candidate == null) {
            return;
        }
        if (candidate.isString()) {
            var url = candidate.asString("");
            if (!url.isBlank()) {
                into.putIfAbsent(url, new ChatResult.Source(url, ""));
            }
            return;
        }
        var citation = candidate.path("url_citation");
        var url = citation.path("url").asString(candidate.path("url").asString(""));
        if (url.isBlank()) {
            return;
        }
        var title = citation.path("title").asString(candidate.path("title").asString(""));
        into.putIfAbsent(url, new ChatResult.Source(url, title));
    }

    static JsonNode parseSchema(String schema) {
        try {
            var parsed = MAPPER.readTree(schema);
            if (!parsed.isObject()) {
                throw new LlmFailure(
                        LlmFailure.Kind.INVALID_REQUEST, "A JSON schema must be a JSON object");
            }
            return parsed;
        } catch (LlmFailure alreadyClassified) {
            throw alreadyClassified;
        } catch (RuntimeException malformed) {
            throw new LlmFailure(
                    LlmFailure.Kind.INVALID_REQUEST,
                    "The JSON schema could not be parsed: " + malformed.getMessage());
        }
    }

    private static long firstPresent(JsonNode first, JsonNode second) {
        return first.isNumber() ? first.asLong(0) : second.asLong(0);
    }

    private static void putArrayIfAny(ObjectNode node, String field, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        var array = node.putArray(field);
        values.forEach(array::add);
    }

    private static void putIfPresent(ObjectNode node, String field, Number value) {
        if (value == null) {
            return;
        }
        if (value instanceof Integer whole) {
            node.put(field, whole.intValue());
        } else {
            node.put(field, value.doubleValue());
        }
    }

    /** Converts a loose extra-body value into a JSON node. */
    static final class JsonValue {

        private JsonValue() {}

        static JsonNode of(Object value) {
            return switch (value) {
                case null -> NODES.nullNode();
                case JsonNode node -> node;
                case String text -> NODES.stringNode(text);
                case Boolean flag -> NODES.booleanNode(flag);
                case Integer number -> NODES.numberNode(number);
                case Long number -> NODES.numberNode(number);
                case Number number -> NODES.numberNode(number.doubleValue());
                case java.util.Collection<?> items -> {
                    ArrayNode array = NODES.arrayNode();
                    items.forEach(item -> array.add(of(item)));
                    yield array;
                }
                case java.util.Map<?, ?> map -> {
                    var object = NODES.objectNode();
                    map.forEach((key, entry) -> object.set(String.valueOf(key), of(entry)));
                    yield object;
                }
                default -> NODES.stringNode(String.valueOf(value));
            };
        }
    }
}
