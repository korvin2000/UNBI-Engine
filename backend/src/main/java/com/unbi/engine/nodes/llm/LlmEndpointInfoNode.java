package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeAction;
import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeProbe;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.discovery.Amounts;
import com.unbi.engine.llm.discovery.EndpointFacts;
import com.unbi.engine.llm.discovery.GatewayDirectory;
import com.unbi.engine.llm.discovery.ModelListingReader;
import com.unbi.engine.llm.discovery.OptionalFetch;
import com.unbi.engine.llm.discovery.TimeBudget;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.ProviderProfile;
import com.unbi.engine.nodes.llm.model.LlmEndpointInfo;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Asks a gateway about itself, and shows the answer on the node.
 *
 * <p>Everything here was already reachable through a test button, and the test button was the wrong
 * shape for it. "Reachable — 443 models offered" is a green light that disappears the moment the
 * dialog closes; what a user actually needs while planning a batch is how much credit is left, how
 * many of those models take images, and whether today's free-model allowance is spent — facts that
 * belong on the canvas, next to the endpoint they describe, with the time they were read.
 *
 * <p>So the answer lands in {@code display} rows, which are inputs the editor draws and nobody can
 * type into. That is the whole reason a discovered fact is a widget value rather than an output: a
 * value persists in the saved workflow and is visible without a run, and an output is neither.
 *
 * <p>It is also a node with outputs, because a fact worth reading is a fact worth recording. The
 * struct can be previewed, tabulated beside a batch's results, or saved next to them — which is what
 * makes "what did this endpoint cost us last Tuesday" answerable at all.
 */
@Component
public class LlmEndpointInfoNode implements NodeDefinition, NodeProbe {

    private final GatewayDirectory gateways;
    private final EndpointProfiles profiles;
    private final LlmEndpointNode endpoints;

    public LlmEndpointInfoNode(
            GatewayDirectory gateways, EndpointProfiles profiles, LlmEndpointNode endpoints) {
        this.gateways = gateways;
        this.profiles = profiles;
        this.endpoints = endpoints;
    }

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.endpoint_info", "Endpoint Info")
                .in(LlmTypes.CATEGORY, "Connection")
                .icon("key")
                .accent(LlmTypes.ACCENT)
                .describedAs("Reads what a gateway publishes about itself and the key it was asked "
                        + "with: how many models it serves, what they can do, what is left of the "
                        + "credit. Press the bulb to fetch; the rows show when they were read.")
                .action(NodeAction.discover("fetch", "Fetch this endpoint's own facts from the gateway", "bulb"))
                .socket("endpoint", "Endpoint", LlmTypes.ENDPOINT)
                .display("fetchedAt", "Fetched", Widget.Display.line())
                .hint("A fact about a gateway is only as good as its age.")
                .display("gateway", "Gateway", Widget.Display.line())
                .display("reachable", "Reachable", Types.BOOLEAN, Widget.Display.line())
                .display("modelsServed", "Models Served", Widget.Display.line())
                .display("keyLabel", "Key", Widget.Display.line())
                .display("credits", "Credits Remaining", Widget.Display.line())
                .hint("What the key's own limit leaves, which is not the same as the account balance.")
                .display("usageToday", "Usage Today", Widget.Display.line())
                .section("Usage")
                .advancedDisplay("usageTotal", "Usage Total", Widget.Display.line())
                .advancedDisplay("usageWeekly", "Usage This Week", Widget.Display.line())
                .advancedDisplay("usageMonthly", "Usage This Month", Widget.Display.line())
                .advancedDisplay("byokUsage", "BYOK Usage", Widget.Display.line())
                .hint("Spend through your own upstream keys, which this gateway meters separately.")
                .advancedDisplay("byokInLimit", "BYOK Counts Toward Limit", Types.BOOLEAN,
                        Widget.Display.line())
                .advancedDisplay("limitResets", "Limit Resets", Widget.Display.line())
                .hint("How often the key's own cap refills, in the gateway's own words.")
                .advancedDisplay("freeTier", "Free Tier", Types.BOOLEAN, Widget.Display.line())
                .advancedDisplay("freeRequests", "Free-Model Requests", Widget.Display.line())
                .advancedDisplay("expires", "Key Expires", Widget.Display.line())
                .section("Balance")
                .advancedDisplay("balance", "Account Balance", Widget.Display.line())
                .hint("From the credits endpoint, which most gateways do not have and some keys may "
                        + "not read. The arithmetic is shown so it can be checked.")
                .section("Catalogue")
                .advancedDisplay("modelsWithVision", "Models With Vision", Widget.Display.line())
                .advancedDisplay("modelsWithReasoning", "Models With Reasoning", Widget.Display.line())
                .advancedDisplay("modelsWithTools", "Models With Tools", Widget.Display.line())
                .advancedDisplay("modelsWithStructuredOutput", "Models With Structured Output",
                        Widget.Display.line())
                .advancedDisplay("modelsWithFileInput", "Models Taking Files", Widget.Display.line())
                .advancedDisplay("inputModalities", "Input Modalities Seen",
                        LlmTypes.TEXT_LIST, Widget.Display.chips())
                .section("")
                .out("info", "Info", LlmTypes.ENDPOINT_INFO)
                .hint("The same facts as data — preview it, tabulate it, save it beside a batch.")
                .out("summary", "Summary", Types.TEXT)
                .out("models", "Model Ids", LlmTypes.TEXT_LIST)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var endpoint = context.require("endpoint", EndpointSpec.class);
        var fetched = fetch(endpoint);
        fetched.notes().forEach(context::log);
        context.log(fetched.verdict());

        var info = info(endpoint, fetched);
        var summary = info.toString();
        context.stream("summary", summary);
        context.output("info", info);
        context.output("summary", summary);
        context.output("models", fetched.facts().modelIds());
        context.progress(1, info.summaryLine());
    }

    /**
     * The same fetch the run makes, with the answer written into the rows.
     *
     * <p>One function for both, so a node showing green rows and a run reading the same gateway
     * cannot disagree — the property {@link EndpointProfiles#build} exists to hold for the endpoint
     * itself, applied to what is read through it.
     */
    @Override
    public Result probe(String action, Request request) {
        if (!"fetch".equals(action)) {
            return Result.failed("This node has no action called " + action);
        }
        EndpointSpec endpoint;
        try {
            endpoint = endpoints.resolve(request.source("endpoint"));
        } catch (RuntimeException misconfigured) {
            return Result.failed("The endpoint is not usable yet: " + misconfigured.getMessage());
        }
        var blocked = profiles.credentialProblem(endpoint);
        if (blocked.isPresent()) {
            return Result.failed(blocked.get());
        }

        var fetched = fetch(endpoint);
        var facts = fetched.facts();
        var result = fetched.reachable()
                ? Result.ok(fetched.verdict())
                : Result.problem(fetched.verdict());
        fetched.notes().forEach(result::detail);

        // Every row, every time: a row this fetch cannot fill has to be cleared, or it keeps
        // showing what a different endpoint said under a timestamp claiming it is fresh.
        var rows = DisplayRows.blank(descriptor());
        rows.put("fetchedAt", fetched.fetchedAt());
        rows.put("gateway", gateway(endpoint));
        rows.put("reachable", fetched.reachable());
        rows.put("modelsServed", facts.modelsServedText());
        rows.put("keyLabel", facts.keyLabel());
        rows.put("credits", facts.creditsRemaining());
        rows.put("usageToday", Amounts.money(facts.usageDaily()));
        rows.put("usageTotal", Amounts.money(facts.usage()));
        rows.put("usageWeekly", Amounts.money(facts.usageWeekly()));
        rows.put("usageMonthly", Amounts.money(facts.usageMonthly()));
        rows.put("byokUsage", Amounts.money(facts.byokUsage()));
        rows.put("byokInLimit", facts.includeByokInLimit() == null ? "" : facts.includeByokInLimit());
        rows.put("limitResets", facts.limitReset());
        rows.put("freeTier", facts.freeTier() == null ? "" : facts.freeTier());
        rows.put("freeRequests", facts.freeRequests());
        rows.put("expires", facts.expires());
        rows.put("balance", facts.balance().isEmpty() ? balanceNote(facts) : facts.balance());
        rows.put("modelsWithVision", facts.capabilityCount(Capability.VISION));
        rows.put("modelsWithReasoning", facts.capabilityCount(Capability.REASONING));
        rows.put("modelsWithTools", facts.capabilityCount(Capability.TOOLS));
        rows.put("modelsWithStructuredOutput", facts.capabilityCount(Capability.JSON_SCHEMA));
        rows.put("modelsWithFileInput", facts.capabilityCount(Capability.FILES));
        rows.put("inputModalities", facts.inputModalities().isEmpty() ? "" : facts.inputModalities());
        rows.forEach(result::value);
        return result.build();
    }

    /**
     * Why the balance row is empty, as specifically as the gateway allows.
     *
     * <p>Measured: {@code /credits} answers 403 for a key that is not a management key, which is
     * most of them — and the same body that refuses the balance says {@code is_management_key:
     * false}, which is the sentence that ends the investigation rather than starting one into a
     * credential that is fine. The sharpening is conditioned on the refusal actually being about
     * the key, because a 404, a spent budget or a timeout blamed on the key's kind would send the
     * reader after the wrong setting — the misdirection {@link OptionalFetch} exists to prevent.
     */
    private static String balanceNote(EndpointFacts facts) {
        if (OptionalFetch.NOT_READABLE.equals(facts.balanceProblem())
                && Boolean.FALSE.equals(facts.managementKey())) {
            return "not readable: this key is not a management key";
        }
        return facts.balanceProblem();
    }

    // --- The one fetch ---------------------------------------------------------

    /**
     * The calls, in the order the answer depends on them.
     *
     * <p>Priority order is what makes one shared budget safe. The credential probe decides
     * {@code reachable} and therefore goes first with the whole allowance; the listing and the
     * balance spend what is left, and a gateway slow enough to exhaust it costs the extras rather
     * than the verdict.
     */
    private Fetched fetch(EndpointSpec endpoint) {
        var profile = ProviderProfile.resolve(endpoint.profile());
        var budget = TimeBudget.forProbe(endpoint);
        var notes = new ArrayList<String>();

        var probe = OptionalFetch.required(gateways, endpoint, profile.probePath(), budget.remaining());
        notes.add(note(endpoint, profile.probePath(), probe));

        // On OpenRouter the probe is /key and the listing is a second call; everywhere else the
        // probe path *is* the listing, and asking twice for the same 735 KB would be the only
        // difference between the two gateways nobody asked for.
        JsonNode modelsBody = null;
        if (probe.answered()) {
            if (profile.probePath().equals(profile.modelsPath())) {
                modelsBody = probe.body();
            } else {
                var listing = OptionalFetch.of(gateways, endpoint, profile.modelsPath(), budget);
                modelsBody = listing.body();
                notes.add(note(endpoint, profile.modelsPath(), listing));
            }
        }

        // A gateway with no credits endpoint publishes none whether or not it answered at all, and
        // OptionalFetch makes no call for a blank path — so the blank case keeps its own sentence
        // rather than being told a call nobody was going to make did not come back.
        var credits = probe.answered() || profile.creditsPath().isBlank()
                ? OptionalFetch.of(gateways, endpoint, profile.creditsPath(), budget)
                : new OptionalFetch.Result(null, "the endpoint did not answer", profile.creditsPath());
        if (!credits.path().isBlank()) {
            notes.add(note(endpoint, credits.path(), credits));
        }

        var facts = EndpointFacts.read(probe.body(), credits.body(), credits.problem(), modelsBody);
        return new Fetched(
                facts,
                probe.answered(),
                verdict(profile, probe, modelsBody),
                List.copyOf(notes),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    }

    private static String verdict(ProviderProfile profile, OptionalFetch.Result probe, JsonNode models) {
        if (!probe.answered()) {
            return probe.problem();
        }
        if (models == null) {
            return "%s answered.".formatted(profile.label());
        }
        var count = ModelListingReader.count(models);
        return "%s answered — %s model%s served.".formatted(
                profile.label(), Amounts.count(count), count == 1 ? "" : "s");
    }

    private static String note(EndpointSpec endpoint, String path, OptionalFetch.Result result) {
        return "%s%s — %s".formatted(
                endpoint.baseUrl(), path, result.answered() ? "answered" : result.problem());
    }

    private static String gateway(EndpointSpec endpoint) {
        return "%s · %s".formatted(ProviderProfile.resolve(endpoint.profile()).label(), endpoint.baseUrl());
    }

    private LlmEndpointInfo info(EndpointSpec endpoint, Fetched fetched) {
        var facts = fetched.facts();
        return new LlmEndpointInfo(
                ProviderProfile.resolve(endpoint.profile()).label(),
                endpoint.baseUrl(),
                fetched.reachable(),
                fetched.fetchedAt(),
                facts.modelsServed() == null ? 0 : facts.modelsServed(),
                facts.keyLabel(),
                or(facts.limit()),
                or(facts.limitRemaining()),
                or(facts.usage()),
                or(facts.usageDaily()),
                Boolean.TRUE.equals(facts.freeTier()),
                facts.freeRequestsUsed() == null ? 0 : facts.freeRequestsUsed(),
                facts.freeRequestsLimit() == null ? 0 : facts.freeRequestsLimit(),
                facts.capabilityCounts().getOrDefault(Capability.VISION, 0),
                facts.capabilityCounts().getOrDefault(Capability.REASONING, 0),
                facts.capabilityCounts().getOrDefault(Capability.TOOLS, 0),
                facts.capabilityCounts().getOrDefault(Capability.JSON_SCHEMA, 0),
                facts.capabilityCounts().getOrDefault(Capability.FILES, 0),
                String.join(", ", facts.inputModalities()));
    }

    private static double or(Double value) {
        return value == null ? 0d : value;
    }

    /** One fetch, so the probe and the run cannot read the gateway differently. */
    private record Fetched(
            EndpointFacts facts, boolean reachable, String verdict, List<String> notes, String fetchedAt) {}
}
