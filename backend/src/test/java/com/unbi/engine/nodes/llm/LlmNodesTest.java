package com.unbi.engine.nodes.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.core.graph.WorkflowGraph;
import com.unbi.engine.core.run.EngineEvent;
import com.unbi.engine.core.run.RunOutcome;
import com.unbi.engine.core.type.TypeSystem;
import com.unbi.engine.engine.ExecutionEngine;
import com.unbi.engine.registry.NodeRegistry;
import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeContext;
import com.unbi.engine.core.node.ValueContext;
import com.unbi.engine.llm.Fixtures;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.ProviderProfile;
import com.unbi.engine.llm.spec.SamplingParams;
import com.unbi.engine.nodes.files.model.FileRef;
import com.unbi.engine.nodes.llm.model.LlmResult;
import com.unbi.engine.nodes.llm.model.LlmVariables;
import com.unbi.engine.support.RecordingContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each node against a recording context.
 *
 * <p>No Spring, no engine, no socket — which is the payoff of the narrow node SPI, and the reason a
 * node can be checked at the cheapest rung rather than through an end-to-end run.
 */
class LlmNodesTest {

    @Test
    void modelRunsWithProductionInputResolution() {
        var model = new LlmModelNode(null, null, null);
        var endpoint = new NodeDefinition() {
            @Override public NodeDescriptor descriptor() {
                return NodeDescriptor.of("test.endpoint", "Endpoint").out("endpoint", "Endpoint", LlmTypes.ENDPOINT).build();
            }
            @Override public void execute(NodeContext context) {
                context.output("endpoint", Fixtures.endpoint());
            }
        };
        var engine = new ExecutionEngine(new NodeRegistry(List.of(endpoint, model)), new TypeSystem());
        var graph = new WorkflowGraph(List.of(
                new WorkflowGraph.GraphNode("endpoint", "test.endpoint", Map.of(), new WorkflowGraph.Position(0, 0)),
                new WorkflowGraph.GraphNode("model", "llm.model", Map.of("model", "fixture-model"),
                        new WorkflowGraph.Position(0, 0))),
                List.of(new WorkflowGraph.GraphEdge("edge", "endpoint", "endpoint", "model", "endpoint")));
        var events = new java.util.concurrent.CopyOnWriteArrayList<EngineEvent>();
        engine.submit(graph, events::add);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                events.stream().anyMatch(EngineEvent.RunFinished.class::isInstance));
        assertThat(events.stream().filter(EngineEvent.RunFinished.class::isInstance)
                .map(EngineEvent.RunFinished.class::cast).findFirst().orElseThrow().outcome())
                .isEqualTo(RunOutcome.COMPLETED);
    }

    @Nested
    class Endpoint {

        private static Map<String, Object> profile(Object... keysAndValues) {
            var values = new LinkedHashMap<String, Object>();
            for (int index = 0; index < keysAndValues.length; index += 2) {
                values.put((String) keysAndValues[index], keysAndValues[index + 1]);
            }
            return values;
        }

        @Test
        @DisplayName("the gateway kind fills in the fields left at 'from gateway', and says which ones")
        void gatewayKindSuppliesDefaults() {
            var context = new ValueContext(profile(
                    "gateway", "openrouter", "baseUrl", "https://router.test/api/v1",
                    "auth", "profile", "stream", "profile", "cachedTokens", "profile", "timeoutSeconds", 60d));
            var endpoint = EndpointProfiles.build(context);

            assertThat(endpoint.baseUrl()).isEqualTo("https://router.test/api/v1");
            assertThat(endpoint.authScheme()).isEqualTo(EndpointSpec.AuthScheme.BEARER);
            assertThat(endpoint.credentialRef()).isEqualTo("openrouter");
            assertThat(endpoint.stream()).isTrue();
            assertThat(endpoint.timeoutMillis()).isEqualTo(60_000);
            assertThat(String.join("\n", context.logs())).contains("OpenRouter").contains("Auth: API key as 'openrouter'");
        }

        @Test
        void typedValuesWinOverTheGatewayKind() {
            var endpoint = EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "openrouter", "baseUrl", "https://mirror.test/v1",
                    "auth", "none", "stream", "off", "timeoutSeconds", 30d)));

            assertThat(endpoint.baseUrl()).isEqualTo("https://mirror.test/v1");
            assertThat(endpoint.authScheme()).isEqualTo(EndpointSpec.AuthScheme.NONE);
            assertThat(endpoint.stream()).isFalse();
        }

        @Test
        void explicitProtocolAndAuthValuesAreStrict() {
            var responses = EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "custom", "baseUrl", "https://router.test/v1",
                    "apiFormat", "RESPONSES", "auth", "BASIC")));
            assertThat(responses.defaultApiFormat()).isEqualTo(ApiFormat.RESPONSES);
            assertThat(responses.authScheme()).isEqualTo(EndpointSpec.AuthScheme.BASIC);

            assertThatThrownBy(() -> EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "custom", "baseUrl", "https://router.test/v1", "apiFormat", "future"))))
                    .hasMessageContaining("Unknown API format");
            assertThatThrownBy(() -> EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "custom", "baseUrl", "https://router.test/v1", "auth", "future"))))
                    .hasMessageContaining("Unknown authentication scheme");
        }

        @Test
        void endpointBaseUrlRejectsCredentialsAndUrlComponents() {
            assertThatThrownBy(() -> EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "custom", "baseUrl", "https://user:pass@router.test/v1"))))
                    .hasMessageContaining("userinfo");
            assertThatThrownBy(() -> EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "custom", "baseUrl", "https://router.test/v1?key=value"))))
                    .hasMessageContaining("query");
        }

        @Test
        @DisplayName("blank pacing takes the gateway kind's; an explicit zero means no limit")
        void pacingDistinguishesBlankFromZero() {
            var fromKind = EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "llamacpp", "baseUrl", "http://box.test:8080/v1")));
            assertThat(fromKind.rate().maxConcurrent()).isEqualTo(1);

            var explicit = EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "llamacpp", "baseUrl", "http://box.test:8080/v1", "maxConcurrent", 0d)));
            assertThat(explicit.rate().maxConcurrent()).isZero();
        }

        @Test
        void profileSaveRejectsAnInvalidEndpoint(@TempDir Path directory) {
            assertThatThrownBy(() -> Fixtures.saveProfile(directory, "Bad", Map.of(
                    "gateway", "custom", "baseUrl", "https://router.test/v1#fragment")))
                    .hasMessageContaining("fragment");
        }

        @Test
        @DisplayName("the built-in Codex profile supplies its ChatGPT resource while an explicit URL wins")
        void codexDefaultResourceCanBeOverridden() {
            var defaultEndpoint = EndpointProfiles.build(new ValueContext(profile("gateway", "codex")));
            assertThat(defaultEndpoint.baseUrl()).isEqualTo("https://chatgpt.com/backend-api/codex");
            var overridden = EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "codex", "baseUrl", "https://chatgpt.example/backend-api/codex")));
            assertThat(overridden.baseUrl()).isEqualTo("https://chatgpt.example/backend-api/codex");
        }

        @Test
        @DisplayName("the built-in Codex gateway rejects incompatible protocol and dialect choices locally")
        void codexRejectsStandardDialectAndChatCompletionsLocally() {
            assertThatThrownBy(() -> EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "codex", "responsesDialect", "standard"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "codex", "apiFormat", "chat_completions"))))
                    .isInstanceOf(IllegalArgumentException.class);

            var endpoint = EndpointProfiles.build(new ValueContext(profile("gateway", "codex")));
            assertThatThrownBy(() -> LlmModelNode.modelFrom(
                    RecordingContext.with("model", "gpt-5-codex", "apiFormat", "chat_completions"),
                    endpoint))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Codex defaults and explicit Responses remain valid, while custom gateways retain both protocols")
        void codexResponsesAndCustomProtocolsRemainSupported() {
            var codex = EndpointProfiles.build(new ValueContext(profile("gateway", "codex")));
            assertThat(codex.defaultApiFormat()).isEqualTo(ApiFormat.RESPONSES);
            assertThat(codex.responsesDialect()).isEqualTo(EndpointSpec.ResponsesDialect.CODEX);
            assertThat(codex.stream()).isTrue();

            var explicitCodex = EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "codex", "apiFormat", "responses", "responsesDialect", "codex", "stream", "on")));
            assertThat(explicitCodex.defaultApiFormat()).isEqualTo(ApiFormat.RESPONSES);

            var customResponses = EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "custom", "baseUrl", "https://gateway.test/v1",
                    "apiFormat", "responses", "responsesDialect", "standard")));
            assertThat(customResponses.defaultApiFormat()).isEqualTo(ApiFormat.RESPONSES);
            var customChat = EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "custom", "baseUrl", "https://gateway.test/v1",
                    "apiFormat", "chat_completions", "responsesDialect", "standard")));
            assertThat(customChat.defaultApiFormat()).isEqualTo(ApiFormat.CHAT_COMPLETIONS);
        }

        @Test
        @DisplayName("no gateway kind carries an URL except built-in Codex's documented resource")
        void aBlankBaseUrlIsRefusedForEveryKindExceptCodex() {
            for (var kind : ProviderProfile.builtIn()) {
                if (kind.id().equals("codex")) continue;
                assertThatThrownBy(() -> EndpointProfiles.build(new ValueContext(profile("gateway", kind.id()))))
                        .as(kind.id())
                        .hasMessageContaining("base URL");
            }
        }

        @Test
        void aBaseUrlWithoutASchemeIsRefused() {
            assertThatThrownBy(() -> EndpointProfiles.build(new ValueContext(profile(
                    "gateway", "custom", "baseUrl", "router.test/v1"))))
                    .hasMessageContaining("http://");
        }

        @Test
        @DisplayName("the node resolves its profile from the store, and the profile's id names the pacer")
        void theNodeResolvesASavedProfile(@TempDir Path directory) throws Exception {
            var node = Fixtures.endpointNode(directory);
            var saved = Fixtures.saveProfile(directory, "Lab box", Map.of(
                    "gateway", "llamacpp", "baseUrl", "http://box.test:8080/v1/"));

            var context = RecordingContext.with("profile", saved.id());
            node.execute(context);

            EndpointSpec endpoint = context.output("endpoint");
            assertThat(endpoint.baseUrl()).isEqualTo("http://box.test:8080/v1");
            assertThat(endpoint.id()).isEqualTo("lab-box");
            assertThat(context.logs()).anySatisfy(line -> assertThat(line).contains("Lab box"));
        }

        @Test
        void aMissingOrUnknownProfileIsNamedInTheFailure(@TempDir Path directory) {
            var node = Fixtures.endpointNode(directory);
            assertThatThrownBy(() -> node.execute(RecordingContext.with("profile", "")))
                    .hasMessageContaining("Choose an endpoint profile");
            assertThatThrownBy(() -> node.execute(RecordingContext.with("profile", "ghost")))
                    .hasMessageContaining("'ghost'");
        }

        @Test
        @DisplayName("nothing in the produced endpoint is a secret — only the name of one")
        void noSecretLeavesTheNode() {
            var context = new ValueContext(profile(
                    "gateway", "openrouter", "baseUrl", "https://router.test/v1", "credential", "my-key"));
            var endpoint = EndpointProfiles.build(context);

            assertThat(endpoint.toString()).contains("my-key");
            assertThat(String.join("\n", context.logs())).doesNotContain("sk-");
        }
    }

    @Nested
    class Model {

        private RecordingContext modelContext() {
            return RecordingContext.with(
                    "endpoint", Fixtures.endpoint(),
                    "model", "vendor/model-1",
                    "apiFormat", "chat_completions",
                    "reasoningDialect", "none",
                    "reasoningEffort", "medium",
                    "webSearchMode", "none",
                    "contextWindow", 200_000d,
                    "maxOutputTokens", 8192d,
                    "maxTokensParam", "max_tokens",
                    "inputPer1M", 0.5d,
                    "outputPer1M", 1.5d);
        }

        @Test
        void modelProtocolPrecedenceIsExplicitThenEndpointThenGateway() {
            var endpoint = EndpointProfiles.build(new ValueContext(Map.of(
                    "gateway", "openai", "baseUrl", "https://router.test/v1",
                    "apiFormat", "responses")));
            var inherited = LlmModelNode.modelFrom(
                    RecordingContext.with("model", "vendor/model-1", "apiFormat", "profile"), endpoint);
            var explicit = LlmModelNode.modelFrom(
                    RecordingContext.with("model", "vendor/model-1", "apiFormat", "chat_completions"), endpoint);

            assertThat(inherited.apiFormat()).isEqualTo(ApiFormat.RESPONSES);
            assertThat(explicit.apiFormat()).isEqualTo(ApiFormat.CHAT_COMPLETIONS);
        }

        @Test
        void buildsATargetFromTheWidgets() throws Exception {
            var context = modelContext().and("capabilities", List.of("json_object", "vision"))
                    .and("reasoningDialect", "reasoning_effort")
                    .and("reasoningEnabled", true)
                    .and("reasoningEffort", "high");
            Fixtures.modelNode().execute(context);

            ModelSpec model = context.output("model");
            assertThat(model.name()).isEqualTo("vendor/model-1");
            assertThat(model.contextWindow()).isEqualTo(200_000);
            assertThat(model.capabilities()).containsExactlyInAnyOrder(Capability.JSON_OBJECT, Capability.VISION);
            assertThat(model.reasoning().dialect()).isEqualTo(com.unbi.engine.llm.spec.Reasoning.Dialect.REASONING_EFFORT);
            assertThat(model.reasoning().enabled()).isTrue();
            assertThat(model.reasoning().effort()).isEqualTo(com.unbi.engine.llm.spec.Reasoning.Effort.HIGH);
            assertThat(model.pricing().inputPer1M()).isEqualTo(0.5);
            assertThat(model.key()).isEqualTo("test:vendor/model-1");
        }

        @Test
        @DisplayName("an unrecognised capability is ignored rather than failing the node")
        void unknownCapabilitiesAreDropped() throws Exception {
            var context = modelContext().and("capabilities", List.of("json_object", "telepathy"));
            Fixtures.modelNode().execute(context);
            assertThat(((ModelSpec) context.output("model")).capabilities())
                    .containsExactly(Capability.JSON_OBJECT);
        }

        @Test
        void providerOrderIsParsedAndRequireParametersIsOptIn() throws Exception {
            var context = modelContext()
                    .and("providerOrder", "deepinfra/fp8, together")
                    .and("requireParameters", true);
            Fixtures.modelNode().execute(context);

            var routing = ((ModelSpec) context.output("model")).routing();
            assertThat(routing.order()).containsExactly("deepinfra/fp8", "together");
            assertThat(routing.requireParameters()).isTrue();
        }

        @Test
        @DisplayName("an unticked require-parameters says nothing rather than saying false")
        void requireParametersStaysUnsaidWhenOff() throws Exception {
            var context = modelContext().and("providerOrder", "together");
            Fixtures.modelNode().execute(context);
            assertThat(((ModelSpec) context.output("model")).routing().requireParameters()).isNull();
        }

        @Test
        @DisplayName("bad JSON in the extra body fails here, not as somebody else's 400")
        void malformedExtraBodyFailsWithItsOwnMessage() {
            var context = modelContext().and("extraBody", "{ not json");
            assertThatThrownBy(() -> Fixtures.modelNode().execute(context))
                    .hasMessageContaining("Extra Request Body");
        }

        @Test
        void aMissingModelNameIsRefused() {
            assertThatThrownBy(() -> Fixtures.modelNode().execute(modelContext().and("model", "  ")))
                    .hasMessageContaining("model name");
        }

        @Test
        void samplingWiredInBecomesTheDefault() throws Exception {
            var sampling = SamplingParams.of(0.2d, null, null);
            var context = modelContext().and("sampling", sampling);
            Fixtures.modelNode().execute(context);
            assertThat(((ModelSpec) context.output("model")).sampling().temperature()).isEqualTo(0.2);
        }
    }

    @Nested
    class Sampling {

        @Test
        @DisplayName("a blank field is absent, and zero is a value")
        void blankIsNotZero() throws Exception {
            var blank = RecordingContext.with("stop", "");
            new LlmSamplingNode().execute(blank);
            assertThat(((SamplingParams) blank.output("sampling")).isUnset()).isTrue();

            var zero = RecordingContext.with("temperature", 0d, "stop", "");
            new LlmSamplingNode().execute(zero);
            assertThat(((SamplingParams) zero.output("sampling")).temperature()).isZero();
        }

        @Test
        void readsEveryField() throws Exception {
            var context = RecordingContext.with(
                    "temperature", 0.7d, "topP", 0.9d, "topK", 40d, "minP", 0.05d,
                    "seed", 12d, "stop", "###, END", "maxOutputTokens", 2048d);
            new LlmSamplingNode().execute(context);

            SamplingParams params = context.output("sampling");
            assertThat(params.topK()).isEqualTo(40);
            assertThat(params.seed()).isEqualTo(12);
            assertThat(params.stop()).containsExactly("###", "END");
            assertThat(params.maxOutputTokens()).isEqualTo(2048);
        }

        @Test
        @DisplayName("a request's params override a model's, field by field")
        void overridingIsPerField() {
            var modelDefaults = new SamplingParams(0.9d, 0.8d, 40, null, null, null, 1, List.of(), 1000);
            var override = SamplingParams.of(0.1d, null, null);

            var merged = override.over(modelDefaults);
            assertThat(merged.temperature()).isEqualTo(0.1);
            assertThat(merged.topP()).isEqualTo(0.8);
            assertThat(merged.topK()).isEqualTo(40);
            assertThat(merged.seed()).isEqualTo(1);
        }
    }

    @Nested
    class Variables {

        @Test
        void namesWiredValues() throws Exception {
            var context = RecordingContext.with(
                    "name1", "document", "value1", "the text",
                    "name2", "count", "value2", 3d,
                    "name3", "", "name4", "");
            new LlmVariablesNode().execute(context);

            LlmVariables variables = context.output("variables");
            assertThat(variables.asMap()).containsEntry("document", "the text").containsEntry("count", 3d);
        }

        @Test
        @DisplayName("a chained node merges underneath, so later names win")
        void mergesWithAnUpstreamNode() throws Exception {
            var base = new LlmVariables(new LinkedHashMap<>(Map.of("a", "old", "b", "kept")));
            var context = RecordingContext.with(
                    "more", base, "name1", "a", "value1", "new",
                    "name2", "", "name3", "", "name4", "");
            new LlmVariablesNode().execute(context);

            LlmVariables variables = context.output("variables");
            assertThat(variables.asMap()).containsEntry("a", "new").containsEntry("b", "kept");
        }

        @Test
        @DisplayName("a value wired into an unnamed slot is a half-finished edit, and is called out")
        void unnamedValuesAreRefused() {
            var context = RecordingContext.with(
                    "name1", "", "value1", "orphan", "name2", "", "name3", "", "name4", "");
            assertThatThrownBy(() -> new LlmVariablesNode().execute(context))
                    .hasMessageContaining("without a name");
        }
    }

    @Nested
    class Prompt {

        @Test
        void rendersFromVariables() throws Exception {
            var variables = new LlmVariables(new LinkedHashMap<>(Map.of("who", "world")));
            var context = RecordingContext.with(
                    "template", "Hello {{who}}", "variables", variables, "strict", true);
            new LlmPromptNode().execute(context);

            assertThat(context.rawOutput("text")).isEqualTo("Hello world");
        }

        @Test
        @DisplayName("a single wired value is bound as {{input}}, so one prompt needs no extra node")
        void theInputSocketBindsItself() throws Exception {
            var context = RecordingContext.with(
                    "template", "Summarise {{input}}", "input", "a report", "strict", true);
            new LlmPromptNode().execute(context);
            assertThat(context.rawOutput("text")).isEqualTo("Summarise a report");
        }

        @Test
        void aNamedBindingWinsOverTheInputSocket() throws Exception {
            var variables = new LlmVariables(new LinkedHashMap<>(Map.of("input", "named")));
            var context = RecordingContext.with(
                    "template", "{{input}}", "variables", variables, "input", "wired", "strict", true);
            new LlmPromptNode().execute(context);
            assertThat(context.rawOutput("text")).isEqualTo("named");
        }

        @Test
        @DisplayName("a mistyped variable fails loudly instead of leaving a hole in an instruction")
        void strictRenderingRefusesAMissingValue() {
            var context = RecordingContext.with("template", "Hello {{whoo}}", "strict", true);
            assertThatThrownBy(() -> new LlmPromptNode().execute(context))
                    .hasMessageContaining("whoo");
        }

        @Test
        void lenientRenderingCarriesOn() throws Exception {
            var context = RecordingContext.with("template", "Hello {{whoo}}", "strict", false);
            new LlmPromptNode().execute(context);
            assertThat(context.rawOutput("text")).isEqualTo("Hello ");
        }

        @Test
        void anEmptyTemplateIsRefused() {
            assertThatThrownBy(() -> new LlmPromptNode().execute(
                            RecordingContext.with("template", "  ", "strict", true)))
                    .hasMessageContaining("template");
        }
    }

    @Nested
    class Attach {

        @Test
        void readsTextImagesAndDocuments(@TempDir Path directory) throws Exception {
            var text = write(directory, "notes.md", "hello");
            var image = writeBytes(directory, "shot.png", new byte[] {1, 2, 3});
            var document = writeBytes(directory, "report.pdf", new byte[] {4, 5});

            var context = attachContext(List.of(text, image, document));
            new LlmAttachNode().execute(context);

            List<Attachment> attachments = context.output("attachments");
            assertThat(attachments).extracting(Attachment::kind).containsExactly(
                    Attachment.Kind.TEXT, Attachment.Kind.IMAGE, Attachment.Kind.DOCUMENT);
            assertThat(attachments.getFirst().text()).isEqualTo("hello");
            assertThat(attachments.get(1).dataUrl()).startsWith("data:image/png;base64,");
            assertThat(context.rawOutput("count")).isEqualTo(3d);
        }

        @Test
        @DisplayName("a binary file is not decoded into replacement characters and called text")
        void binaryFilesAreNotTreatedAsText() throws Exception {
            var directory = Files.createTempDirectory("attach");
            var binary = writeBytes(directory, "blob.dat", new byte[] {(byte) 0xC3, (byte) 0x28});
            var context = attachContext(List.of(binary));
            new LlmAttachNode().execute(context);

            List<Attachment> attachments = context.output("attachments");
            assertThat(attachments.getFirst().kind()).isEqualTo(Attachment.Kind.DOCUMENT);
        }

        @Test
        @DisplayName("an oversized file fails by name rather than quietly not reaching the model")
        void oversizedFilesAreReported(@TempDir Path directory) throws Exception {
            var big = write(directory, "big.md", "x".repeat(4096));
            var context = attachContext(List.of(big)).and("maxFileKb", 1d);

            assertThatThrownBy(() -> new LlmAttachNode().execute(context))
                    .hasMessageContaining("big.md")
                    .hasMessageContaining("limit");
        }

        @Test
        void skippingOversizedFilesIsOptIn(@TempDir Path directory) throws Exception {
            var big = write(directory, "big.md", "x".repeat(4096));
            var small = write(directory, "small.md", "ok");
            var context = attachContext(List.of(big, small))
                    .and("maxFileKb", 1d)
                    .and("skipOversized", true);

            new LlmAttachNode().execute(context);
            assertThat(context.rawOutput("count")).isEqualTo(1d);
            assertThat(context.logs()).anySatisfy(line -> assertThat(line).contains("Skipped big.md"));
        }

        @Test
        void theTotalLimitStopsABatchFromBecomingOneHugePost(@TempDir Path directory) throws Exception {
            var files = List.of(
                    write(directory, "a.md", "x".repeat(700_000)),
                    write(directory, "b.md", "x".repeat(700_000)));
            var context = attachContext(files).and("maxTotalMb", 1d);

            assertThatThrownBy(() -> new LlmAttachNode().execute(context))
                    .hasMessageContaining("total limit");
        }

        private RecordingContext attachContext(List<FileRef> files) {
            return RecordingContext.with(
                    "files", files, "textAsText", true,
                    "maxFileKb", 8192d, "maxTotalMb", 32d, "skipOversized", false);
        }
    }

    @Nested
    class Request {

        private RecordingContext requestContext(ModelSpec model) {
            return RecordingContext.with(
                    "model", model,
                    "system", "You are terse.",
                    "user", "Say hello.",
                    "responseFormat", "text",
                    "schemaName", "response",
                    "attachMode", "all",
                    "combine", "pair",
                    "parallel", 4d,
                    "continueOnError", true,
                    "strictTemplates", true,
                    "strict", true,
                    "failOnTruncation", true,
                    "retries", 1d);
        }

        @Test
        void sendsThePromptsAndReturnsTheAnswer() throws Exception {
            var provider = StubProvider.answering("hi there");
            var context = requestContext(Fixtures.model());
            Fixtures.requestNode(provider.caller()).execute(context);

            assertThat(context.rawOutput("text")).isEqualTo("hi there");
            List<LlmResult> results = context.output("results");
            assertThat(results).singleElement().extracting(LlmResult::model).isEqualTo("stub/model");
            assertThat(provider.onlyCall().messages()).hasSize(2);
            assertThat(provider.onlyCall().messages().getFirst().text()).isEqualTo("You are terse.");
        }

        @Test
        @DisplayName("the cache breakpoint sits after the instructions, never after the payload")
        void theCacheBreakpointIsPlacedForTheStablePrefix() throws Exception {
            var provider = StubProvider.answering("ok");
            Fixtures.requestNode(provider.caller()).execute(requestContext(Fixtures.model()));

            var messages = provider.onlyCall().messages();
            assertThat(messages.getFirst().cacheBreakpoint()).isFalse();
            assertThat(messages.getLast().cacheBreakpoint()).isTrue();
        }

        @Test
        void streamsTowardsTheTextPortWhileItArrives() throws Exception {
            var provider = StubProvider.streaming("Hel", "lo");
            var context = requestContext(Fixtures.model());
            Fixtures.requestNode(provider.caller()).execute(context);

            assertThat(context.streamed("text")).isEqualTo("Hello");
            assertThat(context.rawOutput("text")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("an answer cut off by the output limit fails rather than flowing on as a success")
        void truncationFailsByDefault() {
            var provider = StubProvider.truncating("half a docum");
            assertThatThrownBy(() ->
                            Fixtures.requestNode(provider.caller()).execute(requestContext(Fixtures.model())))
                    .hasMessageContaining("cut off");
        }

        @Test
        void truncationCanBeAccepted() throws Exception {
            var provider = StubProvider.truncating("half");
            var context = requestContext(Fixtures.model()).and("failOnTruncation", false);
            Fixtures.requestNode(provider.caller()).execute(context);
            assertThat(context.rawOutput("text")).isEqualTo("half");
        }

        @Test
        @DisplayName("an unsupported setting is refused with the setting named, not dropped")
        void strictModeRefusesAnUndeclaredFormat() {
            var provider = StubProvider.answering("{}");
            var context = requestContext(Fixtures.model()).and("responseFormat", "json_object");

            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(context))
                    .hasMessageContaining("json_object");
            assertThat(provider.calls()).isEmpty();
        }

        @Test
        void lenientModeDegradesAndSaysSo() throws Exception {
            var provider = StubProvider.answering("{}");
            var context = requestContext(Fixtures.model())
                    .and("responseFormat", "json_object")
                    .and("strict", false);
            Fixtures.requestNode(provider.caller()).execute(context);

            assertThat(provider.calls()).hasSize(1);
            assertThat(context.logs()).anySatisfy(line -> assertThat(line).contains("ERROR"));
        }

        @Test
        void aRetryableFailureIsRetriedAndThenReported() {
            var provider = StubProvider.failing(LlmFailure.Kind.SERVER, "upstream exploded");
            var context = requestContext(Fixtures.model()).and("retries", 2d);

            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(context))
                    .hasMessageContaining("Server error");
            assertThat(provider.calls()).hasSize(2);
        }

        @Test
        @DisplayName("a disconnect after visible output is never replayed")
        void outputBeforeRetryableFailureDispatchesOnlyOnce() {
            var provider = StubProvider.emitsThenFails("partial");
            var context = requestContext(Fixtures.model()).and("retries", 3d);

            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(context))
                    .hasMessageContaining("disconnect after output");
            assertThat(provider.calls()).hasSize(1);
            assertThat(context.streamed("text")).isEqualTo("partial");
        }

        @Test
        @DisplayName("a rejected request is not retried: asking again buys the same rejection")
        void nonRetryableFailuresAreNotRepeated() {
            var provider = StubProvider.failing(LlmFailure.Kind.INVALID_REQUEST, "bad field");
            var context = requestContext(Fixtures.model()).and("retries", 3d);

            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(context))
                    .hasMessageContaining("bad field");
            assertThat(provider.calls()).hasSize(1);
        }

        @Test
        void aJsonSchemaFormatNeedsASchema() {
            var provider = StubProvider.answering("{}");
            var model = Fixtures.with(Fixtures.model(), Capability.JSON_SCHEMA);
            var context = requestContext(model).and("responseFormat", "json_schema");

            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(context))
                    .hasMessageContaining("no schema");
        }

        @Test
        void attachmentsTravelWithTheUserMessage() throws Exception {
            var provider = StubProvider.answering("seen");
            var model = Fixtures.with(Fixtures.model(), Capability.VISION);
            var context = requestContext(model).and("attachments",
                    List.of(Attachment.image("a.png", "image/png", new byte[] {1})));

            Fixtures.requestNode(provider.caller()).execute(context);
            assertThat(provider.onlyCall().attachments()).hasSize(1);
        }

        @Test
        void aRequestWithNothingToSayIsRefused() {
            var provider = StubProvider.answering("x");
            var context = requestContext(Fixtures.model()).and("user", "   ");
            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(context))
                    .hasMessageContaining("user prompt");
        }
    }

    @Nested
    class Batches {

        private RecordingContext batchContext(Object items) {
            return RecordingContext.with(
                    "model", Fixtures.model(),
                    "items", items,
                    "system", "",
                    "user", "Item {{index}}: {{item}}",
                    "responseFormat", "text",
                    "schemaName", "response",
                    "attachMode", "all",
                    "combine", "pair",
                    "parallel", 4d,
                    "continueOnError", true,
                    "strictTemplates", true,
                    "strict", true,
                    "failOnTruncation", true,
                    "retries", 1d);
        }

        @Test
        @DisplayName("answers line up with their inputs even when they finish out of order")
        void resultsKeepTheirPosition() throws Exception {
            var provider = StubProvider.echoing();
            var context = batchContext(List.of("alpha", "beta", "gamma"));
            Fixtures.requestNode(provider.caller()).execute(context);

            List<String> texts = context.output("texts");
            assertThat(texts).containsExactly("Item 1: alpha", "Item 2: beta", "Item 3: gamma");
            assertThat(context.rawOutput("text")).isEqualTo("Item 1: alpha\n\nItem 2: beta\n\nItem 3: gamma");
            assertThat(context.rawOutput("failures")).isEqualTo(0d);
        }

        @Test
        void aSingleValueIsABatchOfOne() throws Exception {
            var provider = StubProvider.echoing();
            var context = batchContext("only");
            Fixtures.requestNode(provider.caller()).execute(context);
            List<String> texts = context.output("texts");
            assertThat(texts).containsExactly("Item 1: only");
        }

        @Test
        @DisplayName("several system prompts against one item: one request per prompt, in order")
        void aListOfSystemPromptsIsTriedInTurn() throws Exception {
            var provider = StubProvider.echoing();
            var context = batchContext("doc").and("system", List.of("Be terse.", "Be thorough."));
            Fixtures.requestNode(provider.caller()).execute(context);

            assertThat(provider.calls()).hasSize(2);
            assertThat(provider.calls().stream().map(call -> call.messages().getFirst().text()))
                    .containsExactlyInAnyOrder("Be terse.", "Be thorough.");
        }

        @Test
        void crossingListsMakesEveryCombination() throws Exception {
            var provider = StubProvider.echoing();
            var context = batchContext(List.of("a", "b", "c"))
                    .and("system", List.of("S1", "S2"))
                    .and("combine", "cross");
            Fixtures.requestNode(provider.caller()).execute(context);

            List<String> texts = context.output("texts");
            assertThat(texts).hasSize(6).startsWith("Item 1: a", "Item 2: b", "Item 3: c");
            assertThat(provider.calls().stream().map(call -> call.messages().getFirst().text()))
                    .containsExactlyInAnyOrder("S1", "S1", "S1", "S2", "S2", "S2");
        }

        @Test
        void pairingListsOfDifferentLengthsIsRefusedWithTheLengths() {
            var provider = StubProvider.echoing();
            var context = batchContext(List.of("a", "b", "c")).and("system", List.of("S1", "S2"));
            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(context))
                    .hasMessageContaining("2 system prompts").hasMessageContaining("3 data items");
        }

        @Test
        @DisplayName("one failure is counted, not thrown away and not fatal — unless nothing answered")
        void failuresAreCountedWhenAskedToContinue() throws Exception {
            var provider = StubProvider.failingOn("b", LlmFailure.Kind.CONTENT_FILTER, "refused");
            var context = batchContext(List.of("a", "b"));
            Fixtures.requestNode(provider.caller()).execute(context);

            assertThat(context.rawOutput("failures")).isEqualTo(1d);
            List<LlmResult> results = context.output("results");
            assertThat(results).hasSize(1);

            var allFail = StubProvider.failing(LlmFailure.Kind.CONTENT_FILTER, "refused");
            assertThatThrownBy(() -> Fixtures.requestNode(allFail.caller()).execute(batchContext(List.of("a", "b"))))
                    .hasMessageContaining("All 2 requests failed");
        }

        @Test
        void continueOnErrorCanBeTurnedOff() {
            var provider = StubProvider.failing(LlmFailure.Kind.CONTENT_FILTER, "refused");
            var context = batchContext(List.of("a", "b")).and("continueOnError", false);

            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(context))
                    .hasMessageContaining("refused");
        }

        @Test
        void aFileListBindsItsNameAndPath(@TempDir Path directory) throws Exception {
            var provider = StubProvider.echoing();
            var file = write(directory, "guide.md", "body");
            var context = batchContext(List.of(file)).and("user", "{{name}} at {{path}}");

            Fixtures.requestNode(provider.caller()).execute(context);
            List<String> texts = context.output("texts");
            assertThat(texts.getFirst()).startsWith("guide.md at ").endsWith("guide.md");
        }

        @Test
        @DisplayName("a dataset row's columns are readable by name")
        void aRecordBindsItsFields() throws Exception {
            var provider = StubProvider.echoing();
            var rows = List.of(Map.of("title", "One", "body", "first"), Map.of("title", "Two", "body", "second"));
            var context = batchContext(rows).and("user", "{{title}}: {{body}}");

            Fixtures.requestNode(provider.caller()).execute(context);
            List<String> texts = context.output("texts");
            assertThat(texts).containsExactly("One: first", "Two: second");
        }

        @Test
        void oneRequestPerAttachmentBindsTheFileName() throws Exception {
            var provider = StubProvider.echoing();
            var model = Fixtures.with(Fixtures.model(), Capability.VISION);
            var context = batchContext(null)
                    .and("model", model)
                    .and("user", "Describe {{file}}")
                    .and("attachMode", "each")
                    .and("attachments", List.of(
                            Attachment.image("a.png", "image/png", new byte[] {1}),
                            Attachment.image("b.png", "image/png", new byte[] {2})));

            Fixtures.requestNode(provider.caller()).execute(context);
            List<String> texts = context.output("texts");
            assertThat(texts).containsExactly("Describe a.png", "Describe b.png");
            assertThat(provider.calls()).allSatisfy(call -> assertThat(call.attachments()).hasSize(1));
        }

        @Test
        void allAttachmentsInOneRequestByDefault() throws Exception {
            var provider = StubProvider.echoing();
            var model = Fixtures.with(Fixtures.model(), Capability.VISION);
            var context = batchContext(null)
                    .and("model", model)
                    .and("user", "Compare these")
                    .and("attachments", List.of(
                            Attachment.image("a.png", "image/png", new byte[] {1}),
                            Attachment.image("b.png", "image/png", new byte[] {2})));

            Fixtures.requestNode(provider.caller()).execute(context);
            assertThat(provider.onlyCall().attachments()).hasSize(2);
        }

        @Test
        void anEmptyListIsRefusedRatherThanSucceedingWithNothing() {
            var provider = StubProvider.echoing();
            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(batchContext(List.of())))
                    .hasMessageContaining("nothing to ask about");
        }

        @Test
        void anUnboundPlaceholderFailsTheRequestByDefault() {
            var provider = StubProvider.echoing();
            var context = batchContext(List.of("a")).and("user", "Tell me about {{topic}}");
            assertThatThrownBy(() -> Fixtures.requestNode(provider.caller()).execute(context))
                    .hasMessageContaining("topic");
        }

        @Test
        void costIsSummedAcrossItems() throws Exception {
            var provider = StubProvider.echoing();
            var priced = com.unbi.engine.llm.Fixtures.model();
            var model = new ModelSpec(
                    priced.endpoint(), priced.name(), priced.apiFormat(), priced.capabilities(),
                    priced.reasoning(), priced.webSearchMode(),
                    com.unbi.engine.llm.spec.Pricing.of(1_000_000, 1_000_000),
                    priced.contextWindow(), priced.maxOutputTokens(), priced.maxTokensParam(),
                    priced.sampling(), priced.routing(), priced.tags(), priced.extraBody());

            var context = batchContext(List.of("a", "b")).and("model", model);
            Fixtures.requestNode(provider.caller()).execute(context);

            // Ten prompt and five completion tokens per stubbed call, at one dollar per token.
            assertThat(context.rawOutput("cost")).isEqualTo(30d);
        }
    }

    @Nested
    class PromptVariants {

        @Test
        void splitsOnSeparatorLinesAndDropsEmptyParts() throws Exception {
            var context = RecordingContext.with(
                    "text", "You are terse.\n---\n\nYou are thorough.\nReally.\n---\n", "separator", "---");
            new LlmPromptVariantsNode().execute(context);
            List<String> prompts = context.output("prompts");
            assertThat(prompts).containsExactly("You are terse.", "You are thorough.\nReally.");
            assertThat(context.rawOutput("count")).isEqualTo(2d);
        }

        @Test
        void aSeparatorInsideALineIsNotASeparator() {
            assertThat(LlmPromptVariantsNode.split("a --- b\n---\nc", "---")).containsExactly("a --- b", "c");
        }

        @Test
        void nothingToSplitIsRefused() {
            assertThatThrownBy(() -> new LlmPromptVariantsNode().execute(RecordingContext.with("text", "  ", "separator", "---")))
                    .hasMessageContaining("at least one prompt");
        }
    }

    @Nested
    class ParseJson {

        @Test
        void parsesAPlainObject() throws Exception {
            var context = RecordingContext.with(
                    "value", "{\"a\":1}", "path", "", "strict", true);
            new LlmParseJsonNode().execute(context);

            Map<String, Object> parsed = context.output("value");
            assertThat(parsed).containsEntry("a", 1d);
            assertThat(context.rawOutput("parsed")).isEqualTo(true);
        }

        @Test
        @DisplayName("a fenced answer is unwrapped, which is what a model asked for JSON usually sends")
        void stripsACodeFence() throws Exception {
            var context = RecordingContext.with(
                    "value", "Here you go:\n```json\n{\"a\":1}\n```\nhope that helps",
                    "path", "", "strict", true);
            new LlmParseJsonNode().execute(context);
            assertThat(context.rawOutput("parsed")).isEqualTo(true);
        }

        @Test
        void readsAResultDirectly() throws Exception {
            var result = new LlmResult("[1,2,3]", "stop", "m", 1, 1, 0, 0, 1, "");
            var context = RecordingContext.with("value", result, "path", "", "strict", true);
            new LlmParseJsonNode().execute(context);
            List<Object> parsed = context.output("value");
            assertThat(parsed).containsExactly(1d, 2d, 3d);
        }

        @Test
        void followsADottedPath() throws Exception {
            var context = RecordingContext.with(
                    "value", "{\"data\":{\"items\":[\"x\"]}}", "path", "data.items", "strict", true);
            new LlmParseJsonNode().execute(context);
            List<Object> parsed = context.output("value");
            assertThat(parsed).containsExactly("x");
        }

        @Test
        void aNumericSegmentIndexesAList() throws Exception {
            var context = RecordingContext.with(
                    "value", "{\"items\":[{\"n\":1},{\"n\":2}]}", "path", "items.1.n", "strict", true);
            new LlmParseJsonNode().execute(context);
            assertThat(context.rawOutput("value")).isEqualTo(2d);
        }

        @Test
        @DisplayName("a strict failure says what the answer actually contained")
        void strictFailureIsDiagnostic() {
            var context = RecordingContext.with(
                    "value", "{\"a\":1}", "path", "b.c", "strict", true);
            assertThatThrownBy(() -> new LlmParseJsonNode().execute(context))
                    .hasMessageContaining("b.c")
                    .hasMessageContaining("contains: a");
        }

        @Test
        void lenientModePassesTheTextThroughSoAGraphCanBranch() throws Exception {
            var context = RecordingContext.with("value", "not json at all", "path", "", "strict", false);
            new LlmParseJsonNode().execute(context);

            assertThat(context.rawOutput("parsed")).isEqualTo(false);
            assertThat(context.rawOutput("value")).isEqualTo("not json at all");
        }
    }

    @Nested
    class Save {

        @Test
        void writesOneFilePerAnswer(@TempDir Path directory) throws Exception {
            var results = List.of(
                    new LlmResult("first", "stop", "m", 0, 0, 0, 0, 0, ""),
                    new LlmResult("second", "stop", "m", 0, 0, 0, 0, 0, ""));
            var context = saveContext(directory, results, "answer-{{index}}.md");
            new LlmSaveNode().execute(context);

            List<FileRef> files = context.output("files");
            assertThat(files).hasSize(2);
            assertThat(Files.readString(files.getFirst().toPath())).isEqualTo("first");
            assertThat(files.getFirst().name()).isEqualTo("answer-1.md");
        }

        @Test
        @DisplayName("the index is padded so a hundred answers sort the way they ran")
        void indexIsZeroPaddedToTheBatchWidth(@TempDir Path directory) throws Exception {
            var results = new java.util.ArrayList<LlmResult>();
            for (int i = 0; i < 12; i++) {
                results.add(new LlmResult("body " + i, "stop", "m", 0, 0, 0, 0, 0, ""));
            }
            var context = saveContext(directory, results, "a-{{index}}.md");
            new LlmSaveNode().execute(context);

            List<FileRef> files = context.output("files");
            assertThat(files.getFirst().name()).isEqualTo("a-01.md");
        }

        @Test
        void namesFollowTheSourceFilesWhenGiven(@TempDir Path directory) throws Exception {
            var source = write(directory, "chapter-one.txt", "x");
            var results = List.of(new LlmResult("summary", "stop", "m", 0, 0, 0, 0, 0, ""));
            var context = saveContext(directory, results, "{{name}}.md").and("names", List.of(source));

            new LlmSaveNode().execute(context);
            List<FileRef> files = context.output("files");
            assertThat(files.getFirst().name()).isEqualTo("chapter-one.md");
        }

        @Test
        @DisplayName("a second run adds a suffix rather than erasing the first")
        void existingFilesAreNotOverwrittenByDefault(@TempDir Path directory) throws Exception {
            var results = List.of(new LlmResult("one", "stop", "m", 0, 0, 0, 0, 0, ""));
            new LlmSaveNode().execute(saveContext(directory, results, "a.md"));

            var second = saveContext(directory, List.of(
                    new LlmResult("two", "stop", "m", 0, 0, 0, 0, 0, "")), "a.md");
            new LlmSaveNode().execute(second);

            List<FileRef> files = second.output("files");
            assertThat(files.getFirst().name()).isEqualTo("a-2.md");
            assertThat(Files.readString(directory.resolve("a.md"))).isEqualTo("one");
        }

        @Test
        @DisplayName("a file name that tries to escape the folder is neutralised, not honoured")
        void theFileNameCannotEscapeTheDirectory(@TempDir Path directory) throws Exception {
            var results = List.of(new LlmResult("body", "stop", "m", 0, 0, 0, 0, 0, ""));
            var context = saveContext(directory, results, "../escaped.md");
            new LlmSaveNode().execute(context);

            List<FileRef> files = context.output("files");
            var written = files.getFirst().toPath();
            assertThat(written.getParent()).isEqualTo(directory);
        }

        @Test
        void aMissingDirectoryIsRefused() {
            var results = List.of(new LlmResult("body", "stop", "m", 0, 0, 0, 0, 0, ""));
            assertThatThrownBy(() -> new LlmSaveNode().execute(RecordingContext.with(
                            "value", results, "directory", "", "fileName", "a.md", "overwrite", false)))
                    .hasMessageContaining("directory");
        }

        private RecordingContext saveContext(Path directory, List<?> values, String fileName) {
            return RecordingContext.with(
                    "value", values,
                    "directory", directory.toString(),
                    "fileName", fileName,
                    "overwrite", false);
        }
    }

    // --- shared helpers -----------------------------------------------------

    private static FileRef write(Path directory, String name, String content) throws IOException {
        var file = directory.resolve(name);
        Files.writeString(file, content);
        return FileRef.of(file, Files.size(file));
    }

    private static FileRef writeBytes(Path directory, String name, byte[] content) throws IOException {
        var file = directory.resolve(name);
        Files.write(file, content);
        return FileRef.of(file, Files.size(file));
    }
}
