package com.unbi.engine.nodes.llm;

import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.NodeInput;
import com.unbi.engine.core.node.NodeProbe;
import com.unbi.engine.core.node.ValueContext;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.core.type.Types;
import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.auth.RequestAuthorization;
import com.unbi.engine.llm.discovery.GatewayDirectory;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.ProviderProfile;
import com.unbi.engine.llm.spec.RatePolicy;
import com.unbi.engine.llm.spec.TokenUsage;
import com.unbi.engine.profiles.ProfileSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The endpoint profile: where requests go, and on what terms, saved once under a name and referred
 * to by that name from any workflow.
 *
 * <p>This is the answer to two complaints that turned out to be one. A base URL inside a workflow
 * file is wrong the moment the file moves machines, and a base URL inside the code is wrong on
 * every machine but one. So the URL lives here, on the engine that will use it, under a name a
 * workflow can mention — and a workflow saying "openrouter" runs against whatever "openrouter"
 * means where it runs.
 *
 * <p>Also the one place an {@link EndpointSpec} is built from values. The node, the test button,
 * the model listing and the request check all go through {@link #build}, so a green light and a
 * working run cannot disagree about which URL, which credential or which headers were meant.
 * Looking a profile up by id is {@link LlmEndpointNode}'s job, because the id is that node's value.
 */
@Component
public class EndpointProfiles implements ProfileSchema {

    public static final String ID = "llm.endpoint";

    /** The third state of every gateway-defaulted dropdown. */
    static final String FROM_GATEWAY = "profile";

    private final GatewayDirectory gateways;
    private final CredentialStore credentials;

    public EndpointProfiles(GatewayDirectory gateways, CredentialStore credentials) {
        this.gateways = gateways;
        this.credentials = credentials;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "LLM Endpoint";
    }

    @Override
    public boolean testable() {
        return true;
    }

    @Override
    public List<String> importFormats() {
        return List.of("openapi");
    }

    @Override
    public void validate(Map<String, Object> values) {
        build(new ValueContext(withDefaults(values)));
    }

    @Override
    public List<NodeInput> fields() {
        var fields = new ArrayList<NodeInput>();
        fields.add(setting("gateway", "Gateway", gateways(), "openrouter",
                "The kind of server, which sets how it authenticates, which wire format it speaks and "
                        + "how it counts tokens. Anything below left at \"From gateway\" follows it."));
        fields.add(setting("baseUrl", "Base URL", Widget.TextField.of("https://host/v1"), "",
                "The API base URL. Leave blank for the built-in ChatGPT/Codex endpoint; "
                        + "other gateways require an explicit URL."));
        fields.add(setting("auth", "Authentication", Widget.Dropdown.of(
                        FROM_GATEWAY, "From gateway",
                        "none", "None",
                        "bearer", "Bearer token",
                        "api_key", "Named API key",
                        "basic", "Username and password",
                        "oauth2", "OAuth 2.0",
                        "codex", "Codex account"), FROM_GATEWAY,
                "How the request proves who is asking."));
        fields.add(setting("credential", "Credential", new Widget.Credential(), "",
                "The name of a credential the engine holds — never the credential itself. Add one here, or "
                        + "set a compatible environment/property source."));
        fields.add(new NodeInput("apiKeyLocation", "API Key Location", Types.TEXT, false, false,
                Widget.Dropdown.of("header", "Header", "query", "Query", "cookie", "Cookie"), "header",
                "Where a named API key is placed.", false, NodeInput.ShowWhen.is("auth", "api_key")));
        fields.add(new NodeInput("apiKeyName", "API Key Name", Types.TEXT, false, false,
                Widget.TextField.of("X-API-Key"), "",
                "Header, query parameter, or cookie name for a named API key.", false,
                NodeInput.ShowWhen.is("auth", "api_key")));
        fields.add(advanced("apiFormat", "API Format", Widget.Dropdown.of(
                        FROM_GATEWAY, "From gateway",
                        "chat_completions", "Chat Completions",
                        "responses", "Responses"), FROM_GATEWAY,
                "The request protocol. From gateway preserves the built-in default."));
        fields.add(advanced("responsesDialect", "Responses Dialect", Widget.Dropdown.of(
                        FROM_GATEWAY, "From gateway",
                        "standard", "Standard",
                        "codex", "Codex"), FROM_GATEWAY,
                "The Responses request shape. Codex requires Responses streaming and store=false."));
        fields.add(setting("stream", "Streaming", Widget.Dropdown.of(
                        FROM_GATEWAY, "From gateway", "on", "On", "off", "Off"), FROM_GATEWAY,
                "Also a correctness setting on gateways whose buffered path mixes up overlapping requests."));
        fields.add(setting("timeoutSeconds", "Timeout", new Widget.NumberField(5, 3600, 5, "s", false), 120d,
                null));
        fields.add(advanced("cachedTokens", "Cached Token Counting", Widget.Dropdown.of(
                        FROM_GATEWAY, "From gateway",
                        "included", "Inside prompt tokens",
                        "additional", "On top of prompt tokens"), FROM_GATEWAY,
                "Some gateways report cached tokens in addition to prompt tokens; the cost estimate "
                        + "has to know which."));
        fields.add(advanced("requestsPerMinute", "Requests Per Minute",
                Widget.NumberField.optional(0, 10_000, 1, "", "from gateway"), null,
                "Blank takes the gateway's value; 0 means no limit."));
        fields.add(advanced("minRequestSpacingMs", "Minimum Request Spacing",
                Widget.NumberField.optional(0, 60_000, 10, "ms", "from gateway"), null,
                "A budget and an interval are different promises — a full bucket lets sixty "
                        + "requests leave in the same millisecond."));
        fields.add(advanced("maxConcurrent", "Max Concurrent",
                Widget.NumberField.optional(0, 64, 1, "", "from gateway"), null, null));
        fields.add(advanced("headers", "Extra Headers", Widget.KeyValue.of("Header", "Value"), null,
                "Sent with every request. A header typed here wins over one the gateway kind suggests."));
        return List.copyOf(fields);
    }

    /**
     * Does the draft answer, with the credential it names?
     *
     * <p>Built through {@link #build} — the very same function a run uses — and refusing early when
     * the credential it needs is not there: "no credential named X, looked in …" is the one message
     * in this subsystem that always has to be actionable, and the gateway's own 401 is not it.
     */
    @Override
    public Optional<NodeProbe.Result> test(Map<String, Object> values) {
        var context = new ValueContext(values);
        EndpointSpec endpoint;
        try {
            endpoint = build(context);
        } catch (RuntimeException misconfigured) {
            return Optional.of(NodeProbe.Result.failed(misconfigured.getMessage()));
        }

        var result = credentialProblem(endpoint).map(NodeProbe.Result::problem).orElseGet(() -> {
            var reachability = gateways.check(endpoint);
            var built = reachability.ok()
                    ? NodeProbe.Result.ok(reachability.message())
                    : NodeProbe.Result.problem(reachability.message());
            built.detail(reachability.url());
            reachability.detail().forEach(built::detail);
            return built;
        });
        context.logs().forEach(result::detail);
        return Optional.of(result.build());
    }

    /** Why a run against this endpoint would fail before reaching the network, if it would. */
    public Optional<String> credentialProblem(EndpointSpec endpoint) {
        if (endpoint.authScheme() == EndpointSpec.AuthScheme.NONE) {
            return Optional.empty();
        }
        if (endpoint.credentialRef().isBlank()) {
            return Optional.of("This gateway needs a credential, and none is named. Choose or add one "
                    + "in the endpoint profile.");
        }
        if (credentials.contains(endpoint.credentialRef())) {
            return Optional.empty();
        }
        return Optional.of(("No credential named '%s'. Add it to the profile, or set %s in the engine's "
                + "environment or credentials.properties.")
                .formatted(endpoint.credentialRef(), "UNBI_LLM_KEY_" + envName(endpoint.credentialRef())));
    }

    /** Builds an unsaved endpoint using its URL as the pacing identity. */
    public static EndpointSpec build(NodeContext values) {
        return build(values, "");
    }

    public static EndpointSpec build(NodeContext values, String id) {
        var kind = ProviderProfile.resolve(values.text("gateway"));
        var baseUrl = values.text("baseUrl").trim();
        if (baseUrl.isEmpty() && "codex".equals(kind.id())) {
            baseUrl = "https://chatgpt.com/backend-api/codex";
        }
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("The endpoint profile needs a base URL — there is no default for "
                    + kind.label() + ", because it differs on every machine.");
        }

        var authRaw = values.text("auth");
        var auth = authRaw.isBlank() || FROM_GATEWAY.equalsIgnoreCase(authRaw.trim())
                ? kind.authScheme()
                : EndpointSpec.AuthScheme.of(authRaw);
        var credentialRef = NodeValues.firstNonBlank(values.text("credential"), kind.credentialRef());

        var headers = new LinkedHashMap<>(kind.headers());
        headers.putAll(NodeValues.stringMap(values, "headers"));

        var formatRaw = values.text("apiFormat");
        var defaultApiFormat = formatRaw.isBlank() || FROM_GATEWAY.equalsIgnoreCase(formatRaw.trim())
                ? kind.defaultApiFormat()
                : ApiFormat.of(formatRaw);
        var dialectRaw = values.text("responsesDialect");
        var responsesDialect = dialectRaw.isBlank() || FROM_GATEWAY.equalsIgnoreCase(dialectRaw.trim())
                ? kind.responsesDialect()
                : EndpointSpec.ResponsesDialect.of(dialectRaw);
        var stream = NodeValues.tristate(values.text("stream"), kind.stream());
        if (responsesDialect == EndpointSpec.ResponsesDialect.CODEX) {
            if (defaultApiFormat != ApiFormat.RESPONSES) {
                throw new IllegalArgumentException("The Codex Responses dialect requires the Responses API format");
            }
            if (!stream) {
                throw new IllegalArgumentException("The Codex Responses dialect requires streaming");
            }
        }
        var apiKeyLocation = values.text("apiKeyLocation");
        if (apiKeyLocation.isBlank()) {
            apiKeyLocation = "header";
        }
        var apiKeyName = values.text("apiKeyName").trim();

        var cachedRaw = values.text("cachedTokens");
        var cachedMode = cachedRaw.isBlank() || FROM_GATEWAY.equals(cachedRaw)
                ? kind.cachedTokenMode()
                : TokenUsage.CachedTokenMode.of(cachedRaw);

        var rate = new RatePolicy(
                orKind(values, "requestsPerMinute", kind.rate().requestsPerMinute()),
                orKind(values, "minRequestSpacingMs", kind.rate().minRequestSpacingMillis()),
                orKind(values, "maxConcurrent", kind.rate().maxConcurrent()));

        var timeout = NodeValues.optionalDouble(values, "timeoutSeconds");
        var endpoint = new EndpointSpec(
                id,
                kind.id(),
                baseUrl,
                auth,
                credentialRef,
                headers,
                rate,
                (int) ((timeout == null || timeout <= 0 ? 120d : timeout) * 1000),
                stream,
                cachedMode,
                kind.responsesPromptCache(),
                defaultApiFormat,
                responsesDialect,
                apiKeyLocation,
                apiKeyName);
        endpoint.validateApiFormat(defaultApiFormat);
        RequestAuthorization.validateEndpoint(endpoint);

        values.log("%s → %s".formatted(kind.label(), endpoint.baseUrl()));
        values.log("Auth: %s%s".formatted(
                auth.label(), auth == EndpointSpec.AuthScheme.NONE ? "" : " as '" + credentialRef + "'"));
        if (!rate.isUnlimited()) {
            values.log("Pacing: %d/min, %d ms apart, %d at a time (0 = no limit)".formatted(
                    rate.requestsPerMinute(), rate.minRequestSpacingMillis(), rate.maxConcurrent()));
        }
        if (!kind.notes().isBlank()) {
            values.log(kind.notes());
        }
        return endpoint;
    }

    private static int orKind(NodeContext values, String key, int fromKind) {
        var typed = NodeValues.optionalInt(values, key);
        return typed == null ? fromKind : typed;
    }

    private static String envName(String ref) {
        return ref.replace('-', '_').replace('.', '_').toUpperCase(java.util.Locale.ROOT);
    }

    private static Widget gateways() {
        var pairs = new ArrayList<String>();
        ProviderProfile.builtIn().forEach(kind -> {
            pairs.add(kind.id());
            pairs.add(kind.label());
        });
        return Widget.Dropdown.of(pairs.toArray(String[]::new));
    }

    private static NodeInput setting(String key, String label, Widget widget, Object defaultValue, String hint) {
        return new NodeInput(key, label, Types.TEXT, false, false, widget, defaultValue, hint, false, null);
    }

    private static NodeInput advanced(String key, String label, Widget widget, Object defaultValue, String hint) {
        return new NodeInput(key, label, Types.TEXT, false, false, widget, defaultValue, hint, true, null);
    }
}
