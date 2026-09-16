package com.unbi.engine.nodes.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.core.node.NodeDefinition;
import com.unbi.engine.core.node.NodeDescriptor;
import com.unbi.engine.core.node.NodeOutput;
import com.unbi.engine.core.node.NodeProbe;
import com.unbi.engine.core.node.Widget;
import com.unbi.engine.llm.Fixtures;
import com.unbi.engine.llm.discovery.StubGateway;
import com.unbi.engine.nodes.llm.model.LlmEndpointInfo;
import com.unbi.engine.nodes.llm.model.LlmModelInfo;
import com.unbi.engine.support.RecordingContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two info nodes against captured gateway bodies.
 *
 * <p>Same rung as {@code LlmNodesTest}: no Spring, no engine, no socket. The only thing standing in
 * for the network is {@link StubGateway}, which overrides the one method that reaches it and leaves
 * credential resolution alone — so these tests still go through the object a run goes through.
 *
 * <p>The load-bearing test here is {@code everyRowIsWrittenOnEveryFetch}. A fetch that wrote only
 * the rows it found values for would leave the rest showing a previous endpoint's figures under a
 * fresh timestamp, and a stale number that looks current is worse than no number at all.
 */
class LlmInfoNodesTest {

    private static final String MODEL = "qwen/qwen3.5-35b-a3b";

    /** The listing's one model priced at "0" — free, which is not the same as unpriced. */
    private static final String FREE_MODEL = "inclusionai/ling-3.0-flash-vl:free";

    @Nested
    class EndpointInfo {

        @Test
        @DisplayName("fills every headline row from what the gateway published")
        void headlineRows(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var probe = probe(directory, gateways, node(directory, gateways));

            assertThat(probe.ok()).isTrue();
            assertThat(probe.message()).isEqualTo("OpenRouter answered — 443 models served.");
            assertThat(probe.values())
                    .containsEntry("gateway", "OpenRouter · https://openrouter.ai/api/v1")
                    .containsEntry("reachable", true)
                    .containsEntry("modelsServed", "443")
                    .containsEntry("keyLabel", "unbi-engine dev")
                    .containsEntry("credits", "$37.66 of $50.00")
                    .containsEntry("usageToday", "$0.42");
            assertThat(String.valueOf(probe.values().get("fetchedAt"))).endsWith("Z");
        }

        @Test
        @DisplayName("the grouped detail comes from the same fetch, including the account balance")
        void advancedRows(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var probe = probe(directory, gateways, node(directory, gateways));

            assertThat(probe.values())
                    .containsEntry("usageTotal", "$12.34")
                    .containsEntry("usageWeekly", "$3.10")
                    .containsEntry("freeTier", false)
                    .containsEntry("byokInLimit", false)
                    // This key's cap has no refill published, and a blank row says so.
                    .containsEntry("limitResets", "")
                    .containsEntry("freeRequests", "3 of 200 used")
                    .containsEntry("balance", "$37.66 = $50.00 credits − $12.34 used")
                    .containsEntry("modelsWithVision", "4")
                    .containsEntry("modelsWithFileInput", "2")
                    .containsEntry("inputModalities", List.of("file", "image", "text", "video"));
        }

        @Test
        @DisplayName("the credential probe goes first, and the extras spend what is left")
        void callsAreMadeInPriorityOrder(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            probe(directory, gateways, node(directory, gateways));

            assertThat(gateways.asked()).containsExactly("/key", "/models", "/credits");
        }

        @Test
        @DisplayName("a balance this key cannot read is a sentence, and the node stays green")
        void anUnreadableBalanceIsNotAFailure(@TempDir Path directory) throws Exception {
            // Measured: /credits answers 403 for a key that is not a management key, which is most
            // of them. A node that went red for that would be red for nearly every user.
            var gateways = new StubGateway()
                    .answering("/key", "key.json")
                    .answering("/models", "models.json")
                    .failing("/credits", 403, "User not found.");
            var probe = probe(directory, gateways, node(directory, gateways));

            assertThat(probe.ok()).isTrue();
            // The same body that refused the balance says is_management_key: false, which is the
            // half of the answer that stops the user re-checking a credential that is fine.
            assertThat(probe.values())
                    .containsEntry("balance", "not readable: this key is not a management key");
            assertThat(probe.details()).anySatisfy(line -> assertThat(line).contains("/credits"));
        }

        @Test
        @DisplayName("a refusal that is not about the key keeps the transport's own words")
        void anUnreadableBalanceIsNotBlamedOnTheKeysKind(@TempDir Path directory) throws Exception {
            var gateways = new StubGateway()
                    .answering("/key", "key.json")
                    .answering("/models", "models.json")
                    .failing("/credits", 404, "Not Found");
            var probe = probe(directory, gateways, node(directory, gateways));

            // is_management_key is false here too, and has nothing to do with a 404 — blaming it
            // would send the reader after the wrong setting, which is what OptionalFetch prevents.
            assertThat(probe.values()).containsEntry("balance", "no such endpoint on this gateway");
        }

        @Test
        @DisplayName("a key the gateway named after itself reaches no row, no struct and no summary")
        void aKeyShapedLabelNeverLeavesTheNode(@TempDir Path directory) throws Exception {
            // An unnamed OpenRouter key is labelled with a truncated form of the key itself. A row
            // is a widget value, so it would be saved into the workflow and travel with the file.
            var gateways = unnamedKey();
            var probe = probe(directory, gateways, node(directory, gateways));

            var context = RecordingContext.with("endpoint", Fixtures.openrouter());
            node(directory, gateways).execute(context);
            LlmEndpointInfo info = context.output("info");

            assertThat(probe.values()).containsEntry("keyLabel", "unnamed key");
            assertThat(info.keyLabel()).isEqualTo("unnamed key");
            assertThat(String.valueOf(probe.values().get("keyLabel"))).doesNotContain("sk-", "…");
            assertThat(info.toString()).doesNotContain("sk-", "…");
            assertThat(context.rawOutput("summary").toString()).doesNotContain("sk-", "…");
            assertThat(String.join("\n", context.logs())).doesNotContain("sk-", "…");
        }

        @Test
        @DisplayName("the key's cap reports how it refills and whether BYOK spend counts against it")
        void theLimitRowsComeFromTheKeyBody(@TempDir Path directory) throws Exception {
            var gateways = unnamedKey();
            var probe = probe(directory, gateways, node(directory, gateways));

            assertThat(probe.values())
                    .containsEntry("limitResets", "monthly")
                    .containsEntry("byokInLimit", true);
        }

        @Test
        @DisplayName("an unreachable endpoint is the verdict, and the rows it could not fill are blank")
        void anUnreachableEndpointIsRed(@TempDir Path directory) throws Exception {
            var gateways = new StubGateway().failing("/key", 401, "No auth credentials found");
            var probe = probe(directory, gateways, node(directory, gateways));

            assertThat(probe.ok()).isFalse();
            assertThat(probe.message()).contains("401");
            assertThat(probe.values())
                    .containsEntry("reachable", false)
                    .containsEntry("modelsServed", "")
                    .containsEntry("credits", "");
            // The listing is never asked for once the verdict is already "no".
            assertThat(gateways.asked()).containsExactly("/key");
        }

        @Test
        @DisplayName("every row is written on every fetch, so a re-fetch cannot leave a stale fact")
        void everyRowIsWrittenOnEveryFetch(@TempDir Path directory) throws Exception {
            var paid = openRouter();
            var first = probe(directory, paid, node(directory, paid));

            // The same node, re-fetched against a different account: no limit, no balance, nothing
            // spent. Every row the first fetch filled has to change or clear.
            var free = new StubGateway()
                    .answering("/key", "key-free-tier.json")
                    .answering("/models", "models.json")
                    .failing("/credits", 403, "User not found.");
            var second = probe(directory, free, node(directory, free));

            var declared = displayKeys(node(directory, free).descriptor());
            assertThat(first.values().keySet()).isEqualTo(declared);
            assertThat(second.values().keySet()).isEqualTo(declared);
            assertThat(second.values())
                    .containsEntry("credits", "no limit")
                    .containsEntry("keyLabel", "spare key")
                    .containsEntry("freeTier", true)
                    .containsEntry("usageTotal", "$0.00")
                    .containsEntry("balance", "not readable: this key is not a management key")
                    // A date, not the instant the gateway published: the editor draws an instant in
                    // a line row as relative time, and every future one of those reads "just now".
                    .containsEntry("expires", "2026-12-31");
        }

        @Test
        @DisplayName("a gateway with no balance endpoint publishes none, up or down")
        void anUnreachableGatewayWithNoBalanceStillSaysItHasNone(@TempDir Path directory)
                throws Exception {
            var gateways = new StubGateway().failing("/models", 500, "Internal Server Error");
            var profiles = new EndpointProfiles(gateways, Fixtures.credentials());
            var saved = Fixtures.saveProfile(directory, "Lab box", Map.of(
                    "gateway", "llamacpp", "baseUrl", "http://box.test:8080/v1"));
            var node = new LlmEndpointInfoNode(
                    gateways, profiles, Fixtures.endpointNode(directory, profiles));

            var probe = node.probe("fetch", request("endpoint", source(saved.id())));

            assertThat(probe.ok()).isFalse();
            // The same row on the same server while it was up reads the same sentence. llama.cpp
            // has no credits endpoint, and a call nobody was going to make cannot have gone
            // unanswered — saying so sends the reader looking for an endpoint that never existed.
            assertThat(probe.values()).containsEntry("balance", "this gateway publishes none");
            assertThat(gateways.asked()).containsExactly("/models");
        }

        @Test
        @DisplayName("on a gateway whose probe path is the listing, one call answers both questions")
        void aListingProbeIsNotAskedTwice(@TempDir Path directory) throws Exception {
            var gateways = new StubGateway().answering("/models", "models.json");
            var profiles = new EndpointProfiles(gateways, Fixtures.credentials());
            var endpoints = Fixtures.endpointNode(directory, profiles);
            var saved = Fixtures.saveProfile(directory, "Lab box", Map.of(
                    "gateway", "llamacpp", "baseUrl", "http://box.test:8080/v1"));
            var node = new LlmEndpointInfoNode(gateways, profiles, endpoints);

            var probe = node.probe("fetch", request("endpoint", source(saved.id())));

            assertThat(gateways.asked()).containsExactly("/models");
            assertThat(probe.ok()).isTrue();
            assertThat(probe.values())
                    .containsEntry("modelsServed", "443")
                    // A llama.cpp server publishes no key facts and no balance, and says so by
                    // staying blank rather than by reporting zeros.
                    .containsEntry("keyLabel", "")
                    .containsEntry("credits", "")
                    .containsEntry("balance", "this gateway publishes none");
        }

        @Test
        @DisplayName("a credential the engine cannot see is refused before anything is contacted")
        void aMissingCredentialIsNamed(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var profiles = new EndpointProfiles(gateways, Fixtures.credentials());
            var endpoints = Fixtures.endpointNode(directory, profiles);
            var saved = Fixtures.saveProfile(directory, "Router", Map.of(
                    "gateway", "openrouter", "baseUrl", "https://openrouter.ai/api/v1"));
            var node = new LlmEndpointInfoNode(gateways, profiles, endpoints);

            var probe = node.probe("fetch", request("endpoint", source(saved.id())));

            assertThat(probe.ok()).isFalse();
            assertThat(probe.message()).contains("openrouter");
            assertThat(gateways.asked()).isEmpty();
        }

        @Test
        @DisplayName("a run produces every declared output, and streams the summary once")
        void runProducesEveryOutput(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var node = node(directory, gateways);
            var context = RecordingContext.with("endpoint", Fixtures.openrouter());

            node.execute(context);

            assertEveryOutputProduced(node, context);
            LlmEndpointInfo info = context.output("info");
            assertThat(info.reachable()).isTrue();
            assertThat(info.modelsServed()).isEqualTo(443);
            assertThat(info.creditsRemaining()).isEqualTo(37.66);
            assertThat(info.inputModalitiesCsv()).isEqualTo("file, image, text, video");
            assertThat(context.streamed("summary")).isEqualTo(info.toString());
            assertThat(context.rawOutput("summary")).isEqualTo(info.toString());
            assertThat(context.<List<String>>output("models")).hasSize(5);
            assertThat(context.logs()).hasSizeLessThanOrEqualTo(6);
        }

        @Test
        @DisplayName("the struct's toString is the report, which is what Preview shows")
        void summaryReadsAsAReport(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var context = RecordingContext.with("endpoint", Fixtures.openrouter());
            node(directory, gateways).execute(context);

            assertThat(context.rawOutput("summary").toString())
                    .startsWith("OpenRouter — https://openrouter.ai/api/v1")
                    .contains("443 models served.")
                    .contains("Credits: $37.66 of $50.00 remaining.")
                    .contains("Free-model requests: 3 of 200 used today.")
                    // Every count on its own placeholder, and no placeholder left showing.
                    .contains("Of those models: 4 with vision")
                    .contains("2 taking files.")
                    .doesNotContain("%s");
        }

        @Test
        void anUndeclaredActionIsRefused(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            assertThat(node(directory, gateways).probe("sniff", request("endpoint", source("x"))).message())
                    .contains("sniff");
        }

        private static StubGateway openRouter() {
            return new StubGateway()
                    .answering("/key", "key.json")
                    .answering("/models", "models.json")
                    .answering("/credits", "credits.json");
        }

        /** The same account with a key nobody named, which the gateway labels after the key. */
        private static StubGateway unnamedKey() {
            return new StubGateway()
                    .answering("/key", "key-unnamed.json")
                    .answering("/models", "models.json")
                    .failing("/credits", 403, "User not found.");
        }

        private static LlmEndpointInfoNode node(Path directory, StubGateway gateways) {
            var profiles = new EndpointProfiles(gateways, Fixtures.credentials("openrouter"));
            return new LlmEndpointInfoNode(gateways, profiles, Fixtures.endpointNode(directory, profiles));
        }

        private static NodeProbe.Result probe(
                Path directory, StubGateway gateways, LlmEndpointInfoNode node) throws Exception {
            var saved = Fixtures.saveProfile(directory, "Router", Map.of(
                    "gateway", "openrouter", "baseUrl", "https://openrouter.ai/api/v1"));
            return node.probe("fetch", request("endpoint", source(saved.id())));
        }
    }

    @Nested
    class ModelInfo {

        @Test
        @DisplayName("fills every headline row, and reads the per-host document for the provider count")
        void headlineRows(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var probe = probe(directory, gateways, MODEL);

            assertThat(probe.ok()).isTrue();
            assertThat(probe.values())
                    .containsEntry("id", MODEL)
                    .containsEntry("name", "Qwen: Qwen3.5-35B-A3B")
                    .containsEntry("contextWindow", "262,144")
                    .containsEntry("maxOutput", "65,536")
                    .containsEntry("price", "$0.1625 in / $1.30 out per M")
                    .containsEntry("providerCount", "7");
            assertThat(probe.values().get("capabilities"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                    .contains("tools", "vision", "reasoning");
        }

        @Test
        @DisplayName("the id reaches the gateway whole, canonical slug and all")
        void theEndpointDocumentIsAskedForByCanonicalSlug(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            probe(directory, gateways, MODEL);

            assertThat(gateways.asked()).containsExactly(
                    "/models", "/models/qwen/qwen3.5-35b-a3b-20260224/endpoints");
        }

        @Test
        @DisplayName("the grouped detail is everything the entry said and nothing it did not")
        void advancedRows(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var probe = probe(directory, gateways, MODEL);

            assertThat(probe.values())
                    .containsEntry("canonicalSlug", "qwen/qwen3.5-35b-a3b-20260224")
                    .containsEntry("huggingFaceId", "Qwen/Qwen3.5-35B-A3B")
                    .containsEntry("released", "2026-02-25")
                    .containsEntry("tokenizer", "Qwen3")
                    .containsEntry("moderated", false)
                    .containsEntry("reasoningMandatory", false)
                    .containsEntry("defaultParameters", "temperature 1 · top_p 0.95 · top_k 20")
                    // The entry carries no cutoff, no alias and no cache price, so those stay blank.
                    .containsEntry("knowledgeCutoff", "")
                    .containsEntry("aliasOf", "")
                    .containsEntry("cacheRead", "")
                    .containsEntry("pricingNote", "");
            assertThat(String.valueOf(probe.values().get("providers")))
                    .startsWith("Darkbloom · fp4 · 262,144 ctx");
        }

        @Test
        @DisplayName("a model the endpoint does not serve is a problem with the near misses")
        void anUnservedModelIsReportedWithNearMatches(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var probe = probe(directory, gateways, "qwen/qwen3.5-35b");

            assertThat(probe.ok()).isFalse();
            assertThat(probe.message())
                    .contains("does not serve 'qwen/qwen3.5-35b'")
                    .contains("Did you mean: qwen/qwen3.5-35b-a3b");
            // Nothing is asked about a model that is not there.
            assertThat(gateways.asked()).containsExactly("/models");
        }

        @Test
        @DisplayName("every row is written on every fetch, so switching models cannot leave a stale price")
        void everyRowIsWrittenOnEveryFetch(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var declared = displayKeys(node(directory, gateways).descriptor());

            var first = probe(directory, gateways, MODEL);
            var second = probe(directory, openRouter(), "~deepseek/deepseek-pro-latest");
            var missing = probe(directory, openRouter(), "vendor/not-there");

            assertThat(first.values().keySet()).isEqualTo(declared);
            assertThat(second.values().keySet()).isEqualTo(declared);
            // Even a fetch that found nothing writes every row — that is what clears the last one.
            assertThat(missing.values().keySet()).isEqualTo(declared);
            assertThat(missing.values()).containsEntry("price", "").containsEntry("providers", "");
            assertThat(second.values())
                    .containsEntry("pricingNote", "varies by time of day: $0.66–$1.32 /M in")
                    .containsEntry("aliasOf",
                            "DeepSeek: DeepSeek V4 Pro 0813 — deepseek/deepseek-v4-pro-0813");
        }

        @Test
        @DisplayName("a free price is a fact a zero cannot carry, so it reaches the struct and summary")
        void aFreeModelSaysFree(@TempDir Path directory) throws Exception {
            // The gateway published "0" for both halves. A Number field holds 0.0 for that and 0.0
            // for a price nobody quoted, so the formatted pair is what keeps the two apart in a
            // saved struct — without it a free model's summary has no price line at all.
            var gateways = openRouter();
            var probe = probe(directory, gateways, FREE_MODEL);
            var context = RecordingContext.with(
                    "model", Fixtures.named(Fixtures.model(Fixtures.openrouter()), FREE_MODEL));
            node(directory, gateways).execute(context);
            LlmModelInfo info = context.output("info");

            assertThat(probe.values()).containsEntry("price", "free in / free out per M");
            assertThat(info.inputPer1M()).isZero();
            assertThat(info.pricePerM()).isEqualTo("free in / free out per M");
            assertThat(info.summaryLine()).isEqualTo(FREE_MODEL + " · free in / free out per M");
            assertThat(info.toString()).contains("Priced at free in / free out per M.");
            assertThat(context.rawOutput("summary").toString()).contains("free in / free out per M");
        }

        @Test
        @DisplayName("an alias serves nothing itself, and the provider block says which")
        void anAliasSaysWhyItHasNoHosts(@TempDir Path directory) throws Exception {
            var gateways = new StubGateway()
                    .answering("/models", "models.json")
                    .answering("/models/~deepseek/deepseek-pro-latest/endpoints", "model-endpoints-alias.json");
            var probe = probe(directory, gateways, "~deepseek/deepseek-pro-latest");

            assertThat(probe.values())
                    .containsEntry("providers", "the gateway publishes no endpoints for this alias")
                    .containsEntry("providerCount", "");
        }

        @Test
        @DisplayName("a gateway with no per-host document still reports the model")
        void theHostDocumentIsOptional(@TempDir Path directory) throws Exception {
            var gateways = new StubGateway().answering("/models", "models.json");
            var profiles = new EndpointProfiles(gateways, Fixtures.credentials());
            var saved = Fixtures.saveProfile(directory, "Lab box", Map.of(
                    "gateway", "llamacpp", "baseUrl", "http://box.test:8080/v1"));
            var node = new LlmModelInfoNode(gateways, profiles, Fixtures.endpointNode(directory, profiles));

            var probe = node.probe("fetch", request("model", new NodeProbe.Source(
                    "llm.model", Map.of("model", MODEL), Map.of("endpoint", source(saved.id())))));

            assertThat(probe.ok()).isTrue();
            assertThat(gateways.asked()).containsExactly("/models");
            assertThat(probe.values())
                    .containsEntry("providers", "this gateway publishes none")
                    .containsEntry("price", "$0.1625 in / $1.30 out per M");
        }

        @Test
        @DisplayName("nothing wired in is an answer about the wiring, not a call")
        void anUnwiredModelIsRefused(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var node = node(directory, gateways);

            assertThat(node.probe("fetch", new NodeProbe.Request(
                            "llm.model_info", Map.of(), Map.of())).message())
                    .contains("Wire an LLM Model");
            assertThat(node.probe("fetch", request("model", new NodeProbe.Source(
                                    "llm.model", Map.of("model", " "), Map.of()))).message())
                    .contains("no model name");
            assertThat(gateways.asked()).isEmpty();
        }

        @Test
        @DisplayName("a run produces every declared output, and streams the summary once")
        void runProducesEveryOutput(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var node = node(directory, gateways);
            var context = RecordingContext.with(
                    "model", Fixtures.named(Fixtures.model(Fixtures.openrouter()), MODEL));

            node.execute(context);

            assertEveryOutputProduced(node, context);
            LlmModelInfo info = context.output("info");
            assertThat(info.id()).isEqualTo(MODEL);
            assertThat(info.contextWindow()).isEqualTo(262_144);
            assertThat(info.inputPer1M()).isEqualTo(0.1625);
            assertThat(info.providerCount()).isEqualTo(7);
            assertThat(info.parametersCsv()).contains("structured_outputs");
            assertThat(context.streamed("summary")).isEqualTo(info.toString());
            assertThat(context.<List<String>>output("providers")).hasSize(7);
            assertThat(context.logs()).hasSizeLessThanOrEqualTo(6);
        }

        @Test
        @DisplayName("a run against a model the endpoint does not serve fails with the near misses")
        void runFailsWhenTheModelIsNotServed(@TempDir Path directory) throws Exception {
            var gateways = openRouter();
            var node = node(directory, gateways);
            var context = RecordingContext.with(
                    "model", Fixtures.named(Fixtures.model(Fixtures.openrouter()), "qwen/qwen3.5-35b"));

            assertThatThrownBy(() -> node.execute(context)).hasMessageContaining("does not serve");
        }

        private static StubGateway openRouter() {
            return new StubGateway()
                    .answering("/models", "models.json")
                    .answering("/models/qwen/qwen3.5-35b-a3b-20260224/endpoints", "model-endpoints.json")
                    .answering("/models/~deepseek/deepseek-pro-latest/endpoints", "model-endpoints-alias.json");
        }

        private static LlmModelInfoNode node(Path directory, StubGateway gateways) {
            var profiles = new EndpointProfiles(gateways, Fixtures.credentials("openrouter"));
            return new LlmModelInfoNode(gateways, profiles, Fixtures.endpointNode(directory, profiles));
        }

        private static NodeProbe.Result probe(Path directory, StubGateway gateways, String model)
                throws Exception {
            var saved = Fixtures.saveProfile(directory, "Router", Map.of(
                    "gateway", "openrouter", "baseUrl", "https://openrouter.ai/api/v1"));
            return node(directory, gateways).probe("fetch", request("model", new NodeProbe.Source(
                    "llm.model", Map.of("model", model), Map.of("endpoint", source(saved.id())))));
        }
    }

    // --- shared helpers -----------------------------------------------------

    private static NodeProbe.Request request(String socket, NodeProbe.Source wired) {
        return new NodeProbe.Request("llm.info", Map.of(), Map.of(socket, wired));
    }

    private static NodeProbe.Source source(String profileId) {
        return new NodeProbe.Source("llm.endpoint", Map.of("profile", profileId), Map.of());
    }

    /** Every display row a node declares — the set a fetch has to write in full. */
    private static Set<String> displayKeys(NodeDescriptor descriptor) {
        return descriptor.inputs().stream()
                .filter(input -> input.widget() instanceof Widget.Display)
                .map(com.unbi.engine.core.node.NodeInput::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** The engine's own rule, applied here: a node that returns owes every output it declared. */
    private static void assertEveryOutputProduced(NodeDefinition node, RecordingContext context) {
        var declared = node.descriptor().outputs().stream().map(NodeOutput::key).toList();
        assertThat(context.outputs().keySet()).containsExactlyInAnyOrderElementsOf(declared);
        assertThat(context.outputs().values()).doesNotContainNull();
    }
}
