package com.unbi.engine.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.llm.spec.Capability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * What one gateway published about one model, and what that renders as.
 *
 * <p>Every fixture here was captured from OpenRouter in this session and trimmed only by dropping
 * whole entries — no field inside one was touched, because the fields are the test. The per-host
 * document is the real seven-provider answer for {@code qwen/qwen3.5-35b-a3b}; the alias document is
 * the real, empty one for {@code ~anthropic/claude-opus-latest}.
 *
 * <p>Two of these assertions are about not inferring. {@code pricing.overrides} comes in two kinds
 * that mean different things and the note has to say which; and {@code pricing.web_search} arrives
 * two orders of magnitude away from every per-token field beside it, so it is reported as written
 * rather than multiplied into a per-million price it is not.
 */
class ModelFactsTest {

    @Test
    @DisplayName("reads a model's identity, ceilings and price from its own entry")
    void oneEntry() {
        var facts = facts("qwen/qwen3.5-35b-a3b", "model-endpoints.json");

        assertThat(facts.id()).isEqualTo("qwen/qwen3.5-35b-a3b");
        assertThat(facts.name()).isEqualTo("Qwen: Qwen3.5-35B-A3B");
        assertThat(facts.canonicalSlug()).isEqualTo("qwen/qwen3.5-35b-a3b-20260224");
        assertThat(facts.huggingFaceId()).isEqualTo("Qwen/Qwen3.5-35B-A3B");
        assertThat(facts.contextWindow()).isEqualTo(262_144);
        assertThat(facts.maxOutputTokens()).isEqualTo(65_536);
        assertThat(facts.price()).isEqualTo("$0.1625 in / $1.30 out per M");
        assertThat(facts.released()).isEqualTo("2026-02-25");
        assertThat(facts.tokenizer()).isEqualTo("Qwen3");
        assertThat(facts.moderated()).isFalse();
        assertThat(facts.reasoningMandatory()).isFalse();
        assertThat(facts.defaultParameters()).isEqualTo("temperature 1 · top_p 0.95 · top_k 20");
    }

    @Test
    @DisplayName("a price is kept to four places: $0.16 for $0.1625 is a 1.5% lie about a batch")
    void priceKeepsThePlacesThatMatter() {
        assertThat(facts("qwen/qwen3.5-35b-a3b", null).price()).contains("$0.1625");
    }

    @Test
    @DisplayName("capabilities and modalities are this engine's reading of what was listed")
    void capabilitiesAndModalities() {
        var facts = facts("qwen/qwen3.5-35b-a3b", null);

        assertThat(facts.capabilities()).contains(
                Capability.TOOLS, Capability.JSON_OBJECT, Capability.JSON_SCHEMA,
                Capability.REASONING, Capability.VISION);
        // Nothing in the entry speaks about files or search, so nothing is claimed about them.
        assertThat(facts.capabilities()).doesNotContain(Capability.FILES, Capability.WEB_SEARCH);
        assertThat(facts.inputModalities()).containsExactly("text", "image", "video");
        assertThat(facts.outputModalities()).containsExactly("text");
        assertThat(facts.supportedParameters()).contains("max_tokens", "structured_outputs", "tools");
    }

    @Test
    @DisplayName("one line per host, so provider order can be chosen by reading them against each other")
    void providerLines() {
        var facts = facts("qwen/qwen3.5-35b-a3b", "model-endpoints.json");

        assertThat(facts.providers()).hasSize(7);
        assertThat(facts.providers().getFirst())
                .isEqualTo("Darkbloom · fp4 · 262,144 ctx · 32,768 out · $0.08 / $0.75 per M · uptime 99.9%");
        assertThat(facts.providers()).anySatisfy(line -> assertThat(line)
                .isEqualTo("DeepInfra · fp8 · 262,144 ctx · 81,920 out · $0.14 / $1.00 per M · uptime 100.0%"));
        assertThat(facts.providersProblem()).isEmpty();
    }

    @Test
    @DisplayName("an alias's empty endpoint list is an answer, and says what it means")
    void anAliasServesNothingItself() {
        var facts = ModelFacts.read(
                entry("~deepseek/deepseek-pro-latest"), StubGateway.read("model-endpoints-alias.json"), "");

        assertThat(facts.providers()).isEmpty();
        assertThat(facts.providersProblem()).isEqualTo("the gateway publishes no endpoints for this alias");
        assertThat(facts.providersBlock()).isEqualTo("the gateway publishes no endpoints for this alias");
        assertThat(facts.aliasOf()).isEqualTo("DeepSeek: DeepSeek V4 Pro 0813 — deepseek/deepseek-v4-pro-0813");
    }

    @Test
    @DisplayName("a time-of-day override says so, with the range the price moves across")
    void timeOfDayPricing() {
        var facts = facts("~deepseek/deepseek-pro-latest", null);

        assertThat(facts.pricingNote()).isEqualTo("varies by time of day: $0.66–$1.32 /M in");
    }

    @Test
    @DisplayName("a long-prompt override says that instead — the same field, a different fact")
    void longPromptPricing() {
        var facts = facts("~openai/gpt-sol-latest", null);

        assertThat(facts.pricingNote())
                .isEqualTo("varies above 272,000 prompt tokens: $2.00–$4.00 /M in");
    }

    @Test
    @DisplayName("web search is reported as the gateway quotes it, not multiplied into a token price")
    void webSearchIsNotAPerTokenPrice() {
        var facts = facts("~openai/gpt-sol-latest", null);

        // Per token this would read $10,000 per million, which is the kind of number that makes a
        // whole panel untrustworthy. The gateway wrote 0.01; that is what is shown.
        assertThat(facts.webSearchPrice()).isEqualTo(0.01);
        // What the row shows, rather than the double the multiplication happens to land on: the
        // observable fact is $0.20 per million, and 0.0000002 × 1e6 is not exactly 0.2 in binary.
        assertThat(Amounts.money(facts.cacheReadPer1M())).isEqualTo("$0.20");
        assertThat(facts.cacheWritePer1M()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("what the gateway left out stays out")
    void nothingIsInvented() {
        var qwen = facts("qwen/qwen3.5-35b-a3b", null);

        assertThat(qwen.knowledgeCutoff()).isEmpty();
        assertThat(qwen.aliasOf()).isEmpty();
        assertThat(qwen.pricingNote()).isEmpty();
        assertThat(qwen.cacheReadPer1M()).isNull();
        assertThat(qwen.webSearchPrice()).isNull();
        assertThat(qwen.defaultEffort()).isEmpty();
        assertThat(qwen.supportedEfforts()).isEmpty();
    }

    @Test
    @DisplayName("reasoning efforts survive intact, including the ones above 'high'")
    void reasoningEfforts() {
        var facts = facts("~openai/gpt-sol-latest", null);

        assertThat(facts.supportedEfforts()).containsExactly("max", "xhigh", "high", "medium", "low", "none");
        assertThat(facts.defaultEffort()).isEqualTo("medium");
        assertThat(facts.knowledgeCutoff()).isEqualTo("2026-02-16");
    }

    @Test
    @DisplayName("a failed per-host call keeps its reason rather than reading as 'no providers'")
    void anUnreachableEndpointDocumentKeepsItsReason() {
        var facts = ModelFacts.read(
                entry("qwen/qwen3.5-35b-a3b"), null, "no such endpoint on this gateway");

        assertThat(facts.providers()).isEmpty();
        assertThat(facts.providersBlock()).isEqualTo("no such endpoint on this gateway");
    }

    private static ModelFacts facts(String id, String endpointsFixture) {
        return ModelFacts.read(
                entry(id), endpointsFixture == null ? null : StubGateway.read(endpointsFixture), "");
    }

    private static JsonNode entry(String id) {
        return ModelListingReader.entry(StubGateway.read("models.json"), id)
                .orElseThrow(() -> new AssertionError("The trimmed listing has no entry for " + id));
    }
}
