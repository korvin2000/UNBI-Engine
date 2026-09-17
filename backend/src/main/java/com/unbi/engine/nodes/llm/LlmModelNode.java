package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeAction;
import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeProbe;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.PortType;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.discovery.DiscoveredModel;
import com.unbi.engine.llm.discovery.GatewayDirectory;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.Pricing;
import com.unbi.engine.llm.spec.ProviderProfile;
import com.unbi.engine.llm.spec.ProviderRouting;
import com.unbi.engine.llm.spec.Reasoning;
import com.unbi.engine.llm.spec.SamplingParams;
import com.unbi.engine.llm.spec.WebSearchMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * One model on one endpoint: what it can do, what it costs, and how it thinks.
 *
 * <p>Capabilities are still <em>declared</em> rather than assumed, and that has not changed: nothing
 * a gateway says proves a model honours a parameter, so the list held against every request is the
 * one written down here. What has changed is where the first draft of that list comes from. Typing
 * a model name from memory and ticking eight boxes by guesswork is not "declaring" anything — it is
 * the same guess with more steps — so the two buttons beside the Model field ask the gateway, and
 * write the answer into the fields where it stays visible, editable and undoable.
 *
 * <p>Discovery never invents. A gateway that says nothing about pricing leaves the price alone; one
 * that says nothing about reasoning leaves the dialect alone. A blank field is an honest "nobody
 * knows", and it is a far better input to the capability check than a plausible number.
 */
@Component
public class LlmModelNode implements NodeDefinition, NodeProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewayDirectory gateways;
    private final EndpointProfiles profiles;
    private final LlmEndpointNode endpoints;

    public LlmModelNode(GatewayDirectory gateways, EndpointProfiles profiles, LlmEndpointNode endpoints) {
        this.gateways = gateways;
        this.profiles = profiles;
        this.endpoints = endpoints;
    }

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.model", "LLM Model")
                .in(LlmTypes.CATEGORY, "Connection")
                .icon("chip")
                .accent(LlmTypes.ACCENT)
                .describedAs("Selects a model and declares its capabilities, reasoning, pricing and "
                        + "provider routing. The Model list is fetched from the wired endpoint; the "
                        + "bulb checks the chosen model and fills these settings in from it.")
                .action(NodeAction.automatic("models", "List the models this endpoint serves", "model"))
                .action(NodeAction.discover("check", "Check this model and fill in its settings from the endpoint", "bulb"))
                .socket("endpoint", "Endpoint", LlmTypes.ENDPOINT)
                .field("model", "Model", Types.TEXT, Widget.Dropdown.discovered(), "")
                .hint("Pick from what the endpoint serves, or type a name it will recognise. The "
                        + "bulb in the header fills in everything below from the endpoint's listing.")
                .optionalSocket("sampling", "Generation Params", LlmTypes.SAMPLING)
                .hint("Defaults for every call; a request may override them.")
                .setting("capabilities", "Capabilities", PortType.list(Types.TEXT), capabilities(), null)
                .hint("What a request is allowed to ask for. Anything unticked is refused loudly "
                        + "rather than dropped silently on the wire.")
                .setting("maxOutputTokens", "Max Output Tokens", Types.NUMBER,
                        Widget.NumberField.optional(0, 1_000_000, 256, "tok", "no limit"), null)
                .hint("Leave it empty for no limit at all — nothing is then sent, and the model "
                        + "writes as much as it is willing to. A number caps every request through "
                        + "this model, however much one of them asks for.")
                .section("Output limits")
                .advancedSetting("maxTokensParam", "Output Limit Field", Types.TEXT, Widget.Dropdown.of(
                        "auto", "Automatic",
                        "max_tokens", "max_tokens",
                        "max_completion_tokens", "max_completion_tokens",
                        "none", "Send no limit"), "auto")
                .hint("Automatic sends max_completion_tokens to a model that declares reasoning and "
                        + "max_tokens to everything else, which is the rule every gateway here "
                        + "follows. Only override it for one that does not.")
                .advancedSetting("contextWindow", "Context Window", Types.NUMBER,
                        new Widget.NumberField(1024, 4_000_000, 1024, "tok", false), 128_000d)
                .section("Protocol")
                .advancedSetting("apiFormat", "API Format", Types.TEXT, Widget.Dropdown.of(
                        "profile", "From gateway",
                        "chat_completions", "Chat Completions",
                        "responses", "Responses"), "profile")
                .advancedSetting("reasoningDialect", "Reasoning Dialect", Types.TEXT, Widget.Dropdown.of(
                        "none", "Say nothing",
                        "reasoning_effort", "reasoning_effort",
                        "reasoning", "reasoning object",
                        "thinking", "thinking budget"), "none")
                .hint("Say nothing leaves the model's default — which on many models is "
                        + "'reason, and bill it at the output rate'.")
                .advancedSetting("reasoningEnabled", "Reasoning On", Types.BOOLEAN, new Widget.Toggle(), false)
                .onlyWhen("reasoningDialect", "reasoning_effort", "reasoning", "thinking")
                .advancedSetting("reasoningEffort", "Reasoning Effort", Types.TEXT, Widget.Dropdown.of(
                        "minimal", "Minimal", "low", "Low", "medium", "Medium", "high", "High",
                        "xhigh", "XHigh", "max", "Max"), "medium")
                .onlyWhen("reasoningDialect", "reasoning_effort", "reasoning", "thinking")
                .advancedSetting("webSearchMode", "Web Search Mode", Types.TEXT, Widget.Dropdown.of(
                        "none", "Off",
                        "responses_tool", "Responses tool (verifiable)",
                        "hosted", "Hosted search model",
                        "online", "Online suffix",
                        "plugin", "Web plugin"), "none")
                .section("Cost")
                .advancedSetting("inputPer1M", "Input Price", Types.NUMBER,
                        new Widget.NumberField(0, 1000, 0.01, "$/M", false), 0d)
                .advancedSetting("outputPer1M", "Output Price", Types.NUMBER,
                        new Widget.NumberField(0, 1000, 0.01, "$/M", false), 0d)
                .section("Routing")
                .advancedSetting("providerOrder", "Provider Order", Types.TEXT,
                        Widget.TextField.of("deepinfra/fp8, together, …"), "")
                .hint("One model id can be served by many hosts whose sampler support differs.")
                .advancedSetting("requireParameters", "Require Parameter Support", Types.BOOLEAN,
                        new Widget.Toggle(), false)
                .hint("Turns a silently dropped sampler into an error. Worth it for any call "
                        + "carrying more than a temperature.")
                .advancedSetting("tags", "Tags", Types.TEXT, Widget.TextField.of("fast, cheap, german"), "")
                .section("Escape hatch")
                .advancedSetting("extraBody", "Extra Request Body", Types.TEXT,
                        Widget.TextField.code("{ }", 4).withEditor(), "")
                .hint("JSON spread onto every request, for anything this node has no field for.")
                .section("")
                .out("model", "Model", LlmTypes.MODEL)
                .build();
    }

    @Override
    public void execute(NodeContext context) {
        var endpoint = context.require("endpoint", EndpointSpec.class);
        var model = modelFrom(context, endpoint);

        context.log("%s on %s (%s)".formatted(
                model.name(), endpoint.baseUrl(), model.apiFormat().wireName()));
        context.log(model.capabilities().isEmpty()
                ? "No capabilities declared: structured output, attachments and search will be refused."
                : "Capabilities: " + model.capabilities().stream().map(Capability::wireName).toList());
        context.log(model.hasOutputCeiling()
                ? "Output ceiling: %,d tokens, sent as %s".formatted(
                        model.maxOutputTokens(), model.outputLimitField().orElse("nothing"))
                : "No output ceiling: the model's own limit applies.");
        if (!model.pricing().isFree()) {
            context.log("Priced at $%.2f in / $%.2f out per 1M tokens"
                    .formatted(model.pricing().inputPer1M(), model.pricing().outputPer1M()));
        }
        context.output("model", model);
        context.progress(1, model.name());
    }

    /**
     * Lists what the endpoint serves, and checks one model against that list.
     *
     * <p>Both actions go through the endpoint the graph actually wires in, resolved from that node's
     * profile by {@link LlmEndpointNode#resolve} — so "the model is not there" can never mean "I
     * looked somewhere else".
     */
    @Override
    public Result probe(String action, Request request) {
        var wired = request.source("endpoint");
        if (!wired.isPresent()) {
            return Result.failed("Wire an LLM Endpoint into this node first — there is nothing to ask.");
        }

        EndpointSpec endpoint;
        try {
            endpoint = endpoints.resolve(wired);
        } catch (RuntimeException misconfigured) {
            return Result.failed("The endpoint is not usable yet: " + misconfigured.getMessage());
        }
        var blocked = profiles.credentialProblem(endpoint);
        if (blocked.isPresent()) {
            return Result.failed(blocked.get());
        }

        List<DiscoveredModel> found;
        try {
            found = gateways.models(endpoint);
        } catch (LlmFailure failure) {
            return Result.failed(failure.describe());
        } catch (RuntimeException unexpected) {
            return Result.failed(unexpected.getMessage() == null
                    ? unexpected.getClass().getSimpleName()
                    : unexpected.getMessage());
        }

        return switch (action) {
            case "models" -> listing(found, endpoint);
            case "check" -> check(
                    request.text("model").trim(), request.text("reasoningEffort"), found, endpoint);
            default -> Result.failed("This node has no action called " + action);
        };
    }

    private static Result listing(List<DiscoveredModel> found, EndpointSpec endpoint) {
        if (found.isEmpty()) {
            return Result.problem("%s listed no models.".formatted(endpoint.baseUrl()))
                    .detail("The endpoint answered, but with nothing this engine could read as a "
                            + "model list. Type the model name instead.")
                    .build();
        }
        return Result.ok("%d model%s available.".formatted(found.size(), found.size() == 1 ? "" : "s"))
                .options("model", found.stream()
                        .map(model -> new Widget.Option(model.id(), model.describe()))
                        .toList())
                .detail("From " + endpoint.baseUrl())
                .build();
    }

    /**
     * Confirms a model exists and hands back everything the gateway said about it.
     *
     * <p>Only fields the gateway actually answered are returned; the rest are left exactly as the
     * user had them. That asymmetry is deliberate — a discovery that blanked a carefully set price
     * because this gateway does not publish prices would be a data-loss bug wearing a feature's
     * clothes.
     */
    private static Result check(
            String name, String currentEffort, List<DiscoveredModel> found, EndpointSpec endpoint) {
        if (name.isEmpty()) {
            return Result.failed("Pick or type a model name first.");
        }
        var match = found.stream().filter(model -> model.id().equalsIgnoreCase(name)).findFirst();
        if (match.isEmpty()) {
            var near = found.stream()
                    .filter(model -> model.id().toLowerCase(java.util.Locale.ROOT)
                            .contains(name.toLowerCase(java.util.Locale.ROOT)))
                    .limit(4)
                    .map(DiscoveredModel::id)
                    .toList();
            return Result.problem("%s does not serve '%s'.".formatted(endpoint.baseUrl(), name))
                    .detail(near.isEmpty()
                            ? "Open the Model list to see what it does serve."
                            : "Did you mean: " + String.join(", ", near))
                    .build();
        }

        var model = match.get();
        var result = Result.ok("%s is available.".formatted(model.id()))
                .detail(model.summary().isBlank() ? "The gateway published no details." : model.summary())
                .options("model", found.stream()
                        .map(candidate -> new Widget.Option(candidate.id(), candidate.describe()))
                        .toList())
                .value("contextWindow", model.contextWindow() == null ? null : (double) model.contextWindow())
                .value("maxOutputTokens", model.maxOutputTokens() == null
                        ? null
                        : (double) model.maxOutputTokens())
                .value("inputPer1M", model.inputPer1M())
                .value("outputPer1M", model.outputPer1M());

        if (!model.capabilities().isEmpty()) {
            result.value("capabilities", model.capabilities().stream()
                    .map(Capability::wireName)
                    .sorted()
                    .toList());
        }
        if (model.apiFormat() != null) {
            result.value("apiFormat", model.apiFormat().wireName());
        }
        if (model.maxTokensParam() != null) {
            result.value("maxTokensParam", model.maxTokensParam().wireName());
        }
        if (model.reasoningDialect() != null) {
            result.value("reasoningDialect", model.reasoningDialect().wireName());
            // The switch travels with the dialect, always. A dialect on its own means "say
            // something about reasoning", and with the switch left off that something is "turn it
            // off" — which a model that reasons mandatorily answers with a 400 on every call.
            result.value("reasoningEnabled", model.reasoningOn() == null ? Boolean.TRUE : model.reasoningOn());
            if (!model.efforts().isEmpty()) {
                result.options("reasoningEffort", model.efforts().stream()
                        .map(effort -> new Widget.Option(effort, capitalise(effort)))
                        .toList());
                result.detail("Reasoning efforts: " + String.join(", ", model.efforts()));
            }
            // Only when it has to change: a tier this model accepts and the user chose is theirs.
            model.effortFor(currentEffort).ifPresent(effort -> result.value("reasoningEffort", effort));
        }
        return result.build();
    }

    /** The model this node's values describe. Shared by the run and by everything that inspects it. */
    static ModelSpec modelFrom(NodeContext context, EndpointSpec endpoint) {
        var name = context.text("model").trim();
        if (name.isBlank()) {
            throw new IllegalStateException("This node needs the model name the endpoint will recognise.");
        }

        var profile = ProviderProfile.resolve(endpoint.profile());
        var apiFormatRaw = context.text("apiFormat");
        var apiFormat = apiFormatRaw.isBlank() || "profile".equalsIgnoreCase(apiFormatRaw.trim())
                ? (endpoint.defaultApiFormat() == null ? profile.defaultApiFormat() : endpoint.defaultApiFormat())
                : ApiFormat.of(apiFormatRaw);
        endpoint.validateApiFormat(apiFormat);

        var capabilities = new java.util.LinkedHashSet<Capability>();
        NodeValues.strings(context, "capabilities")
                .forEach(raw -> Capability.byWireName(raw).ifPresent(capabilities::add));

        var reasoning = new Reasoning(
                Reasoning.Dialect.of(context.text("reasoningDialect")),
                context.flag("reasoningEnabled"),
                Reasoning.Effort.of(context.text("reasoningEffort")),
                0,
                true);

        var routing = new ProviderRouting(
                ProviderRouting.slugs(context.text("providerOrder")),
                List.of(),
                List.of(),
                null,
                context.flag("requireParameters") ? Boolean.TRUE : null,
                null);

        return new ModelSpec(
                endpoint,
                name,
                apiFormat,
                capabilities,
                reasoning,
                WebSearchMode.of(context.text("webSearchMode")),
                new Pricing(context.number("inputPer1M"), context.number("outputPer1M"), -1, -1),
                NodeValues.intOr(context, "contextWindow", 128_000),
                // Blank means no ceiling, which reaches here as zero and stays zero.
                NodeValues.intOr(context, "maxOutputTokens", 0),
                ModelSpec.MaxTokensParam.of(context.text("maxTokensParam")),
                context.optional("sampling", SamplingParams.class).orElse(SamplingParams.UNSET),
                routing,
                NodeValues.csv(context.text("tags")),
                extraBody(context.text("extraBody")));
    }

    /**
     * Parses the extra-body field, failing here rather than on the wire.
     *
     * <p>A malformed escape hatch that only surfaces as a gateway 400 is the worst version of this
     * feature: the message comes back from someone else's server, about a body the user never saw.
     */
    private static Map<String, Object> extraBody(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            var parsed = MAPPER.readTree(raw);
            if (!parsed.isObject()) {
                throw new IllegalStateException("Extra Request Body must be a JSON object.");
            }
            var values = new LinkedHashMap<String, Object>();
            parsed.propertyNames().forEach(field -> values.put(field, parsed.get(field)));
            return values;
        } catch (IllegalStateException alreadyExplained) {
            throw alreadyExplained;
        } catch (RuntimeException malformed) {
            throw new IllegalStateException("Extra Request Body is not valid JSON: " + malformed.getMessage());
        }
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static Widget capabilities() {
        var pairs = new java.util.ArrayList<String>();
        for (var capability : Capability.values()) {
            pairs.add(capability.wireName());
            pairs.add(capability.label());
        }
        return Widget.MultiSelect.of(pairs.toArray(String[]::new));
    }
}
