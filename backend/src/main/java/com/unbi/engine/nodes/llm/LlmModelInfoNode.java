package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeAction;
import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeProbe;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.discovery.Amounts;
import com.unbi.engine.llm.discovery.GatewayDirectory;
import com.unbi.engine.llm.discovery.ModelEndpointPath;
import com.unbi.engine.llm.discovery.ModelFacts;
import com.unbi.engine.llm.discovery.ModelListingReader;
import com.unbi.engine.llm.discovery.OptionalFetch;
import com.unbi.engine.llm.discovery.TimeBudget;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.ProviderProfile;
import com.unbi.engine.nodes.llm.model.LlmModelInfo;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Asks a gateway about one model, and shows the answer on the node.
 *
 * <p>Deliberately not more buttons on the Model node. That node <em>declares</em> what a request may
 * ask for, and everything on it is editable because a declaration has to be; this one only reports,
 * and nothing on it can be typed into. Keeping the two apart is what stops a reference — "what does
 * this cost, who serves it, when was it trained" — from being mistaken for a setting, and stops a
 * fact from being quietly edited into a claim the capability check will then enforce.
 *
 * <p>The provider block is the row that earns the node. One OpenRouter model id is served by many
 * hosts at different quantizations, context lengths, prices and uptimes, and "which do I put first
 * in provider order" is unanswerable from the model listing alone — it needs the per-host endpoint
 * document, which nothing else in this pack reads.
 */
@Component
public class LlmModelInfoNode implements NodeDefinition, NodeProbe {

    /** Enough near matches to recognise a typo, few enough to read at a glance. */
    private static final int NEAR_MATCHES = 4;

    private final GatewayDirectory gateways;
    private final EndpointProfiles profiles;
    private final LlmEndpointNode endpoints;

    public LlmModelInfoNode(
            GatewayDirectory gateways, EndpointProfiles profiles, LlmEndpointNode endpoints) {
        this.gateways = gateways;
        this.profiles = profiles;
        this.endpoints = endpoints;
    }

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.model_info", "Model Info")
                .in(LlmTypes.CATEGORY, "Connection")
                .icon("table")
                .accent(LlmTypes.ACCENT)
                .describedAs("Reads what the gateway publishes about the wired model: context and "
                        + "output ceilings, prices, modalities, and every host serving it with its "
                        + "quantization, price and uptime. Reports only — nothing here is a setting.")
                .action(NodeAction.discover("fetch", "Fetch this model's facts from the endpoint", "bulb"))
                .socket("model", "Model", LlmTypes.MODEL)
                .display("fetchedAt", "Fetched", Widget.Display.line())
                .display("name", "Name", Widget.Display.line())
                .display("id", "Id", Widget.Display.line())
                .display("contextWindow", "Context Window", Widget.Display.line("tok"))
                .hint("What the host serving the call accepts, not the best any host offers.")
                .display("maxOutput", "Max Output", Widget.Display.line("tok"))
                .display("price", "Price", Widget.Display.line())
                .display("capabilities", "Capabilities", LlmTypes.TEXT_LIST, Widget.Display.chips())
                .hint("In this engine's vocabulary, read from what the gateway listed — the same "
                        + "reading the Model node's bulb writes into its capability boxes.")
                .display("providerCount", "Providers", Widget.Display.line())
                .section("Identity")
                .advancedDisplay("canonicalSlug", "Canonical Slug", Widget.Display.line())
                .advancedDisplay("aliasOf", "Resolves To", Widget.Display.line())
                .advancedDisplay("huggingFaceId", "Hugging Face Id", Widget.Display.line())
                .advancedDisplay("released", "Released", Widget.Display.line())
                .advancedDisplay("knowledgeCutoff", "Knowledge Cutoff", Widget.Display.line())
                .advancedDisplay("description", "Description", Widget.Display.block())
                .hint("As the gateway published it, truncation and all.")
                .section("Modalities")
                .advancedDisplay("inputModalities", "Input", LlmTypes.TEXT_LIST, Widget.Display.chips())
                .advancedDisplay("outputModalities", "Output", LlmTypes.TEXT_LIST, Widget.Display.chips())
                .advancedDisplay("tokenizer", "Tokenizer", Widget.Display.line())
                .section("Pricing")
                .advancedDisplay("cacheRead", "Cache Read", Widget.Display.line())
                .advancedDisplay("cacheWrite", "Cache Write", Widget.Display.line())
                .advancedDisplay("imagePrice", "Image Input", Widget.Display.line())
                .advancedDisplay("internalReasoning", "Internal Reasoning", Widget.Display.line())
                .advancedDisplay("webSearchPrice", "Web Search", Widget.Display.line())
                .hint("As the gateway quotes it. This one is not a per-token price, so it is not "
                        + "multiplied into one.")
                .advancedDisplay("pricingNote", "Pricing Note", Widget.Display.line())
                .section("Reasoning")
                .advancedDisplay("reasoningMandatory", "Mandatory", Types.BOOLEAN, Widget.Display.line())
                .hint("A model that reasons mandatorily rejects every call telling it not to.")
                .advancedDisplay("defaultEffort", "Default Effort", Widget.Display.line())
                .advancedDisplay("supportedEfforts", "Supported Efforts",
                        LlmTypes.TEXT_LIST, Widget.Display.chips())
                .section("Limits")
                .advancedDisplay("moderated", "Moderated", Types.BOOLEAN, Widget.Display.line())
                .advancedDisplay("parameters", "Supported Parameters",
                        LlmTypes.TEXT_LIST, Widget.Display.chips())
                .advancedDisplay("defaultParameters", "Default Parameters", Widget.Display.line())
                .section("Providers")
                .advancedDisplay("providers", "Serving This Model", Widget.Display.block())
                .hint("One line per host: quantization, ceilings, price, uptime. This is what "
                        + "provider order on the Model node is choosing between.")
                .section("")
                .out("info", "Info", LlmTypes.MODEL_INFO)
                .hint("The same facts as data — preview it, or tabulate several models side by side.")
                .out("summary", "Summary", Types.TEXT)
                .out("providers", "Providers", LlmTypes.TEXT_LIST)
                .hint("One formatted line each, for a report or a further filter.")
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var model = context.require("model", ModelSpec.class);
        var fetched = fetch(model.endpoint(), model.name());
        fetched.notes().forEach(context::log);
        context.log(fetched.verdict());
        if (fetched.facts().isEmpty()) {
            throw new IllegalStateException(fetched.verdict());
        }

        var info = info(fetched);
        var summary = info.toString();
        context.stream("summary", summary);
        context.output("info", info);
        context.output("summary", summary);
        context.output("providers", fetched.facts().orElseThrow().providers());
        context.progress(1, info.summaryLine());
    }

    @Override
    public Result probe(String action, Request request) {
        if (!"fetch".equals(action)) {
            return Result.failed("This node has no action called " + action);
        }
        var wired = request.source("model");
        if (!wired.isPresent()) {
            return Result.failed("Wire an LLM Model into this node first — there is nothing to ask about.");
        }
        var name = wired.text("model").trim();
        if (name.isEmpty()) {
            return Result.failed("The wired Model node has no model name yet.");
        }

        EndpointSpec endpoint;
        try {
            endpoint = endpoints.resolve(wired.source("endpoint"));
        } catch (RuntimeException misconfigured) {
            return Result.failed("The endpoint is not usable yet: " + misconfigured.getMessage());
        }
        var blocked = profiles.credentialProblem(endpoint);
        if (blocked.isPresent()) {
            return Result.failed(blocked.get());
        }

        var fetched = fetch(endpoint, name);
        var result = fetched.facts().isPresent()
                ? Result.ok(fetched.verdict())
                : Result.problem(fetched.verdict());
        fetched.notes().forEach(result::detail);

        // Cleared first, always: a re-fetch that could not find the model must not leave the
        // previous model's price and providers on screen under a fresh timestamp.
        var rows = DisplayRows.blank(descriptor());
        rows.put("fetchedAt", fetched.fetchedAt());
        fetched.facts().ifPresent(facts -> {
            rows.put("name", facts.name());
            rows.put("id", facts.id());
            rows.put("contextWindow", Amounts.count(facts.contextWindow()));
            rows.put("maxOutput", Amounts.count(facts.maxOutputTokens()));
            rows.put("price", facts.price());
            rows.put("capabilities", chips(facts.capabilities().stream()
                    .map(com.unbi.engine.llm.spec.Capability::wireName)
                    .sorted()
                    .toList()));
            rows.put("providerCount", facts.providers().isEmpty()
                    ? ""
                    : Amounts.count(facts.providers().size()));
            rows.put("canonicalSlug", facts.canonicalSlug());
            rows.put("aliasOf", facts.aliasOf());
            rows.put("huggingFaceId", facts.huggingFaceId());
            rows.put("released", facts.released());
            rows.put("knowledgeCutoff", facts.knowledgeCutoff());
            rows.put("description", facts.description());
            rows.put("inputModalities", chips(facts.inputModalities()));
            rows.put("outputModalities", chips(facts.outputModalities()));
            rows.put("tokenizer", facts.tokenizer());
            rows.put("cacheRead", Amounts.money(facts.cacheReadPer1M()));
            rows.put("cacheWrite", Amounts.money(facts.cacheWritePer1M()));
            rows.put("imagePrice", Amounts.money(facts.imagePer1M()));
            rows.put("internalReasoning", Amounts.money(facts.internalReasoningPer1M()));
            rows.put("webSearchPrice", Amounts.money(facts.webSearchPrice()));
            rows.put("pricingNote", facts.pricingNote());
            rows.put("reasoningMandatory",
                    facts.reasoningMandatory() == null ? "" : facts.reasoningMandatory());
            rows.put("defaultEffort", facts.defaultEffort());
            rows.put("supportedEfforts", chips(facts.supportedEfforts()));
            rows.put("moderated", facts.moderated() == null ? "" : facts.moderated());
            rows.put("parameters", chips(facts.supportedParameters()));
            rows.put("defaultParameters", facts.defaultParameters());
            rows.put("providers", facts.providersBlock());
        });
        rows.forEach(result::value);
        return result.build();
    }

    // --- The one fetch ---------------------------------------------------------

    /**
     * The listing first, because it decides whether there is anything to report; the per-host
     * document second, with what is left of the budget.
     */
    private Fetched fetch(EndpointSpec endpoint, String name) {
        var profile = ProviderProfile.resolve(endpoint.profile());
        var budget = TimeBudget.forProbe(endpoint);
        var notes = new ArrayList<String>();
        var fetchedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        var listing = OptionalFetch.required(gateways, endpoint, profile.modelsPath(), budget.remaining());
        notes.add(note(endpoint, profile.modelsPath(), listing));
        if (!listing.answered()) {
            return new Fetched(Optional.empty(), listing.problem(), List.copyOf(notes), fetchedAt);
        }

        var entry = ModelListingReader.entry(listing.body(), name);
        if (entry.isEmpty()) {
            return new Fetched(Optional.empty(), notServed(endpoint, listing.body(), name),
                    List.copyOf(notes), fetchedAt);
        }

        // The id goes into the path whole. Several of these contain a slash and a colon, and
        // splitting one to build a URL is how a model the gateway serves answers 404.
        var path = ModelEndpointPath.of(
                profile.modelEndpointsPath(), ModelEndpointPath.slugOf(entry.get(), name));
        var hosts = path.isBlank()
                ? new OptionalFetch.Result(null, "this gateway publishes none", "")
                : OptionalFetch.of(gateways, endpoint, path, budget);
        if (!hosts.path().isBlank()) {
            notes.add(note(endpoint, hosts.path(), hosts));
        }

        var facts = ModelFacts.read(entry.get(), hosts.body(), hosts.problem());
        return new Fetched(Optional.of(facts), verdict(facts), List.copyOf(notes), fetchedAt);
    }

    private static String verdict(ModelFacts facts) {
        var price = facts.price();
        var served = facts.providers().isEmpty()
                ? ""
                : " · %d provider%s".formatted(
                        facts.providers().size(), facts.providers().size() == 1 ? "" : "s");
        return "%s%s%s".formatted(
                facts.id(), price.isEmpty() ? "" : " · " + price, served);
    }

    /**
     * "Not served" with the near misses, exactly as {@code LlmModelNode.check} reports it.
     *
     * <p>Same question, same answer: a model name is usually wrong by a suffix, and the four ids
     * containing what was typed are what turns "does not serve it" into a fix.
     */
    private static String notServed(EndpointSpec endpoint, JsonNode listing, String name) {
        var lower = name.toLowerCase(Locale.ROOT);
        var near = ModelListingReader.read(listing).stream()
                .map(com.unbi.engine.llm.discovery.DiscoveredModel::id)
                .filter(id -> id.toLowerCase(Locale.ROOT).contains(lower))
                .limit(NEAR_MATCHES)
                .toList();
        var suffix = near.isEmpty() ? "" : " Did you mean: " + String.join(", ", near);
        return "%s does not serve '%s'.%s".formatted(endpoint.baseUrl(), name, suffix);
    }

    private static String note(EndpointSpec endpoint, String path, OptionalFetch.Result result) {
        return "%s%s — %s".formatted(
                endpoint.baseUrl(), path, result.answered() ? "answered" : result.problem());
    }

    /** A chips row holds a list, and blank when the gateway published nothing to put in it. */
    private static Object chips(List<String> values) {
        return values.isEmpty() ? "" : values;
    }

    private LlmModelInfo info(Fetched fetched) {
        var facts = fetched.facts().orElseThrow();
        return new LlmModelInfo(
                facts.id(),
                facts.name(),
                facts.canonicalSlug(),
                facts.contextWindow() == null ? 0 : facts.contextWindow(),
                facts.maxOutputTokens() == null ? 0 : facts.maxOutputTokens(),
                or(facts.inputPer1M()),
                or(facts.outputPer1M()),
                facts.price(),
                facts.capabilityNames(),
                String.join(", ", facts.inputModalities()),
                String.join(", ", facts.outputModalities()),
                facts.providers().size(),
                String.join(", ", facts.supportedParameters()),
                facts.released(),
                facts.knowledgeCutoff(),
                Boolean.TRUE.equals(facts.moderated()),
                facts.aliasOf(),
                facts.huggingFaceId(),
                facts.tokenizer(),
                facts.pricingNote(),
                facts.description());
    }

    /**
     * A number for the struct, where a published zero and an unpublished figure both read as 0.
     *
     * <p>Which is why the price travels as formatted text as well: {@code Types.NUMBER} round-trips
     * through the codec and consumers do arithmetic on it, so a sentinel for "unpublished" would
     * leak into a sum. The text field is the carrier of that distinction.
     */
    private static double or(Double value) {
        return value == null ? 0d : value;
    }

    /** One fetch, so the rows on the node and a run's outputs come from the same read. */
    private record Fetched(
            Optional<ModelFacts> facts, String verdict, List<String> notes, String fetchedAt) {}
}
