package com.unbi.engine.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.llm.Fixtures;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatMessage;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.FinishReason;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.RatePolicy;
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

class ResponsesWireTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelSpec responsesModel() {
        return Fixtures.searching(Fixtures.model(), WebSearchMode.NONE, ApiFormat.RESPONSES);
    }

    private static ChatCall call(ModelSpec model, ChatMessage... messages) {
        return ChatCall.of(model, List.of(messages));
    }

    @Nested
    class RequestBody {

        @Test
        @DisplayName("the system role is renamed, because this API rejects the old spelling")
        void systemBecomesDeveloper() {
            var body = ResponsesWire.request(
                    call(responsesModel(), ChatMessage.system("rules"), ChatMessage.user("hi")), false);
            assertThat(body.path("input").path(0).path("role").asString("")).isEqualTo("developer");
            assertThat(body.path("input").path(1).path("role").asString("")).isEqualTo("user");
        }

        @Test
        void textIsAlwaysATypedPart() {
            var body = ResponsesWire.request(call(responsesModel(), ChatMessage.user("hi")), false);
            var part = body.path("input").path(0).path("content").path(0);
            assertThat(part.path("type").asString("")).isEqualTo("input_text");
            assertThat(part.path("text").asString("")).isEqualTo("hi");
        }

        @Test
        @DisplayName("nothing is stored server-side, which is not this pack's decision to make for a user")
        void doesNotStoreTheConversation() {
            assertThat(ResponsesWire.request(call(responsesModel(), ChatMessage.user("hi")), false)
                            .path("store").asBoolean(true))
                    .isFalse();
        }

        @Test
        @DisplayName("top_k and min_p are withheld: this API rejects them rather than ignoring them")
        void unsupportedSamplersAreNotSent() {
            var sampling = new SamplingParams(0.5d, 0.9d, 40, 0.05d, null, null, null, List.of(), null);
            var body = ResponsesWire.request(new ChatCall(
                    responsesModel(), List.of(ChatMessage.user("hi")), ResponseFormat.TEXT,
                    sampling, ChatCall.WebSearch.OFF, "", ""), false);

            assertThat(body.path("temperature").asDouble(-1)).isEqualTo(0.5);
            assertThat(body.path("top_p").asDouble(-1)).isEqualTo(0.9);
            assertThat(body.has("top_k")).isFalse();
            assertThat(body.has("min_p")).isFalse();
        }

        @Test
        void attachmentsUseThisApiSpelling() {
            var message = ChatMessage.user("look", List.of(
                    Attachment.image("a.png", "image/png", new byte[] {1}),
                    Attachment.document("b.pdf", "application/pdf", new byte[] {2})));
            var content = ResponsesWire.message(message, false).path("content");

            assertThat(content.path(1).path("type").asString("")).isEqualTo("input_image");
            assertThat(content.path(1).path("image_url").asString("")).startsWith("data:image/png;base64,");
            assertThat(content.path(2).path("type").asString("")).isEqualTo("input_file");
            assertThat(content.path(2).path("filename").asString("")).isEqualTo("b.pdf");
        }

        @Test
        @DisplayName("cache controls only go to an endpoint that says it accepts them")
        void cacheBreakpointsAreEndpointGated() {
            var message = ChatMessage.user("hi").withCacheBreakpoint();
            assertThat(ResponsesWire.message(message, false).path("content").path(0)
                            .has("prompt_cache_breakpoint"))
                    .isFalse();
            assertThat(ResponsesWire.message(message, true).path("content").path(0)
                            .path("prompt_cache_breakpoint").path("mode").asString(""))
                    .isEqualTo("explicit");
        }

        @Test
        void cacheKeyIsSentOnlyWhenTheEndpointSupportsIt() {
            var base = Fixtures.endpoint();
            var caching = new EndpointSpec(
                    base.id(), base.profile(), base.baseUrl(), base.authScheme(), base.credentialRef(),
                    Map.of(), RatePolicy.UNLIMITED, 30_000, false,
                    TokenUsage.CachedTokenMode.INCLUDED, true);

            var withoutSupport = new ChatCall(
                    responsesModel(), List.of(ChatMessage.user("hi")), ResponseFormat.TEXT,
                    SamplingParams.UNSET, ChatCall.WebSearch.OFF, "prefix-1", "");
            var withSupport = new ChatCall(
                    Fixtures.searching(Fixtures.model(caching), WebSearchMode.NONE, ApiFormat.RESPONSES),
                    List.of(ChatMessage.user("hi")), ResponseFormat.TEXT,
                    SamplingParams.UNSET, ChatCall.WebSearch.OFF, "prefix-1", "");

            assertThat(ResponsesWire.request(withoutSupport, false).has("prompt_cache_key")).isFalse();
            assertThat(ResponsesWire.request(withSupport, false).path("prompt_cache_key").asString(""))
                    .isEqualTo("prefix-1");
        }
    }

    @Nested
    class HostedSearch {

        private ChatCall searching(boolean required) {
            var model = Fixtures.searching(Fixtures.model(), WebSearchMode.RESPONSES_TOOL, ApiFormat.RESPONSES);
            return new ChatCall(
                    model, List.of(ChatMessage.user("what happened?")), ResponseFormat.TEXT,
                    SamplingParams.UNSET, new ChatCall.WebSearch(true, required, "medium"), "", "");
        }

        @Test
        void sendsTheToolAndAsksForItsSources() {
            var body = ResponsesWire.request(searching(true), false);
            assertThat(body.path("tools").path(0).path("type").asString("")).isEqualTo("web_search");
            assertThat(body.path("tools").path(0).path("search_context_size").asString(""))
                    .isEqualTo("medium");
            assertThat(body.path("tool_choice").asString("")).isEqualTo("required");
            assertThat(body.path("include").path(0).asString(""))
                    .isEqualTo("web_search_call.action.sources");
        }

        @Test
        void optionalSearchLetsTheModelDecide() {
            assertThat(ResponsesWire.request(searching(false), false).path("tool_choice").asString(""))
                    .isEqualTo("auto");
        }

        @Test
        void aModelWithoutTheToolModeSendsNoTools() {
            var call = new ChatCall(
                    responsesModel(), List.of(ChatMessage.user("hi")), ResponseFormat.TEXT,
                    SamplingParams.UNSET, new ChatCall.WebSearch(true, true, ""), "", "");
            assertThat(ResponsesWire.request(call, false).has("tools")).isFalse();
        }
    }

    @Nested
    class ReasoningTranslation {

        @Test
        void effortDialectsMapOnto() {
            assertThat(ResponsesWire.reasoning(new Reasoning(
                            Reasoning.Dialect.REASONING_EFFORT, true, Reasoning.Effort.HIGH, 0, true))
                    .path("effort").asString(""))
                    .isEqualTo("high");
            assertThat(ResponsesWire.reasoning(new Reasoning(
                            Reasoning.Dialect.REASONING, false, Reasoning.Effort.HIGH, 0, true))
                    .path("effort").asString(""))
                    .isEqualTo("none");
        }

        @Test
        @DisplayName("the budget dialect has no analogue here and is left to the extra body")
        void budgetDialectIsNotFaked() {
            assertThat(ResponsesWire.reasoning(new Reasoning(
                            Reasoning.Dialect.THINKING, true, Reasoning.Effort.HIGH, 4096, true)))
                    .isNull();
            assertThat(ResponsesWire.reasoning(Reasoning.UNSPECIFIED)).isNull();
        }
    }

    @Nested
    class Formats {

        @Test
        void jsonSchemaIsFlatHereRatherThanNested() {
            var format = ResponsesWire.textFormat(
                    new ResponseFormat.JsonSchema("answer", "{\"type\":\"object\"}", true),
                    Fixtures.with(responsesModel(), Capability.JSON_SCHEMA));

            assertThat(format.path("type").asString("")).isEqualTo("json_schema");
            assertThat(format.path("name").asString("")).isEqualTo("answer");
            assertThat(format.path("schema").path("type").asString("")).isEqualTo("object");
        }

        @Test
        void anUndeclaredFormatIsDropped() {
            assertThat(ResponsesWire.textFormat(ResponseFormat.JSON_OBJECT, responsesModel())).isNull();
        }
    }

    @Nested
    class ResponseParsing {

        @Test
        void readsTextAndUsage() {
            var response = MAPPER.readTree("""
                    {"model":"served","status":"completed",
                     "usage":{"input_tokens":50,"output_tokens":10,"total_tokens":60,
                       "input_tokens_details":{"cached_tokens":20},
                       "output_tokens_details":{"reasoning_tokens":4}},
                     "output":[{"type":"message","content":[{"type":"output_text","text":"answer"}]}]}""");

            var result = ResponsesWire.parse(response, 7);
            assertThat(result.text()).isEqualTo("answer");
            assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
            assertThat(result.usage().promptTokens()).isEqualTo(50);
            assertThat(result.usage().cachedPromptTokens()).isEqualTo(20);
            assertThat(result.usage().reasoningTokens()).isEqualTo(4);
        }

        @Test
        @DisplayName("an incomplete response caused by the output ceiling is a truncation, not a mystery")
        void incompleteBecauseOfLengthIsTruncation() {
            var response = MAPPER.readTree("""
                    {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                     "output":[{"type":"message","content":[{"type":"output_text","text":"half"}]}]}""");
            assertThat(ResponsesWire.parse(response, 0).wasTruncated()).isTrue();
        }

        @Test
        void collectsSearchEvidenceFromTheCallAndTheAnnotations() {
            var response = MAPPER.readTree("""
                    {"status":"completed","output":[
                      {"type":"web_search_call","status":"completed",
                       "action":{"type":"search","sources":[{"url":"https://a.test","title":"A"}]}},
                      {"type":"message","content":[{"type":"output_text","text":"see",
                       "annotations":[{"url_citation":{"url":"https://b.test"}}]}]}]}""");

            var result = ResponsesWire.parse(response, 0);
            assertThat(result.searchPerformed()).isTrue();
            assertThat(result.sources()).extracting("url")
                    .containsExactly("https://a.test", "https://b.test");
        }

        @Test
        @DisplayName("no search call means no search happened, whatever the prose claims")
        void proseWithoutASearchCallIsNotEvidence() {
            var response = MAPPER.readTree("""
                    {"status":"completed","output":[{"type":"message",
                      "content":[{"type":"output_text","text":"see https://invented.test"}]}]}""");
            assertThat(ResponsesWire.parse(response, 0).searchPerformed()).isFalse();
        }
    }

    @Nested
    class StreamReassembly {

        @Test
        void deltasAndTheTerminalEventBecomeOneResponse() {
            var accumulator = new ResponsesWire.Accumulator();
            accumulator.accept(MAPPER.readTree(
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"Hel\"}"));
            accumulator.accept(MAPPER.readTree(
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"lo\"}"));
            accumulator.accept(MAPPER.readTree("""
                    {"type":"response.completed","response":{"status":"completed",
                     "usage":{"input_tokens":9,"output_tokens":2},
                     "output":[{"type":"message","content":[{"type":"output_text","text":"Hello"}]}]}}"""));

            var result = ResponsesWire.parse(accumulator.response(), 0);
            assertThat(result.text()).isEqualTo("Hello");
            assertThat(result.usage().promptTokens()).isEqualTo(9);
        }

        @Test
        @DisplayName("the incomplete terminal carries usage and truncation, and is the easiest to drop")
        void incompleteTerminalIsCaught() {
            var accumulator = new ResponsesWire.Accumulator();
            accumulator.accept(MAPPER.readTree(
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"half\"}"));
            accumulator.accept(MAPPER.readTree("""
                    {"type":"response.incomplete","response":{"status":"incomplete",
                     "incomplete_details":{"reason":"max_output_tokens"},
                     "usage":{"input_tokens":1000,"output_tokens":30000},
                     "output":[{"type":"message","content":[{"type":"output_text","text":"half"}]}]}}"""));

            var result = ResponsesWire.parse(accumulator.response(), 0);
            assertThat(result.wasTruncated()).isTrue();
            assertThat(result.usage().completionTokens()).isEqualTo(30_000);
        }

        @Test
        @DisplayName("a stream that just stops says incomplete rather than claiming a clean finish")
        void aStreamWithNoTerminalEventIsHonest() {
            var accumulator = new ResponsesWire.Accumulator();
            accumulator.accept(MAPPER.readTree(
                    "{\"type\":\"response.output_text.delta\",\"delta\":\"cut\"}"));

            var result = ResponsesWire.parse(accumulator.response(), 0);
            assertThat(result.text()).isEqualTo("cut");
            assertThat(result.finishReason()).isNotEqualTo(FinishReason.STOP);
        }
    }
}
