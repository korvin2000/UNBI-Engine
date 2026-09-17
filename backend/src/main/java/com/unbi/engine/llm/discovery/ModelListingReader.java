package com.unbi.engine.llm.discovery;

import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.Reasoning;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads a {@code GET /v1/models} body into {@link DiscoveredModel}s.
 *
 * <p>A pure function of the response, for the same reason the request builders are: what a gateway
 * says about a model, and what this engine concluded from it, is a mapping worth testing without a
 * socket. The three real shapes it has to survive were measured against live gateways, not guessed:
 *
 * <ul>
 *   <li><b>OpenRouter</b> — {@code supported_parameters}, {@code architecture.input_modalities},
 *       per-token prices as decimal <em>strings</em>, {@code top_provider.max_completion_tokens}.
 *   <li><b>OmniRoute</b> — an {@code api_format}, a {@code capabilities} object, {@code effort_tiers}
 *       and an explicit {@code max_output_tokens}.
 *   <li><b>llama.cpp</b> — an id and {@code meta.n_ctx}, and nothing else at all.
 * </ul>
 *
 * <p>Everything is read defensively and nothing is inferred beyond what is written down. A missing
 * field leaves the corresponding value null, which the Model node reads as "leave that setting
 * alone" — because a capability this engine <em>guessed</em> is exactly the untested claim the
 * capability check exists to refuse.
 */
public final class ModelListingReader {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;


    private ModelListingReader() {}

    public static List<DiscoveredModel> read(JsonNode body) {
        var models = new ArrayList<DiscoveredModel>();
        var data = body == null ? null : body.path("data");
        if (data == null || !data.isArray()) {
            return List.of();
        }
        for (var entry : data) {
            var id = firstText(entry, "id", "name", "model");
            if (id.isBlank()) {
                continue;
            }
            models.add(one(entry, id));
        }
        models.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return List.copyOf(models);
    }

    /**
     * Translates the Codex model catalogue into this reader's established listing shape.
     *
     * <p>The Codex catalogue is deliberately mapped here, once, rather than teaching every
     * discovery consumer its separate envelope. Only published facts survive the translation:
     * notably, it has no price fields, so the resulting entries have none either.
     */
    public static JsonNode normalizeCodex(JsonNode body) {
        if (body == null || !body.path("models").isArray()) {
            return body;
        }
        var normalized = NODES.objectNode();
        var data = normalized.putArray("data");
        for (var source : body.path("models")) {
            var id = firstText(source, "slug");
            if (id.isBlank()) {
                continue;
            }
            var target = data.addObject().put("id", id).put("api_format", "responses");
            var label = firstText(source, "display_name");
            if (!label.isBlank()) {
                target.put("name", label);
            }
            copyArray(source, target, "input_modalities");
            var context = positive(source.path("context_window"));
            if (context != null) {
                target.put("context_length", context);
            }
            reasoning(source, target);
            if (source.path("supports_search_tool").isBoolean()
                    && source.path("supports_search_tool").asBoolean()) {
                capabilities(target).put("web_search", true);
            }
        }
        return normalized;
    }

    /**
     * How many models this listing says are served.
     *
     * <p>{@code total_count} first, because a paged listing's {@code data} is one page: OpenRouter
     * now answers {@code {data:[…], total_count, links:{next}}}, and counting the array would report
     * a page size as a catalogue size the moment that {@code next} link starts being used.
     */
    public static int count(JsonNode body) {
        if (body == null) {
            return 0;
        }
        var total = body.path("total_count");
        if (total.isNumber() && total.asInt(0) > 0) {
            return total.asInt(0);
        }
        var data = body.path("data");
        return data.isArray() ? data.size() : 0;
    }

    /** One entry of a listing, by id, as the gateway wrote it. */
    public static Optional<JsonNode> entry(JsonNode body, String id) {
        if (body == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        var data = body.path("data");
        if (!data.isArray()) {
            return Optional.empty();
        }
        for (var entry : data) {
            if (firstText(entry, "id", "name", "model").equalsIgnoreCase(id.trim())) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** What this engine concludes about a single entry, without wrapping it back into a listing. */
    public static Optional<DiscoveredModel> single(JsonNode entry) {
        if (entry == null) {
            return Optional.empty();
        }
        var id = firstText(entry, "id", "name", "model");
        return id.isBlank() ? Optional.empty() : Optional.of(one(entry, id));
    }

    /** Every input modality any model in this listing accepts, in the gateway's own words. */
    public static List<String> inputModalities(JsonNode body) {
        var found = new java.util.TreeSet<String>();
        var data = body == null ? null : body.path("data");
        if (data != null && data.isArray()) {
            for (var entry : data) {
                found.addAll(textSet(entry.path("architecture").path("input_modalities")));
                found.addAll(textSet(entry.path("input_modalities")));
            }
        }
        return List.copyOf(found);
    }

    /** Input modalities of one entry, in the order the gateway listed them. */
    public static List<String> inputModalitiesOf(JsonNode entry) {
        var found = new LinkedHashSet<String>(textSet(entry.path("architecture").path("input_modalities")));
        found.addAll(textSet(entry.path("input_modalities")));
        return List.copyOf(found);
    }

    /** Output modalities of one entry. Usually just {@code text}, and occasionally not. */
    public static List<String> outputModalitiesOf(JsonNode entry) {
        return List.copyOf(textSet(entry.path("architecture").path("output_modalities")));
    }

    /** The parameters one entry says it accepts, in the order the gateway listed them. */
    public static List<String> supportedParametersOf(JsonNode entry) {
        return List.copyOf(textSet(entry.path("supported_parameters")));
    }

    private static DiscoveredModel one(JsonNode entry, String id) {
        var parameters = textSet(entry.path("supported_parameters"));
        var modalities = textSet(entry.path("architecture").path("input_modalities"));
        modalities.addAll(textSet(entry.path("input_modalities")));
        var capabilityFlags = entry.path("capabilities");

        var capabilities = new LinkedHashSet<Capability>();
        if (parameters.contains("response_format")) {
            capabilities.add(Capability.JSON_OBJECT);
        }
        if (capabilityFlags.path("web_search").asBoolean(false)) {
            capabilities.add(Capability.WEB_SEARCH);
        }
        if (parameters.contains("structured_outputs")) {
            capabilities.add(Capability.JSON_SCHEMA);
        }
        if (parameters.contains("tools") || capabilityFlags.path("tool_calling").asBoolean(false)) {
            capabilities.add(Capability.TOOLS);
        }
        if (parameters.contains("reasoning")
                || parameters.contains("reasoning_effort")
                || parameters.contains("include_reasoning")
                || capabilityFlags.path("reasoning").asBoolean(false)
                || capabilityFlags.path("thinking").asBoolean(false)) {
            capabilities.add(Capability.REASONING);
        }
        if (modalities.contains("image") || capabilityFlags.path("vision").asBoolean(false)) {
            capabilities.add(Capability.VISION);
        }
        if (modalities.contains("file") || modalities.contains("pdf") || modalities.contains("document")) {
            capabilities.add(Capability.FILES);
        }
        if (entry.path("pricing").has("input_cache_read")
                || capabilityFlags.path("prompt_cache").asBoolean(false)) {
            capabilities.add(Capability.PROMPT_CACHE);
        }

        return new DiscoveredModel(
                id,
                firstText(entry, "name"),
                apiFormat(entry),
                contextWindow(entry),
                maxOutputTokens(entry),
                capabilities,
                dialect(parameters, capabilities, capabilityFlags),
                reasoningOn(entry),
                efforts(entry, capabilityFlags),
                entry.path("reasoning").path("default_effort").asString(""),
                maxTokensParam(parameters),
                perMillion(entry.path("pricing").path("prompt")),
                perMillion(entry.path("pricing").path("completion")));
    }

    /**
     * The wire format this model speaks, when the gateway says so.
     *
     * <p>Null rather than a default: guessing chat completions for a model only reachable through
     * Responses produces a 404 the user has no way to connect back to this moment.
     */
    private static ApiFormat apiFormat(JsonNode entry) {
        var declared = entry.path("api_format").asString("");
        if (declared.isBlank()) {
            var endpoints = textSet(entry.path("supported_endpoints"));
            if (endpoints.size() == 1 && endpoints.contains("responses")) {
                return ApiFormat.RESPONSES;
            }
            return null;
        }
        return declared.equalsIgnoreCase("responses") ? ApiFormat.RESPONSES : ApiFormat.CHAT_COMPLETIONS;
    }

    private static Integer contextWindow(JsonNode entry) {
        // top_provider first: OpenRouter's model-level context_length is the best any host offers,
        // while top_provider is what the host actually serving the call will accept.
        for (var candidate : List.of(
                entry.path("top_provider").path("context_length"),
                entry.path("context_length"),
                entry.path("max_input_tokens"),
                entry.path("meta").path("n_ctx"))) {
            var value = positive(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer maxOutputTokens(JsonNode entry) {
        for (var candidate : List.of(
                entry.path("top_provider").path("max_completion_tokens"),
                entry.path("max_output_tokens"),
                entry.path("max_completion_tokens"))) {
            var value = positive(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Which reasoning dialect to speak, or null when the gateway gave no reason to speak one.
     *
     * <p>{@code dialect: none} is a real answer and the safest one, so it is never invented here —
     * a model that says nothing about reasoning keeps whatever the node already had.
     */
    private static Reasoning.Dialect dialect(
            Set<String> parameters, Set<Capability> capabilities, JsonNode capabilityFlags) {
        if (parameters.contains("reasoning_effort") || !capabilityFlags.path("effort_tiers").isEmpty()) {
            return Reasoning.Dialect.REASONING_EFFORT;
        }
        if (parameters.contains("reasoning")) {
            return Reasoning.Dialect.REASONING;
        }
        if (capabilityFlags.path("thinking").asBoolean(false)) {
            return Reasoning.Dialect.THINKING;
        }
        return capabilities.contains(Capability.REASONING) ? Reasoning.Dialect.REASONING : null;
    }

    /**
     * Whether this model reasons unless told otherwise.
     *
     * <p>Mandatory beats everything: a gateway that refuses to disable reasoning must never be sent
     * an instruction to disable it. Otherwise the gateway's stated default is taken at face value,
     * and a gateway that says nothing leaves this null — which the Model node reads as "do not
     * touch the switch".
     */
    private static Boolean reasoningOn(JsonNode entry) {
        var reasoning = entry.path("reasoning");
        if (reasoning.path("mandatory").asBoolean(false)) {
            return Boolean.TRUE;
        }
        var declared = reasoning.path("default_enabled");
        return declared.isBoolean() ? declared.asBoolean() : null;
    }

    private static List<String> efforts(JsonNode entry, JsonNode capabilityFlags) {
        var tiers = new ArrayList<String>();
        for (var node : List.of(
                entry.path("reasoning").path("supported_efforts"), capabilityFlags.path("effort_tiers"))) {
            if (node.isArray()) {
                node.forEach(value -> {
                    var text = value.asString("").trim().toLowerCase(Locale.ROOT);
                    if (!text.isEmpty() && !tiers.contains(text)) {
                        tiers.add(text);
                    }
                });
            }
        }
        return tiers;
    }

    /**
     * Which output-ceiling field this model accepts.
     *
     * <p>Only when the gateway lists its parameters. Everywhere else the node's own "Automatic"
     * setting decides, which is a better answer than a coin toss recorded as a discovery.
     */
    private static ModelSpec.MaxTokensParam maxTokensParam(Set<String> parameters) {
        if (parameters.contains("max_completion_tokens") && !parameters.contains("max_tokens")) {
            return ModelSpec.MaxTokensParam.MAX_COMPLETION_TOKENS;
        }
        if (parameters.contains("max_tokens")) {
            return ModelSpec.MaxTokensParam.MAX_TOKENS;
        }
        return null;
    }

    private static void copyArray(JsonNode source, ObjectNode target, String field) {
        var values = source.path(field);
        if (!values.isArray()) {
            return;
        }
        var copy = target.putArray(field);
        values.forEach(value -> {
            var text = value.asString("").trim();
            if (!text.isEmpty()) {
                copy.add(text);
            }
        });
        if (copy.isEmpty()) {
            target.remove(field);
        }
    }

    private static void reasoning(JsonNode source, ObjectNode target) {
        var levels = source.path("supported_reasoning_levels");
        var efforts = new ArrayList<String>();
        if (levels.isArray()) {
            for (var level : levels) {
                var effort = level.isObject() ? level.path("effort").asString("") : level.asString("");
                effort = effort.trim().toLowerCase(Locale.ROOT);
                if (!effort.isEmpty() && !efforts.contains(effort)) {
                    efforts.add(effort);
                }
            }
        }
        var defaultLevel = source.path("default_reasoning_level");
        var defaultEffort = defaultLevel.isObject()
                ? defaultLevel.path("effort").asString("")
                : defaultLevel.asString("");
        defaultEffort = defaultEffort.trim().toLowerCase(Locale.ROOT);
        if (!efforts.isEmpty()) {
            target.putArray("supported_parameters").add("reasoning_effort");
            var reasoning = target.putObject("reasoning");
            var listed = reasoning.putArray("supported_efforts");
            efforts.forEach(listed::add);
            if (!defaultEffort.isEmpty()) {
                reasoning.put("default_effort", defaultEffort);
            }
            capabilities(target).put("reasoning", true);
        }
    }
    private static ObjectNode capabilities(ObjectNode target) {
        var existing = target.path("capabilities");
        return existing.isObject() ? (ObjectNode) existing : target.putObject("capabilities");
    }


    /** Gateways quote per-token prices, often as decimal strings. This engine quotes per million. */
    private static Double perMillion(JsonNode node) {
        return Amounts.perMillion(node);
    }

    private static Integer positive(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        var value = node.asInt(0);
        return value > 0 ? value : null;
    }

    private static String firstText(JsonNode entry, String... keys) {
        for (var key : keys) {
            var value = entry.path(key);
            if (value.isString() && !value.asString("").isBlank()) {
                return value.asString("");
            }
        }
        return "";
    }

    private static Set<String> textSet(JsonNode node) {
        var found = new LinkedHashSet<String>();
        if (node != null && node.isArray()) {
            node.forEach(value -> {
                var text = value.asString("").trim().toLowerCase(Locale.ROOT);
                if (!text.isEmpty()) {
                    found.add(text);
                }
            });
        }
        return found;
    }
}
