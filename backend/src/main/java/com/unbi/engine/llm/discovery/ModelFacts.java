package com.unbi.engine.llm.discovery;

import com.unbi.engine.llm.spec.Capability;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Everything one gateway published about one model, read once and formatted here.
 *
 * <p>The companion to {@link EndpointFacts}, and a pure function of the same two kinds of body: the
 * model's entry in the listing, and — where the gateway publishes one — the list of hosts serving
 * it. Testing it over captured JSON is the only way to tell a field that was <em>read</em> from one
 * that was plausibly guessed, and the guesses are what make an info node worse than no info node.
 *
 * <p>Nothing here is inferred. A price the gateway did not quote stays null and renders as nothing;
 * a model with no {@code reasoning} block says nothing about reasoning rather than saying "no". The
 * one place that rule needed a decision is {@code pricing.web_search}, which arrives two orders of
 * magnitude away from every per-token field beside it — so it is reported as the figure the gateway
 * wrote, and not multiplied into a per-million price it plainly is not.
 *
 * @param created         the gateway's own creation timestamp, unix seconds; null when absent
 * @param aliasName       the model this id redirects to, when the entry says it redirects
 * @param providers       one formatted line per host serving this model, in the gateway's order
 * @param providersProblem why there are no provider lines, in the user's words; blank when there are
 * @param pricingNote     what makes the headline price incomplete, when something does
 */
public record ModelFacts(
        String id,
        String name,
        String canonicalSlug,
        String huggingFaceId,
        String description,
        Long created,
        String knowledgeCutoff,
        String aliasName,
        String aliasSlug,
        Integer contextWindow,
        Integer maxOutputTokens,
        Double inputPer1M,
        Double outputPer1M,
        Double cacheReadPer1M,
        Double cacheWritePer1M,
        Double imagePer1M,
        Double internalReasoningPer1M,
        Double webSearchPrice,
        String pricingNote,
        Set<Capability> capabilities,
        List<String> inputModalities,
        List<String> outputModalities,
        String tokenizer,
        Boolean reasoningMandatory,
        String defaultEffort,
        List<String> supportedEfforts,
        Boolean moderated,
        List<String> supportedParameters,
        String defaultParameters,
        List<String> providers,
        String providersProblem) {

    public ModelFacts {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        canonicalSlug = canonicalSlug == null ? "" : canonicalSlug;
        huggingFaceId = huggingFaceId == null ? "" : huggingFaceId;
        description = description == null ? "" : description;
        knowledgeCutoff = knowledgeCutoff == null ? "" : knowledgeCutoff;
        aliasName = aliasName == null ? "" : aliasName;
        aliasSlug = aliasSlug == null ? "" : aliasSlug;
        pricingNote = pricingNote == null ? "" : pricingNote;
        tokenizer = tokenizer == null ? "" : tokenizer;
        defaultEffort = defaultEffort == null ? "" : defaultEffort;
        defaultParameters = defaultParameters == null ? "" : defaultParameters;
        providersProblem = providersProblem == null ? "" : providersProblem;
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        inputModalities = List.copyOf(inputModalities == null ? List.of() : inputModalities);
        outputModalities = List.copyOf(outputModalities == null ? List.of() : outputModalities);
        supportedEfforts = List.copyOf(supportedEfforts == null ? List.of() : supportedEfforts);
        supportedParameters = List.copyOf(supportedParameters == null ? List.of() : supportedParameters);
        providers = List.copyOf(providers == null ? List.of() : providers);
    }

    /**
     * @param entry           this model's entry in the listing
     * @param endpointsBody   the hosts-serving-it answer, or null
     * @param endpointsProblem why there is no such answer; blank when there is one
     */
    public static ModelFacts read(JsonNode entry, JsonNode endpointsBody, String endpointsProblem) {
        var discovered = ModelListingReader.single(entry);
        var pricing = entry.path("pricing");
        var alias = entry.path("alias_target");
        var hosts = hosts(endpointsBody);

        return new ModelFacts(
                text(entry, "id"),
                text(entry, "name"),
                text(entry, "canonical_slug"),
                text(entry, "hugging_face_id"),
                text(entry, "description"),
                entry.path("created").isNumber() ? entry.path("created").asLong() : null,
                text(entry, "knowledge_cutoff"),
                text(alias, "name"),
                text(alias, "slug"),
                discovered.map(DiscoveredModel::contextWindow).orElse(null),
                discovered.map(DiscoveredModel::maxOutputTokens).orElse(null),
                Amounts.perMillion(pricing.path("prompt")),
                Amounts.perMillion(pricing.path("completion")),
                Amounts.perMillion(pricing.path("input_cache_read")),
                Amounts.perMillion(pricing.path("input_cache_write")),
                Amounts.perMillion(pricing.path("image")),
                Amounts.perMillion(pricing.path("internal_reasoning")),
                asDouble(pricing.path("web_search")),
                pricingNote(pricing),
                discovered.map(DiscoveredModel::capabilities).orElse(Set.of()),
                ModelListingReader.inputModalitiesOf(entry),
                ModelListingReader.outputModalitiesOf(entry),
                entry.path("architecture").path("tokenizer").asString(""),
                entry.path("reasoning").path("mandatory").isBoolean()
                        ? entry.path("reasoning").path("mandatory").asBoolean()
                        : null,
                discovered.map(DiscoveredModel::defaultEffort).orElse(""),
                discovered.map(DiscoveredModel::efforts).orElse(List.of()),
                entry.path("top_provider").path("is_moderated").isBoolean()
                        ? entry.path("top_provider").path("is_moderated").asBoolean()
                        : null,
                ModelListingReader.supportedParametersOf(entry),
                defaultParameters(entry.path("default_parameters")),
                hosts,
                hosts.isEmpty() ? aliasOrProblem(entry, endpointsBody, endpointsProblem) : "");
    }

    // --- Rows, already formatted ----------------------------------------------

    /** {@code $0.15 in / $0.60 out per M}, or blank when the gateway quoted no price. */
    public String price() {
        return Amounts.pricePair(inputPer1M, outputPer1M);
    }

    /** The date the gateway says this model appeared — a date, not an instant: it has no clock. */
    public String released() {
        if (created == null || created <= 0) {
            return "";
        }
        return Instant.ofEpochSecond(created).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** {@code Claude Opus 5 — anthropic/claude-opus-5}, or blank when this id is not an alias. */
    public String aliasOf() {
        if (aliasName.isEmpty() && aliasSlug.isEmpty()) {
            return "";
        }
        return aliasName.isEmpty() || aliasSlug.isEmpty()
                ? aliasName + aliasSlug
                : "%s — %s".formatted(aliasName, aliasSlug);
    }

    public String capabilityNames() {
        return capabilities.stream().map(Capability::wireName).sorted().collect(java.util.stream.Collectors.joining(", "));
    }

    /** Every provider line as one block, which is what a display of style BLOCK draws. */
    public String providersBlock() {
        return providers.isEmpty() ? providersProblem : String.join("\n", providers);
    }

    // --- Reading, defensively -------------------------------------------------

    /**
     * One line per host: who, at what quantization, how much it accepts, what it costs, how it has
     * been behaving.
     *
     * <p>A line rather than a row per field because this is the answer to a single question —
     * "provider order: which one do I put first?" — and that is decided by reading the hosts against
     * each other, which nine separate rows per host makes impossible.
     */
    private static List<String> hosts(JsonNode endpointsBody) {
        var found = new ArrayList<String>();
        var endpoints = endpointsBody == null
                ? null
                : endpointsBody.path("data").path("endpoints");
        if (endpoints == null || !endpoints.isArray()) {
            return List.of();
        }
        for (var host : endpoints) {
            var parts = new ArrayList<String>();
            add(parts, host.path("provider_name").asString(""));
            add(parts, host.path("quantization").asString(""));
            if (host.path("context_length").isNumber()) {
                add(parts, Amounts.count(host.path("context_length").asInt(0)) + " ctx");
            }
            if (host.path("max_completion_tokens").isNumber()) {
                add(parts, Amounts.count(host.path("max_completion_tokens").asInt(0)) + " out");
            }
            var in = Amounts.perMillion(host.path("pricing").path("prompt"));
            var out = Amounts.perMillion(host.path("pricing").path("completion"));
            if (in != null || out != null) {
                add(parts, "%s / %s per M".formatted(Amounts.moneyOrFree(in), Amounts.moneyOrFree(out)));
            }
            if (host.path("uptime_last_30m").isNumber()) {
                add(parts, "uptime %s".formatted(
                        String.format(Locale.ROOT, "%.1f%%", host.path("uptime_last_30m").asDouble())));
            }
            if (!parts.isEmpty()) {
                found.add(String.join(" · ", parts));
            }
        }
        return List.copyOf(found);
    }

    /**
     * Why a model has no provider lines.
     *
     * <p>An empty {@code endpoints} array is a real, measured answer and not a failure: an OpenRouter
     * alias answers exactly that, because the alias itself is served by nobody — it redirects. Saying
     * so is more useful than an empty box, and much more useful than "no providers found", which
     * reads as an outage.
     */
    private static String aliasOrProblem(JsonNode entry, JsonNode endpointsBody, String problem) {
        if (endpointsBody != null && endpointsBody.path("data").path("endpoints").isArray()) {
            return entry.path("alias_target").isObject()
                    ? "the gateway publishes no endpoints for this alias"
                    : "the gateway published no hosts for this model";
        }
        return problem == null ? "" : problem;
    }

    /**
     * What makes the headline price incomplete, in the gateway's own terms.
     *
     * <p>Two kinds of override were measured in one listing and they mean different things: some
     * models are cheaper at certain UTC hours, and many charge more once a prompt passes a token
     * threshold. Naming which one applies is the difference between a note a user can act on and a
     * disclaimer they learn to ignore.
     */
    private static String pricingNote(JsonNode pricing) {
        var overrides = pricing.path("overrides");
        if (!overrides.isArray() || overrides.isEmpty()) {
            return "";
        }
        var base = Amounts.perMillion(pricing.path("prompt"));
        var low = base;
        var high = base;
        var timeOfDay = false;
        Integer fromTokens = null;
        for (var override : overrides) {
            var value = Amounts.perMillion(override.path("prompt"));
            if (value != null) {
                low = low == null ? value : Math.min(low, value);
                high = high == null ? value : Math.max(high, value);
            }
            if (override.has("utc_days") || override.has("utc_start")) {
                timeOfDay = true;
            }
            var threshold = override.path("min_prompt_tokens");
            if (threshold.isNumber()) {
                fromTokens = fromTokens == null
                        ? threshold.asInt(0)
                        : Math.min(fromTokens, threshold.asInt(0));
            }
        }
        var reason = timeOfDay
                ? "varies by time of day"
                : fromTokens != null
                        ? "varies above %s prompt tokens".formatted(Amounts.count(fromTokens))
                        : "varies";
        if (low == null || high == null || low.equals(high)) {
            return reason;
        }
        return "%s: %s–%s /M in".formatted(reason, Amounts.money(low), Amounts.money(high));
    }

    /** {@code temperature 1 · top_p 0.95}, skipping the keys the gateway left null. */
    private static String defaultParameters(JsonNode defaults) {
        if (defaults == null || !defaults.isObject()) {
            return "";
        }
        var parts = new ArrayList<String>();
        defaults.propertyNames().forEach(name -> {
            var value = defaults.path(name);
            if (!value.isNull() && !value.isMissingNode()) {
                parts.add("%s %s".formatted(name, value.asString(value.toString())));
            }
        });
        return String.join(" · ", parts);
    }

    private static void add(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.trim());
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() ? value.asString("") : "";
    }

    private static Double asDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return node.isNumber() ? node.asDouble() : Double.valueOf(node.asString("").trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
