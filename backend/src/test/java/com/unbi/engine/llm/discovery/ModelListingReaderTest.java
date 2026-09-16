package com.unbi.engine.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.Reasoning;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * What a gateway said, and what this engine concluded from it.
 *
 * <p>The payloads below are cut down from real {@code GET /v1/models} responses, not invented: the
 * field names, the nesting and the decimal-string prices are the parts that actually differ between
 * gateways, and they are exactly the parts a hand-written fixture would quietly normalise away.
 *
 * <p>The assertions that matter most are the negative ones. A discovery that fills a capability in
 * from a guess produces a model declaration nobody verified, which the capability check then trusts
 * — so "said nothing, so changed nothing" is the property under test, not an edge case.
 */
class ModelListingReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Nested
    @DisplayName("OpenRouter")
    class OpenRouter {

        /** Model-level fields, plus the per-host block that decides what a call may actually ask for. */
        private static final String BODY = """
                {"data":[{
                  "id":"z-ai/glm-5.3-flash",
                  "name":"Z.ai: GLM 5.3 Flash",
                  "context_length":1310720,
                  "architecture":{"input_modalities":["text","image","video"]},
                  "pricing":{"prompt":"0.000000075","completion":"0.00000025",
                             "input_cache_read":"0.000000015"},
                  "top_provider":{"context_length":1048576,"max_completion_tokens":131072},
                  "supported_parameters":["max_tokens","reasoning","reasoning_effort",
                                          "response_format","structured_outputs","tools","temperature"],
                  "reasoning":{"mandatory":true,"default_enabled":true,
                               "supported_efforts":["max","high","low"],"default_effort":"max"}
                }]}""";

        @Test
        @DisplayName("reads capabilities from supported_parameters and modalities, not from the name")
        void capabilities() {
            var model = only(BODY);

            assertThat(model.capabilities()).containsExactlyInAnyOrder(
                    Capability.JSON_OBJECT,
                    Capability.JSON_SCHEMA,
                    Capability.TOOLS,
                    Capability.REASONING,
                    Capability.VISION,
                    Capability.PROMPT_CACHE);
            // Nothing in the body speaks about search, so nothing is claimed about it.
            assertThat(model.capabilities()).doesNotContain(Capability.WEB_SEARCH, Capability.FILES);
        }

        @Test
        @DisplayName("prefers the serving host's ceiling over the best any host offers")
        void ceilings() {
            var model = only(BODY);

            // The model advertises 1310720; the host that will actually take the call accepts
            // 1048576, and a context window the call cannot use is a context window that misleads.
            assertThat(model.contextWindow()).isEqualTo(1_048_576);
            assertThat(model.maxOutputTokens()).isEqualTo(131_072);
        }

        @Test
        @DisplayName("converts per-token decimal strings into this engine's per-million doubles")
        void pricing() {
            var model = only(BODY);

            assertThat(model.inputPer1M()).isEqualTo(0.075);
            assertThat(model.outputPer1M()).isEqualTo(0.25);
        }

        @Test
        void reasoningDialectAndEffortsComeFromWhatIsListed() {
            var model = only(BODY);

            assertThat(model.reasoningDialect()).isEqualTo(Reasoning.Dialect.REASONING_EFFORT);
            assertThat(model.efforts()).containsExactly("max", "high", "low");
            assertThat(model.maxTokensParam()).isEqualTo(ModelSpec.MaxTokensParam.MAX_TOKENS);
        }

        @Test
        @DisplayName("a mandatory-reasoning model is discovered as reasoning ON, not merely as having a dialect")
        void mandatoryReasoning() {
            var model = only(BODY);

            // The failure this prevents was measured: a dialect with the switch left off sends
            // "turn reasoning off", and this gateway answers every such call with
            // "Reasoning is mandatory for this endpoint and cannot be disabled".
            assertThat(model.reasoningOn()).isTrue();
        }

        @Test
        @DisplayName("the gateway's own default effort wins over whatever was set")
        void defaultEffort() {
            assertThat(only(BODY).effortFor("medium")).contains("max");
        }
    }

    @Nested
    @DisplayName("OmniRoute")
    class OmniRoute {

        private static final String BODY = """
                {"data":[{
                  "id":"cx/gpt-5.5-low",
                  "name":"cx/GPT 5.5 (Low)",
                  "api_format":"responses",
                  "context_length":272000,
                  "max_output_tokens":128000,
                  "supported_endpoints":["responses"],
                  "capabilities":{"vision":true,"tool_calling":true,"reasoning":true,"thinking":true,
                                  "effort_tiers":["none","low","medium","high","xhigh"]},
                  "input_modalities":["text","image"]
                }]}""";

        @Test
        @DisplayName("a declared api_format is carried through, so a Responses-only model is not sent to chat")
        void apiFormat() {
            var model = only(BODY);

            assertThat(model.apiFormat()).isEqualTo(ApiFormat.RESPONSES);
            assertThat(model.contextWindow()).isEqualTo(272_000);
            assertThat(model.maxOutputTokens()).isEqualTo(128_000);
        }

        @Test
        @DisplayName("effort tiers survive intact, including the ones above 'high'")
        void efforts() {
            var model = only(BODY);

            assertThat(model.efforts()).containsExactly("none", "low", "medium", "high", "xhigh");
            assertThat(model.capabilities())
                    .contains(Capability.VISION, Capability.TOOLS, Capability.REASONING);
        }

        @Test
        @DisplayName("says nothing about the output-limit field, because this gateway does not")
        void leavesUnknownsAlone() {
            var model = only(BODY);

            assertThat(model.maxTokensParam()).isNull();
            assertThat(model.inputPer1M()).isNull();
            assertThat(model.outputPer1M()).isNull();
            // No reasoning block at all: nothing is claimed about whether it reasons by default.
            assertThat(model.reasoningOn()).isNull();
        }

        @Test
        @DisplayName("an effort the model does not list is replaced; one it lists is left alone")
        void effortFallsBackOnlyWhenItMust() {
            var model = only(BODY);

            assertThat(model.effortFor("high")).isEmpty();
            assertThat(model.effortFor("minimal")).contains("medium");
        }
    }

    @Nested
    @DisplayName("llama.cpp")
    class LlamaCpp {

        @Test
        @DisplayName("an id and a context size is a complete answer, and claims nothing else")
        void sparseListing() {
            var model = only("""
                    {"data":[{"id":"gemma4-31b-local","object":"model","owned_by":"llamacpp",
                              "meta":{"n_ctx":65536,"n_ctx_train":262144}}]}""");

            assertThat(model.id()).isEqualTo("gemma4-31b-local");
            assertThat(model.contextWindow()).isEqualTo(65_536);
            assertThat(model.capabilities()).isEmpty();
            assertThat(model.apiFormat()).isNull();
            assertThat(model.reasoningDialect()).isNull();
            assertThat(model.maxOutputTokens()).isNull();
        }
    }

    @Nested
    @DisplayName("the paged envelope")
    class Envelope {

        /**
         * The shape a live OpenRouter listing now arrives in: 735 KB, 443 entries, a
         * {@code total_count} beside them and a {@code links.next} that is currently null. The
         * fixture is that response with all but five entries dropped — so the count and the array
         * disagree on purpose.
         */
        private static final String BODY = """
                {"data":[
                   {"id":"vendor/one","context_length":8192},
                   {"id":"vendor/two","context_length":8192}
                 ],
                 "total_count":443,
                 "links":{"next":null}}""";

        @Test
        @DisplayName("the count comes from total_count, so a page size is never read as a catalogue size")
        void countPrefersTotalCount() {
            assertThat(ModelListingReader.count(MAPPER.readTree(BODY))).isEqualTo(443);
            assertThat(ModelListingReader.read(MAPPER.readTree(BODY))).hasSize(2);
        }

        @Test
        @DisplayName("a gateway that publishes no total is counted by what it sent")
        void countFallsBackToTheArray() {
            assertThat(ModelListingReader.count(MAPPER.readTree(
                            "{\"data\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\"}]}")))
                    .isEqualTo(3);
            assertThat(ModelListingReader.count(MAPPER.readTree("{}"))).isZero();
            assertThat(ModelListingReader.count(null)).isZero();
        }

        @Test
        @DisplayName("the envelope does not hide an entry from a lookup by id")
        void entriesAreFoundInsideIt() {
            var body = MAPPER.readTree(BODY);

            assertThat(ModelListingReader.entry(body, "VENDOR/TWO"))
                    .isPresent()
                    .get()
                    .satisfies(entry -> assertThat(entry.path("id").asString("")).isEqualTo("vendor/two"));
            assertThat(ModelListingReader.entry(body, "vendor/three")).isEmpty();
            assertThat(ModelListingReader.entry(body, "")).isEmpty();
        }
    }

    @Test
    @DisplayName("a body with no model list is an empty result rather than a failure")
    void unreadableBody() {
        assertThat(ModelListingReader.read(MAPPER.readTree("{\"models\":[]}"))).isEmpty();
        assertThat(ModelListingReader.read(MAPPER.readTree("{}"))).isEmpty();
        assertThat(ModelListingReader.read(null)).isEmpty();
    }

    @Test
    @DisplayName("entries with no id are skipped rather than given one")
    void skipsNamelessEntries() {
        var found = ModelListingReader.read(MAPPER.readTree(
                "{\"data\":[{\"object\":\"model\"},{\"id\":\"real\"}]}"));

        assertThat(found).singleElement().extracting(DiscoveredModel::id).isEqualTo("real");
    }

    private static DiscoveredModel only(String body) {
        var found = ModelListingReader.read(MAPPER.readTree(body));
        assertThat(found).hasSize(1);
        return found.getFirst();
    }
}
