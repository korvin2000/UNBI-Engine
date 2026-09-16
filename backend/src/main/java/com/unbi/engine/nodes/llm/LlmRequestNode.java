package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeAction;
import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeProbe;
import com.unbi.engine.core.node.ValueContext;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.discovery.GatewayDirectory;
import com.unbi.engine.llm.provider.StreamSink;
import com.unbi.engine.llm.runtime.CallPolicy;
import com.unbi.engine.llm.runtime.CapabilityCheck;
import com.unbi.engine.llm.runtime.LlmCaller;
import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatMessage;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.ResponseFormat;
import com.unbi.engine.llm.spec.SamplingParams;
import com.unbi.engine.nodes.llm.model.LlmResult;
import com.unbi.engine.nodes.llm.model.LlmVariables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Asks the model — once, or once per item — and hands the answers on.
 *
 * <p>One node for both, and the reason is the thing that used to be confusing: a "request" node
 * and a "batch" node with different sockets, different template rules and different outputs asked
 * the user to decide up front which of two shapes their problem had. The shape is already visible
 * in what is wired in. A single text on System Prompt is one instruction; a list of texts is
 * several to try. A single value on Data is one request; a list is one request per entry. Every
 * input that can carry several values works this way, and {@link RequestPlan} turns what is wired
 * in into the requests to make — three system prompts against one document, one file per request,
 * all files in one request, X prompts by Y prompts — with one setting saying whether long lists
 * pair up or cross.
 *
 * <p>The prompts are fields as well as sockets, so the simplest workflow is still three nodes with
 * the prompt typed straight in. Both prompts are templates: {@code {{item}}}, {@code {{index}}},
 * {@code {{count}}}, a file's {@code {{name}}}, a dataset row's columns and anything from a
 * Variables node are filled in per request.
 *
 * <p>Two of the toggles are on by default and both are about refusing an answer that only looks
 * like one: a truncated answer is a well-formed success carrying half a document, and an answer
 * from a model asked to search that did not search is fluent, cited and invented.
 */
@Component
public class LlmRequestNode implements NodeDefinition, NodeProbe {

    /** Preset node type whose saved values stock the prompt library on both prompt fields. */
    static final String PROMPT_LIBRARY = "llm.prompt";

    /** Which value inside those presets holds the text. */
    static final String PROMPT_LIBRARY_KEY = "template";

    /** Beyond this many requests at once the endpoint's own limit is the one that matters anyway. */
    private static final int MAX_PARALLEL = 16;

    private final LlmCaller caller;
    private final GatewayDirectory gateways;
    private final LlmEndpointNode endpoints;

    public LlmRequestNode(LlmCaller caller, GatewayDirectory gateways, LlmEndpointNode endpoints) {
        this.caller = caller;
        this.gateways = gateways;
        this.endpoints = endpoints;
    }

    @Override
    public NodeDescriptor descriptor() {
        return NodeDescriptor.of("llm.request", "LLM Request")
                .in(LlmTypes.CATEGORY, "Request")
                .icon("sparkles")
                .accent(LlmTypes.ACCENT)
                .describedAs("Sends a request and returns the answer, its token usage and its cost. "
                        + "Wire a list into any prompt, into Data or into Attachments and it becomes "
                        + "one request per entry, with the answers in the same order.")
                .action(NodeAction.check("check", "Check this request against the model", "bulb"))
                .socket("model", "Model", LlmTypes.MODEL)
                .field("system", "System Prompt", LlmTypes.PROMPTS,
                        Widget.TextField.prose("You are a careful assistant.", 3,
                                PROMPT_LIBRARY, PROMPT_LIBRARY_KEY), "")
                .hint("Instructions that hold for every request. Wire a list of texts to try several "
                        + "— one request per variant.")
                .field("user", "User Prompt", LlmTypes.PROMPTS,
                        Widget.TextField.prose("Ask something…", 4,
                                PROMPT_LIBRARY, PROMPT_LIBRARY_KEY), "")
                .hint("A template: {{item}}, {{index}}, {{count}}, a file's {{name}}, a dataset row's "
                        + "columns and any Variables are filled in per request. A list of texts makes "
                        + "one request per prompt.")
                .optionalSocket("items", "Data", Types.ANY)
                .hint("One request per entry: files, dataset rows, search matches, strings. Each is "
                        + "bound as {{item}}; a single value is a single request.")
                .optionalSocket("attachments", "Attachments", LlmTypes.ATTACHMENT_LIST)
                .hint("Images, documents and text files from Attach Files.")
                .setting("attachMode", "Attach", Types.TEXT, Widget.Dropdown.of(
                        "all", "All files in one request",
                        "each", "One request per file"), "all")
                .optionalSocket("variables", "Variables", LlmTypes.VARIABLES)
                .hint("Named values every request's templates can read.")
                .optionalSocket("sampling", "Generation Params", LlmTypes.SAMPLING)
                .hint("Overrides whatever the model node set.")
                .setting("responseFormat", "Response Format", Types.TEXT, Widget.Dropdown.of(
                                "text", "Text",
                                "json_object", "JSON object",
                                "json_schema", "JSON schema")
                        // Only the formats the wired model declares. A format this target will
                        // silently ignore is not a choice, it is a trap: the answer comes back
                        // well-formed prose and everything downstream parses it as JSON.
                        .narrowedBy(Widget.Narrowing.from("model", "capabilities", "text")), "text")
                .setting("jsonSchema", "JSON Schema", Types.TEXT,
                        Widget.TextField.code("{ \"type\": \"object\" }", 4).withEditor(), "")
                .onlyWhen("responseFormat", "json_schema")
                .advancedSetting("schemaName", "Schema Name", Types.TEXT,
                        Widget.TextField.of("response"), "response")
                .onlyWhen("responseFormat", "json_schema")
                .advancedSetting("combine", "Combine Lists", Types.TEXT, Widget.Dropdown.of(
                        "pair", "Pair up by position",
                        "cross", "Every combination"), "pair")
                .hint("When more than one input is a list: match the first with the first, or cross "
                        + "every system prompt with every user prompt with every item.")
                .advancedSetting("parallel", "Parallel Requests", Types.NUMBER,
                        new Widget.NumberField(1, MAX_PARALLEL, 1, "", false), 4d)
                .hint("For a batch. The endpoint profile's own limit still applies and is the "
                        + "tighter of the two.")
                .advancedSetting("continueOnError", "Continue On Error", Types.BOOLEAN, new Widget.Toggle(), true)
                .hint("For a batch: a failed request is counted and the rest still run. The node "
                        + "fails only when nothing answered.")
                .advancedSetting("strictTemplates", "Fail On Missing Values", Types.BOOLEAN,
                        new Widget.Toggle(), true)
                .hint("A {{name}} nothing bound fails the request rather than rendering as a hole. "
                        + "Write \\{{ for a literal brace.")
                .advancedSetting("webSearch", "Web Search", Types.BOOLEAN, new Widget.Toggle(), false)
                .advancedSetting("requireSearchEvidence", "Require Search Evidence", Types.BOOLEAN,
                        new Widget.Toggle(), false)
                .onlyWhen("webSearch", "true")
                .hint("Rejects an answer with no provider-side proof it searched. URLs in the "
                        + "prose are not proof.")
                .advancedSetting("strict", "Refuse Unsupported Settings", Types.BOOLEAN,
                        new Widget.Toggle(), true)
                .hint("Off degrades instead, and says so in the log.")
                .advancedSetting("failOnTruncation", "Fail If Cut Off", Types.BOOLEAN, new Widget.Toggle(), true)
                .advancedSetting("retries", "Attempts", Types.NUMBER,
                        new Widget.NumberField(1, 6, 1, "", false), 3d)
                .hint("Per request. Only transient failures are retried; a rejected request is not.")
                .out("text", "Text", Types.TEXT)
                .hint("The answer. For a batch, every answer, separated by a blank line.")
                .out("results", "Results", LlmTypes.RESULT_LIST)
                .hint("One per request, in order, with usage and cost.")
                .out("texts", "Texts", LlmTypes.TEXT_LIST)
                .out("cost", "Cost (USD)", Types.NUMBER)
                .out("failures", "Failures", Types.NUMBER)
                .build();
    }

    @Override
    public void execute(NodeContext context) throws InterruptedException {
        var model = context.require("model", ModelSpec.class);
        var requests = RequestPlan.plan(inputsOf(context));
        var policy = new CallPolicy(
                context.flag("strict"),
                Math.max(1, context.integer("retries")),
                1000,
                context.flag("failOnTruncation"),
                context.flag("requireSearchEvidence"));
        var sampling = context.optional("sampling", SamplingParams.class)
                .orElse(SamplingParams.UNSET)
                .over(model.sampling());

        List<Outcome> outcomes;
        if (requests.size() == 1) {
            context.progress(0, "Asking " + model.name());
            var call = callFor(context, model, requests.getFirst(), sampling);
            var sink = new NodeStreamSink(context, "text", call.effectiveMaxOutputTokens());
            outcomes = List.of(ask(context, call, policy, sink, requests.getFirst().label(1), false));
        } else {
            context.log("%d requests planned.".formatted(requests.size()));
            outcomes = askAll(context, model, requests, sampling, policy);
        }

        // A cancelled batch is cancelled, not a batch where everything happened to fail.
        context.checkCancelled();
        emit(context, model, outcomes);
    }

    // --- Planning ------------------------------------------------------------

    private static RequestPlan.Inputs inputsOf(NodeContext context) {
        var attachments = context.rawInput("attachments") == null
                ? List.<Attachment>of()
                : context.listOf("attachments", Attachment.class);
        var shared = context.optional("variables", LlmVariables.class).orElse(LlmVariables.EMPTY).asMap();
        return new RequestPlan.Inputs(
                textsOf(context.rawInput("system"), "System Prompt"),
                textsOf(context.rawInput("user"), "User Prompt"),
                itemsOf(context.rawInput("items")),
                attachments,
                "each".equals(context.text("attachMode")),
                RequestPlan.Combine.of(context.text("combine")),
                shared,
                context.flag("strictTemplates"));
    }

    /** A prompt field holds one text; a wired list holds several. Either way, a list. */
    private static List<String> textsOf(Object raw, String label) {
        return switch (raw) {
            case null -> List.of();
            case String text -> List.of(text);
            case Collection<?> texts -> {
                var found = new ArrayList<String>(texts.size());
                for (var entry : texts) {
                    if (!(entry instanceof String text)) {
                        throw new IllegalStateException("%s should be text or a list of texts, but holds %s."
                                .formatted(label, entry == null ? "null" : entry.getClass().getSimpleName()));
                    }
                    found.add(text);
                }
                yield List.copyOf(found);
            }
            default -> List.of(String.valueOf(raw));
        };
    }

    /**
     * Whatever came down the Data edge, as a list, or null for nothing wired.
     *
     * <p>{@code Any} rather than a typed list so that a file list, a match list, dataset rows and
     * strings all work. A single value is a batch of one, because refusing it would only teach the
     * user to wrap it in something.
     */
    private static List<Object> itemsOf(Object raw) {
        return switch (raw) {
            case null -> null;
            case Collection<?> items -> new ArrayList<>(items);
            default -> List.of(raw);
        };
    }

    // --- Asking --------------------------------------------------------------

    /**
     * Runs every request, bounded by the parallel setting, preserving order.
     *
     * <p>Virtual threads and a semaphore rather than a fixed pool: the work is entirely waiting on a
     * network, and the ceiling that matters is the gateway's, which the pacer already enforces. This
     * one only stops a thousand-item list from opening a thousand sockets at once.
     */
    private List<Outcome> askAll(
            NodeContext context,
            ModelSpec model,
            List<RequestPlan.Request> requests,
            SamplingParams sampling,
            CallPolicy policy)
            throws InterruptedException {

        var gate = new Semaphore(Math.max(1, Math.min(MAX_PARALLEL, context.integer("parallel"))));
        var completed = new AtomicInteger();
        var continueOnError = context.flag("continueOnError");
        var futures = new ArrayList<Future<Outcome>>(requests.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var request : requests) {
                futures.add(executor.submit(() -> {
                    if (context.isCancelled()) {
                        return Outcome.failed("cancelled");
                    }
                    gate.acquire();
                    try {
                        var call = callFor(context, model, request, sampling);
                        return ask(context, call, policy, cancellationOnly(context),
                                request.label(requests.size()), continueOnError);
                    } finally {
                        gate.release();
                        var done = completed.incrementAndGet();
                        context.progress(done / (double) requests.size(), "%d of %d".formatted(done, requests.size()));
                    }
                }));
            }
            var outcomes = new ArrayList<Outcome>(requests.size());
            for (var future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (ExecutionException failed) {
                    // The worker converts every expected failure into an Outcome, so what escapes
                    // is a request the user chose not to survive, or a bug worth seeing as itself.
                    throw failed.getCause() instanceof RuntimeException runtime
                            ? runtime
                            : new IllegalStateException("A request failed unexpectedly: " + failed.getCause(), failed.getCause());
                }
            }
            return outcomes;
        }
    }

    /**
     * One call.
     *
     * @param tolerate count a failure and go on, rather than failing the node with it
     */
    private Outcome ask(
            NodeContext context, ChatCall call, CallPolicy policy, StreamSink sink, String label, boolean tolerate) {
        try {
            return Outcome.ok(LlmResult.from(caller.call(call, policy, sink, context::log), call.model()));
        } catch (LlmFailure failure) {
            if (!tolerate) {
                // Classified failures already carry a sentence written for a person; the engine
                // shows getMessage() under the node, so the classification has to be inside it.
                throw new IllegalStateException(failure.describe(), failure);
            }
            context.log("%s failed: %s".formatted(capitalise(label), failure.describe()));
            return Outcome.failed(failure.describe());
        } catch (RuntimeException problem) {
            if (!tolerate) {
                throw problem;
            }
            context.log("%s failed: %s".formatted(capitalise(label), problem.getMessage()));
            return Outcome.failed(String.valueOf(problem.getMessage()));
        }
    }

    private static ChatCall callFor(
            NodeContext context, ModelSpec model, RequestPlan.Request request, SamplingParams sampling) {
        var messages = new ArrayList<ChatMessage>(2);
        if (!request.system().isEmpty()) {
            messages.add(ChatMessage.system(request.system()));
        }
        // The cache breakpoint sits after the system prompt: providers cache on the longest common
        // token prefix, so the instructions are paid for once across a batch and the per-item
        // payload that follows never enters the prefix.
        messages.add(new ChatMessage(ChatMessage.Role.USER, request.user(), request.attachments(), true));

        return new ChatCall(
                model,
                messages,
                responseFormat(context),
                sampling,
                new ChatCall.WebSearch(
                        context.flag("webSearch"),
                        context.flag("requireSearchEvidence"),
                        ""),
                model.can(Capability.PROMPT_CACHE) ? cacheKey(request.system()) : "",
                "");
    }

    private void emit(NodeContext context, ModelSpec model, List<Outcome> outcomes) {
        var results = new ArrayList<LlmResult>();
        var texts = new ArrayList<String>();
        var failures = new ArrayList<String>();
        var cost = 0d;
        for (var outcome : outcomes) {
            if (outcome.result() == null) {
                failures.add(outcome.failure());
                continue;
            }
            results.add(outcome.result());
            texts.add(outcome.result().text());
            cost += outcome.result().costUsd();
        }
        if (results.isEmpty()) {
            throw new IllegalStateException(outcomes.size() == 1
                    ? failures.getFirst()
                    : "All %d requests failed. First: %s".formatted(outcomes.size(), failures.getFirst()));
        }

        var summary = outcomes.size() == 1
                ? results.getFirst().summary()
                : "%d of %d answered%s, $%.4f on %s".formatted(
                        results.size(), outcomes.size(),
                        failures.isEmpty() ? "" : " (%d failed)".formatted(failures.size()),
                        cost, model.name());
        context.log(summary);
        context.output("text", String.join("\n\n", texts));
        context.output("results", List.copyOf(results));
        context.output("texts", List.copyOf(texts));
        context.output("cost", cost);
        context.output("failures", (double) failures.size());
        context.progress(1, summary);
    }

    // --- Checking ------------------------------------------------------------

    /**
     * Answers "would this request work?" without sending it.
     *
     * <p>Runs the real capability check against the real endpoint and the real model, and reaches
     * the gateway only to confirm the model is still served. That combination is the one worth
     * pressing before a batch of four hundred: a JSON schema this model ignores, a search mode it
     * has none of, or a model that was renamed last week all fail here in a second rather than on
     * item one of the run.
     */
    @Override
    public Result probe(String action, Request request) {
        if (!"check".equals(action)) {
            return Result.failed("This node has no action called " + action);
        }
        var modelSource = request.source("model");
        if (!modelSource.isPresent()) {
            return Result.failed("Wire an LLM Model into this node first — there is nothing to check.");
        }

        ModelSpec model;
        try {
            var endpoint = endpoints.resolve(modelSource.source("endpoint"));
            model = LlmModelNode.modelFrom(new ValueContext(modelSource.values()), endpoint);
        } catch (RuntimeException misconfigured) {
            return Result.failed(misconfigured.getMessage());
        }

        var findings = new ArrayList<String>();
        try {
            var values = new ValueContext(request.values());
            var planned = RequestPlan.plan(probeInputs(values));
            var call = callFor(values, model, planned.getFirst(), SamplingParams.UNSET);
            CapabilityCheck.inspect(call).forEach(finding -> findings.add(finding.toString()));
            available(model, findings);
        } catch (RuntimeException unusable) {
            return Result.problem(unusable.getMessage()).build();
        }

        var problems = findings.stream().filter(line -> line.startsWith("ERROR")).count();
        var result = problems > 0
                ? Result.problem("%d setting%s this model will not honour."
                        .formatted(problems, problems == 1 ? "" : "s"))
                : Result.ok(findings.isEmpty()
                        ? "Ready — %s accepts this request.".formatted(model.name())
                        : "Ready, with %d note%s.".formatted(findings.size(), findings.size() == 1 ? "" : "s"));
        findings.forEach(result::detail);
        return result.build();
    }

    /**
     * A plan from the typed fields alone.
     *
     * <p>A probe carries values, not upstream data, so templates are rendered leniently here: a
     * {@code {{item}}} that a run would fill from Data is not a mistake at check time.
     */
    private static RequestPlan.Inputs probeInputs(ValueContext values) {
        return new RequestPlan.Inputs(
                textsOf(values.rawInput("system"), "System Prompt"),
                List.of(NodeValues.firstNonBlank(values.text("user"), "(the user prompt)")),
                null, List.of(), false, RequestPlan.Combine.PAIR, Map.of(), false);
    }

    /** Adds the one finding the capability check cannot make: whether the model is still there. */
    private void available(ModelSpec model, List<String> findings) {
        try {
            var served = gateways.models(model.endpoint());
            if (served.isEmpty()) {
                return;
            }
            if (served.stream().noneMatch(candidate -> candidate.id().equalsIgnoreCase(model.name()))) {
                findings.add("ERROR — Model: %s does not serve '%s'."
                        .formatted(model.endpoint().baseUrl(), model.name()));
            }
        } catch (RuntimeException unreachable) {
            findings.add("WARNING — Endpoint: could not reach %s to confirm the model (%s)."
                    .formatted(model.endpoint().baseUrl(), unreachable.getMessage()));
        }
    }

    private static ResponseFormat responseFormat(NodeContext context) {
        return switch (context.text("responseFormat")) {
            case "json_object" -> ResponseFormat.JSON_OBJECT;
            case "json_schema" -> {
                var schema = context.text("jsonSchema").trim();
                if (schema.isEmpty()) {
                    throw new IllegalStateException(
                            "Response format is JSON schema, but no schema is filled in.");
                }
                yield new ResponseFormat.JsonSchema(context.text("schemaName"), schema, true);
            }
            default -> ResponseFormat.TEXT;
        };
    }

    /**
     * A cache key derived from the stable half of the prompt.
     *
     * <p>Content-derived rather than node-derived on purpose: two nodes with the same system prompt
     * should share a cached prefix, and one node whose prompt was edited should not keep the old key
     * and be served someone else's prefix.
     */
    private static String cacheKey(String system) {
        return system.isEmpty() ? "" : "unbi-" + Integer.toHexString(system.hashCode());
    }

    /** Cancellation reaches the provider; partial text does not, since answers overlap. */
    private static StreamSink cancellationOnly(NodeContext context) {
        return new StreamSink() {
            @Override
            public void chunk(String text) {
                // Several requests answer at once; interleaved tokens would be unreadable.
            }

            @Override
            public boolean cancelled() {
                return context.isCancelled();
            }
        };
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** @param result null when the request failed, in which case {@code failure} says why */
    private record Outcome(LlmResult result, String failure) {

        static Outcome ok(LlmResult result) {
            return new Outcome(result, null);
        }

        static Outcome failed(String failure) {
            return new Outcome(null, failure);
        }
    }
}
