package com.unbi.engine.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.llm.Fixtures;
import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatMessage;
import com.unbi.engine.llm.spec.FinishReason;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.ProviderRouting;
import com.unbi.engine.llm.spec.Reasoning;
import com.unbi.engine.llm.spec.ResponseFormat;
import com.unbi.engine.llm.spec.SamplingParams;
import com.unbi.engine.llm.spec.TokenUsage;
import com.unbi.engine.llm.spec.WebSearchMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * What actually goes on the wire.
 *
 * <p>The only way to tell a parameter that was configured from one that was sent: gateways accept
 * unknown fields and drop what they do not implement, so a request that silently lost a setting
 * looks identical to one that kept it.
 */
class ChatWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ChatCall call(ModelSpec model) {
        return ChatCall.of(model, List.of(ChatMessage.user("hello")));
    }

    @Nested
    class RequestBody {

        @Test
        void carriesTheModelAndMessages() {
            var body = ChatWire.request(call(Fixtures.model()), false);
            assertThat(body.path("model").asString("")).isEqualTo("vendor/model-1");
            assertThat(body.path("messages").path(0).path("role").asString("")).isEqualTo("user");
            assertThat(body.path("messages").path(0).path("content").asString("")).isEqualTo("hello");
        }

        @Test
        @DisplayName("streaming asks for usage too, or the token counts and the cost are lost")
        void streamingIncludesUsage() {
            var body = ChatWire.request(call(Fixtures.model()), true);
            assertThat(body.path("stream").asBoolean(false)).isTrue();
            assertThat(body.path("stream_options").path("include_usage").asBoolean(false)).isTrue();
        }

        @Test
        void bufferedRequestsSendNoStreamOptions() {
            assertThat(ChatWire.request(call(Fixtures.model()), false).has("stream_options")).isFalse();
        }

        @Test
        @DisplayName("the output limit uses the field name the target declared")
        void usesTheDeclaredMaxTokensField() {
            var base = Fixtures.model();
            var reasoningEra = new ModelSpec(
                    base.endpoint(), base.name(), base.apiFormat(), base.capabilities(), base.reasoning(),
                    base.webSearchMode(), base.pricing(), base.contextWindow(), base.maxOutputTokens(),
                    ModelSpec.MaxTokensParam.MAX_COMPLETION_TOKENS, base.sampling(), base.routing(),
                    base.tags(), base.extraBody());

            assertThat(ChatWire.request(call(base), false).has("max_tokens")).isTrue();
            assertThat(ChatWire.request(call(reasoningEra), false).has("max_completion_tokens")).isTrue();
            assertThat(ChatWire.request(call(reasoningEra), false).has("max_tokens")).isFalse();
        }

        @Test
        @DisplayName("the model's own ceiling wins over a larger request")
        void outputLimitIsCappedByTheModel() {
            var sampling = new SamplingParams(null, null, null, null, null, null, null, List.of(), 99_999);
            var body = ChatWire.request(new ChatCall(
                    Fixtures.model(), List.of(ChatMessage.user("hi")),
                    ResponseFormat.TEXT, sampling, ChatCall.WebSearch.OFF, "", ""), false);
            assertThat(body.path("max_tokens").asInt(0)).isEqualTo(4096);
        }

        @Test
        @DisplayName("an unset sampler is absent, which is not the same as zero")
        void unsetSamplersAreNotSent() {
            var body = ChatWire.request(call(Fixtures.model()), false);
            assertThat(body.has("temperature")).isFalse();
            assertThat(body.has("top_p")).isFalse();
            assertThat(body.has("top_k")).isFalse();
            assertThat(body.has("seed")).isFalse();
        }

        @Test
        void sendsEverySamplerThatWasSet() {
            var sampling = new SamplingParams(0d, 0.9d, 64, 0.05d, 0.1d, -0.1d, 7, List.of("###"), null);
            var body = ChatWire.request(new ChatCall(
                    Fixtures.model(), List.of(ChatMessage.user("hi")),
                    ResponseFormat.TEXT, sampling, ChatCall.WebSearch.OFF, "", ""), false);

            assertThat(body.path("temperature").asDouble(-1)).isZero();
            assertThat(body.path("top_p").asDouble(-1)).isEqualTo(0.9);
            assertThat(body.path("top_k").asInt(-1)).isEqualTo(64);
            assertThat(body.path("min_p").asDouble(-1)).isEqualTo(0.05);
            assertThat(body.path("frequency_penalty").asDouble(-9)).isEqualTo(0.1);
            assertThat(body.path("presence_penalty").asDouble(-9)).isEqualTo(-0.1);
            assertThat(body.path("seed").asInt(-1)).isEqualTo(7);
            assertThat(body.path("stop").path(0).asString("")).isEqualTo("###");
        }

        @Test
        @DisplayName("the extra body can override anything above it, which is what makes it an escape hatch")
        void extraBodyIsAppliedLast() {
            var base = Fixtures.model();
            var withExtra = new ModelSpec(
                    base.endpoint(), base.name(), base.apiFormat(), base.capabilities(), base.reasoning(),
                    base.webSearchMode(), base.pricing(), base.contextWindow(), base.maxOutputTokens(),
                    base.maxTokensParam(), base.sampling(), base.routing(), base.tags(),
                    Map.of("max_tokens", 111, "plugins", List.of(Map.of("id", "web"))));

            var body = ChatWire.request(call(withExtra), false);
            assertThat(body.path("max_tokens").asInt(0)).isEqualTo(111);
            assertThat(body.path("plugins").path(0).path("id").asString("")).isEqualTo("web");
        }
    }

    @Nested
    class ResponseFormatGating {

        @Test
        @DisplayName("JSON mode is dropped for a model that never declared it")
        void jsonObjectNeedsTheCapability() {
            assertThat(ChatWire.responseFormat(ResponseFormat.JSON_OBJECT, Fixtures.model())).isNull();
            assertThat(ChatWire.responseFormat(
                            ResponseFormat.JSON_OBJECT,
                            Fixtures.with(Fixtures.model(), Capability.JSON_OBJECT))
                    .path("type").asString(""))
                    .isEqualTo("json_object");
        }

        @Test
        void jsonSchemaCarriesTheSchemaAndStrictness() {
            var format = new ResponseFormat.JsonSchema("answer", "{\"type\":\"object\"}", true);
            var node = ChatWire.responseFormat(
                    format, Fixtures.with(Fixtures.model(), Capability.JSON_SCHEMA));

            assertThat(node.path("type").asString("")).isEqualTo("json_schema");
            assertThat(node.path("json_schema").path("name").asString("")).isEqualTo("answer");
            assertThat(node.path("json_schema").path("strict").asBoolean(false)).isTrue();
            assertThat(node.path("json_schema").path("schema").path("type").asString("")).isEqualTo("object");
        }

        @Test
        void textNeedsNoField() {
            assertThat(ChatWire.responseFormat(ResponseFormat.TEXT, Fixtures.model())).isNull();
        }
    }

    @Nested
    class ProviderBlock {

        @Test
        @DisplayName("an endpoint that never heard of provider routing is sent nothing at all")
        void emptyRoutingProducesNoBlock() {
            assertThat(ChatWire.provider(ProviderRouting.NONE)).isNull();
            assertThat(ChatWire.request(call(Fixtures.model()), false).has("provider")).isFalse();
        }

        @Test
        void writesOnlyTheFieldsThatWereSet() {
            var routing = new ProviderRouting(
                    List.of("deepinfra/fp8", "together"), List.of(), List.of(), null, true,
                    ProviderRouting.Sort.THROUGHPUT);
            var node = ChatWire.provider(routing);

            assertThat(node.path("order").path(0).asString("")).isEqualTo("deepinfra/fp8");
            assertThat(node.path("require_parameters").asBoolean(false)).isTrue();
            assertThat(node.path("sort").asString("")).isEqualTo("throughput");
            assertThat(node.has("only")).isFalse();
            assertThat(node.has("ignore")).isFalse();
            assertThat(node.has("allow_fallbacks")).isFalse();
        }
    }

    @Nested
    class ReasoningDialects {

        private String dialectField(Reasoning reasoning) {
            var body = ChatWire.request(call(Fixtures.reasoning(Fixtures.model(), reasoning)), false);
            return body.toString();
        }

        @Test
        @DisplayName("saying nothing means sending nothing, leaving the model's own default")
        void noneSendsNothing() {
            var body = dialectField(Reasoning.UNSPECIFIED);
            assertThat(body).doesNotContain("reasoning").doesNotContain("thinking");
        }

        @Test
        @DisplayName("disabled is stated explicitly, because that is the only way to stop a reasoner")
        void flatDialectStatesBothDirections() {
            assertThat(dialectField(new Reasoning(
                            Reasoning.Dialect.REASONING_EFFORT, false, Reasoning.Effort.HIGH, 0, true)))
                    .contains("\"reasoning_effort\":\"none\"");
            assertThat(dialectField(new Reasoning(
                            Reasoning.Dialect.REASONING_EFFORT, true, Reasoning.Effort.HIGH, 0, true)))
                    .contains("\"reasoning_effort\":\"high\"");
        }

        @Test
        void nestedDialectCarriesEffortAndBudget() {
            var body = ChatWire.request(call(Fixtures.reasoning(Fixtures.model(),
                    new Reasoning(Reasoning.Dialect.REASONING, true, Reasoning.Effort.LOW, 2048, false))), false);
            assertThat(body.path("reasoning").path("effort").asString("")).isEqualTo("low");
            assertThat(body.path("reasoning").path("max_tokens").asInt(0)).isEqualTo(2048);
            assertThat(body.path("reasoning").path("exclude").asBoolean(true)).isFalse();
        }

        @Test
        void nestedDialectDisabledIsAnExplicitFalse() {
            var body = ChatWire.request(call(Fixtures.reasoning(Fixtures.model(),
                    new Reasoning(Reasoning.Dialect.REASONING, false, Reasoning.Effort.LOW, 0, true))), false);
            assertThat(body.path("reasoning").path("enabled").asBoolean(true)).isFalse();
        }

        @Test
        @DisplayName("the budget dialect turns an effort level into a token budget")
        void budgetDialectConvertsEffort() {
            var body = ChatWire.request(call(Fixtures.reasoning(Fixtures.model(),
                    new Reasoning(Reasoning.Dialect.THINKING, true, Reasoning.Effort.MEDIUM, 0, true))), false);
            assertThat(body.path("thinking").path("type").asString("")).isEqualTo("enabled");
            assertThat(body.path("thinking").path("budget_tokens").asInt(0))
                    .isEqualTo(Reasoning.Effort.MEDIUM.budgetTokens());
        }
    }

    @Nested
    class Attachments {

        @Test
        @DisplayName("text-only messages stay a plain string, which every local server understands")
        void plainMessagesUseTheStringForm() {
            assertThat(ChatWire.message(ChatMessage.user("hi")).path("content").isString()).isTrue();
        }

        @Test
        void imagesBecomeADataUrlPart() {
            var message = ChatMessage.user("look", List.of(
                    Attachment.image("shot.png", "image/png", new byte[] {1, 2, 3})));
            var content = ChatWire.message(message).path("content");

            assertThat(content.path(0).path("type").asString("")).isEqualTo("text");
            assertThat(content.path(1).path("type").asString("")).isEqualTo("image_url");
            assertThat(content.path(1).path("image_url").path("url").asString(""))
                    .startsWith("data:image/png;base64,");
        }

        @Test
        void documentsCarryTheirFilename() {
            var message = ChatMessage.user("", List.of(
                    Attachment.document("report.pdf", "application/pdf", new byte[] {9})));
            var part = ChatWire.message(message).path("content").path(0);

            assertThat(part.path("type").asString("")).isEqualTo("file");
            assertThat(part.path("file").path("filename").asString("")).isEqualTo("report.pdf");
        }

        @Test
        @DisplayName("a text attachment is labelled with its file name so the model knows what it is")
        void textAttachmentsAreNamedInline() {
            var message = ChatMessage.user("", List.of(Attachment.text("notes.md", "text/markdown", "body")));
            assertThat(ChatWire.message(message).path("content").path(0).path("text").asString(""))
                    .isEqualTo("notes.md:\nbody");
        }
    }

    @Nested
    class WebSearch {

        @Test
        @DisplayName("search options are sent only for the suffix mode, which is the only chat one")
        void onlineModeSendsSearchOptions() {
            var model = Fixtures.searching(
                    Fixtures.model(), WebSearchMode.ONLINE, com.unbi.engine.llm.spec.ApiFormat.CHAT_COMPLETIONS);
            var searching = new ChatCall(
                    model, List.of(ChatMessage.user("news?")), ResponseFormat.TEXT,
                    SamplingParams.UNSET, new ChatCall.WebSearch(true, true, "high"), "", "");

            assertThat(ChatWire.request(searching, false)
                            .path("web_search_options").path("search_context_size").asString(""))
                    .isEqualTo("high");
        }

        @Test
        void otherModesSendNothingHere() {
            var searching = new ChatCall(
                    Fixtures.model(), List.of(ChatMessage.user("news?")), ResponseFormat.TEXT,
                    SamplingParams.UNSET, new ChatCall.WebSearch(true, true, "high"), "", "");
            assertThat(ChatWire.request(searching, false).has("web_search_options")).isFalse();
        }
    }

    @Nested
    class ResponseParsing {

        @Test
        void readsTextUsageAndFinishReason() {
            var completion = MAPPER.readTree("""
                    {"model":"vendor/served","usage":{"prompt_tokens":100,"completion_tokens":20,
                     "total_tokens":120,"prompt_tokens_details":{"cached_tokens":40},
                     "completion_tokens_details":{"reasoning_tokens":5}},
                     "choices":[{"finish_reason":"stop","message":{"content":"answer"}}]}""");

            var result = ChatWire.parse(completion, TokenUsage.CachedTokenMode.INCLUDED, 12);
            assertThat(result.text()).isEqualTo("answer");
            assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
            assertThat(result.reportedModel()).isEqualTo("vendor/served");
            assertThat(result.latencyMillis()).isEqualTo(12);
            assertThat(result.usage().promptTokens()).isEqualTo(100);
            assertThat(result.usage().cachedPromptTokens()).isEqualTo(40);
            assertThat(result.usage().reasoningTokens()).isEqualTo(5);
        }

        @Test
        @DisplayName("a gateway that double-counts cached tokens is normalised, not believed")
        void additionalCachedTokensAreSubtracted() {
            var completion = MAPPER.readTree("""
                    {"usage":{"prompt_tokens":100,"completion_tokens":20,
                     "prompt_tokens_details":{"cached_tokens":40}},
                     "choices":[{"finish_reason":"stop","message":{"content":"a"}}]}""");

            var usage = ChatWire.parse(completion, TokenUsage.CachedTokenMode.ADDITIONAL, 0).usage();
            assertThat(usage.promptTokens()).isEqualTo(60);
            assertThat(usage.cachedPromptTokens()).isEqualTo(40);
            assertThat(usage.totalTokens()).isEqualTo(80);
        }

        @Test
        void aMissingUsageBlockIsNotAFailure() {
            var completion = MAPPER.readTree(
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"a\"}}]}");
            assertThat(ChatWire.parse(completion, TokenUsage.CachedTokenMode.INCLUDED, 0).usage())
                    .isEqualTo(TokenUsage.NONE);
        }

        @Test
        void arrayContentIsJoined() {
            var completion = MAPPER.readTree("""
                    {"choices":[{"finish_reason":"stop","message":{"content":[
                      {"type":"text","text":"one "},{"type":"text","text":"two"}]}}]}""");
            assertThat(ChatWire.parse(completion, TokenUsage.CachedTokenMode.INCLUDED, 0).text())
                    .isEqualTo("one two");
        }

        @Test
        @DisplayName("sources come from citations and annotations, never from the prose")
        void collectsSearchEvidence() {
            var completion = MAPPER.readTree("""
                    {"citations":["https://a.test"],
                     "choices":[{"finish_reason":"stop","message":{"content":"see https://invented.test",
                       "annotations":[{"url_citation":{"url":"https://b.test","title":"B"}}]}}]}""");

            var result = ChatWire.parse(completion, TokenUsage.CachedTokenMode.INCLUDED, 0);
            assertThat(result.searchPerformed()).isTrue();
            assertThat(result.sources()).extracting("url")
                    .containsExactly("https://a.test", "https://b.test");
        }

        @Test
        void truncationIsReportedAsSuch() {
            var completion = MAPPER.readTree(
                    "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"half\"}}]}");
            assertThat(ChatWire.parse(completion, TokenUsage.CachedTokenMode.INCLUDED, 0).wasTruncated())
                    .isTrue();
        }
    }

    @Nested
    class StreamReassembly {

        @Test
        void deltasBecomeOneAnswer() {
            var accumulator = new ChatWire.Accumulator();
            accumulator.accept(MAPPER.readTree(
                    "{\"model\":\"m\",\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}"));
            accumulator.accept(MAPPER.readTree("{\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}"));
            accumulator.accept(MAPPER.readTree(
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}],\"usage\":{\"prompt_tokens\":3}}"));

            var result = ChatWire.parse(
                    accumulator.completion(), TokenUsage.CachedTokenMode.INCLUDED, 0);
            assertThat(result.text()).isEqualTo("Hello");
            assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
            assertThat(result.usage().promptTokens()).isEqualTo(3);
            assertThat(result.reportedModel()).isEqualTo("m");
        }

        @Test
        void eachEventReturnsOnlyWhatItAdded() {
            var accumulator = new ChatWire.Accumulator();
            assertThat(accumulator.accept(MAPPER.readTree(
                            "{\"choices\":[{\"delta\":{\"content\":\"one\"}}]}")))
                    .isEqualTo("one");
            assertThat(accumulator.accept(MAPPER.readTree("{\"choices\":[{\"delta\":{}}]}"))).isEmpty();
        }

        @Test
        @DisplayName("a gateway repeating the whole message at the end does not double the answer")
        void finalWholeMessageDoesNotDuplicateDeltas() {
            var accumulator = new ChatWire.Accumulator();
            accumulator.accept(MAPPER.readTree("{\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"));
            accumulator.accept(MAPPER.readTree(
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Hello\"}}]}"));

            assertThat(ChatWire.parse(accumulator.completion(), TokenUsage.CachedTokenMode.INCLUDED, 0)
                            .text())
                    .isEqualTo("Hello");
        }

        @Test
        @DisplayName("a gateway that only ever sends a whole message still produces an answer")
        void wholeMessageOnlyStreamsStillWork() {
            var accumulator = new ChatWire.Accumulator();
            accumulator.accept(MAPPER.readTree(
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"whole\"}}]}"));
            assertThat(accumulator.textSoFar()).isEqualTo("whole");
        }
    }

    @Nested
    class Sse {

        @Test
        void readsDataLinesAndIgnoresEverythingElse() {
            var reader = new SseReader();
            assertThat(reader.accept("data: {\"a\":1}")).isNull();
            assertThat(reader.accept("")).satisfies(event -> {
                assertThat(event).isNotNull();
                assertThat(event.type()).isEmpty();
                assertThat(event.data()).isEqualTo("{\"a\":1}");
            });
            assertThat(reader.accept(": keep-alive")).isNull();
            assertThat(reader.accept("event: message")).isNull();
            assertThat(reader.accept("")).isNull();
        }

        @Test
        @DisplayName("only one space after the colon is eaten; the rest could be payload")
        void keepsSignificantWhitespace() {
            var reader = new SseReader();
            assertThat(reader.accept("data:  two")).isNull();
            assertThat(reader.accept("")).satisfies(event -> assertThat(event.data()).isEqualTo(" two"));
        }
    }
}
