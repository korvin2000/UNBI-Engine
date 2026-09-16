package com.unbi.engine.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.unbi.engine.llm.spec.Capability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What a gateway said about a key and a catalogue, and what that renders as.
 *
 * <p>The listing fixture is a real {@code GET /api/v1/models} from OpenRouter, trimmed to five
 * entries with the {@code {data, total_count, links}} envelope left intact — including the
 * {@code total_count: 443} that no longer matches the array, which is the whole point: a paged
 * listing's array is one page, and reporting a page size as a catalogue size is the bug this
 * fixture exists to catch.
 *
 * <p>The key and credits fixtures are written to the field list measured against the live endpoint
 * (label, limit, the four usage windows, byok usage, the free-tier flags, the rate-limit block and
 * {@code free_model_daily_requests}). They are the one pair here not captured in this session: a
 * live {@code /key} needs the account's own credential, which is not readable from where these tests
 * run.
 *
 * <p>The assertions that matter most are the blank ones. A row that says {@code $0.00} for a figure
 * the gateway never published is exactly the invented fact this layer refuses to produce.
 */
class EndpointFactsTest {

    @Test
    @DisplayName("reads the key's own figures, and formats them the way they are read out loud")
    void keyFigures() {
        var facts = EndpointFacts.read(StubGateway.read("key.json"), null, "", null);

        assertThat(facts.keyLabel()).isEqualTo("unbi-engine dev");
        assertThat(facts.creditsRemaining()).isEqualTo("$37.66 of $50.00");
        assertThat(facts.freeRequests()).isEqualTo("3 of 200 used");
        assertThat(facts.usageDaily()).isEqualTo(0.42);
        assertThat(facts.freeTier()).isFalse();
        assertThat(facts.managementKey()).isFalse();
    }

    @Test
    @DisplayName("a key with no cap says 'no limit' rather than showing a zero")
    void anAbsentLimitIsNotZero() {
        var facts = EndpointFacts.read(StubGateway.read("key-free-tier.json"), null, "", null);

        assertThat(facts.limit()).isNull();
        assertThat(facts.creditsRemaining()).isEqualTo("no limit");
        assertThat(facts.freeTier()).isTrue();
        assertThat(facts.expiresAt()).isEqualTo("2026-12-31T00:00:00Z");
    }

    @Test
    @DisplayName("an expiry is a date, because a future instant would be drawn as 'just now'")
    void anExpiryIsADate() {
        var expiring = EndpointFacts.read(StubGateway.read("key-free-tier.json"), null, "", null);
        assertThat(expiring.expires()).isEqualTo("2026-12-31");

        // No expiry at all stays blank, and text no clock can read is shown as it arrived rather
        // than thrown away — an unreadable expiry is still more than an empty row says.
        assertThat(EndpointFacts.read(StubGateway.read("key.json"), null, "", null).expires()).isEmpty();
        assertThat(keyLabelled("x").expires()).isEmpty();
        assertThat(expiring("never").expires()).isEqualTo("never");
        assertThat(expiring("2026-12-31").expires()).isEqualTo("2026-12-31");
    }

    @Test
    @DisplayName("a key the gateway named after itself is reported as unnamed, not as a key fragment")
    void aKeyShapedLabelIsNotPublished() {
        // OpenRouter labels an unnamed key with a truncated form of the key. That is the gateway's
        // own redaction and still key-shaped text, and a display value is saved into the workflow.
        var unnamed = EndpointFacts.read(StubGateway.read("key-unnamed.json"), null, "", null);

        assertThat(unnamed.keyLabel()).isEqualTo("unnamed key");
        assertThat(keyLabelled("pk_live_9f3...a21").keyLabel()).isEqualTo("unnamed key");
        assertThat(keyLabelled("sk-or-v1-0ca…618").keyLabel()).isEqualTo("unnamed key");
        // The row's whole job is to say which key answered, so a real name survives — including the
        // ones that begin with a word a prefix-only rule would have swallowed.
        assertThat(keyLabelled("api-team key").keyLabel()).isEqualTo("api-team key");
        assertThat(keyLabelled("sk_prod-shared").keyLabel()).isEqualTo("sk_prod-shared");
        assertThat(keyLabelled("unbi-engine dev").keyLabel()).isEqualTo("unbi-engine dev");
        assertThat(keyLabelled("").keyLabel()).isEmpty();
    }

    @Test
    @DisplayName("the balance shows its arithmetic, so it can be checked against an invoice")
    void balanceIsShownAsASum() {
        var facts = EndpointFacts.read(
                StubGateway.read("key.json"), StubGateway.read("credits.json"), "", null);

        assertThat(facts.balance()).isEqualTo("$37.66 = $50.00 credits − $12.34 used");
        assertThat(facts.balanceProblem()).isEmpty();
    }

    @Test
    @DisplayName("an unreadable balance is a sentence, not a zero and not a failure")
    void anUnreadableBalanceKeepsItsReason() {
        var facts = EndpointFacts.read(
                StubGateway.read("key.json"), null, "not readable with this key", null);

        assertThat(facts.balance()).isEmpty();
        assertThat(facts.balanceProblem()).isEqualTo("not readable with this key");
    }

    @Test
    @DisplayName("the model count comes from total_count, not from the page that arrived")
    void catalogueCountPrefersTotalCount() {
        var facts = EndpointFacts.read(null, null, "", StubGateway.read("models.json"));

        assertThat(facts.modelsServed()).isEqualTo(443);
        assertThat(facts.modelsServedText()).isEqualTo("443");
        assertThat(facts.modelIds()).hasSize(5);
    }

    @Test
    @DisplayName("capability counts are this engine's vocabulary applied to every entry")
    void catalogueCapabilities() {
        var facts = EndpointFacts.read(null, null, "", StubGateway.read("models.json"));

        // All five trimmed entries list tools and reasoning; four take images, two take files, and
        // one of the five is text-only — counted from what each entry listed, never from its name.
        assertThat(facts.capabilityCount(Capability.TOOLS)).isEqualTo("5");
        assertThat(facts.capabilityCount(Capability.REASONING)).isEqualTo("5");
        assertThat(facts.capabilityCount(Capability.VISION)).isEqualTo("4");
        assertThat(facts.capabilityCount(Capability.FILES)).isEqualTo("2");
        assertThat(facts.capabilityCount(Capability.JSON_SCHEMA)).isEqualTo("4");
        assertThat(facts.inputModalities()).containsExactly("file", "image", "text", "video");
    }

    @Test
    @DisplayName("with no listing read, every catalogue row is blank rather than zero")
    void nothingAskedIsNotZero() {
        var facts = EndpointFacts.read(StubGateway.read("key.json"), null, "", null);

        assertThat(facts.modelsServed()).isNull();
        assertThat(facts.modelsServedText()).isEmpty();
        assertThat(facts.capabilityCount(Capability.VISION)).isEmpty();
        assertThat(facts.inputModalities()).isEmpty();
    }

    @Test
    @DisplayName("a model listing where a key introspection was expected leaves the key rows blank")
    void aListingIsNotAKeyIntrospection() {
        // What a gateway whose probe path is /models answers: reachable and a catalogue, and not one
        // word about a key. Nothing is invented to fill the gap.
        var listing = StubGateway.read("models.json");
        var facts = EndpointFacts.read(listing, null, "this gateway publishes none", listing);

        assertThat(facts.keyLabel()).isEmpty();
        assertThat(facts.creditsRemaining()).isEmpty();
        assertThat(facts.freeRequests()).isEmpty();
        assertThat(facts.balance()).isEmpty();
        assertThat(facts.modelsServed()).isEqualTo(443);
    }

    private static EndpointFacts keyLabelled(String label) {
        return EndpointFacts.read(keyBody("label", label), null, "", null);
    }

    private static EndpointFacts expiring(String expiresAt) {
        return EndpointFacts.read(keyBody("expires_at", expiresAt), null, "", null);
    }

    /** A {@code /key} body carrying one field, for the shapes no captured body happens to have. */
    private static JsonNode keyBody(String field, String value) {
        var body = new ObjectMapper().createObjectNode();
        body.putObject("data").put(field, value);
        return body;
    }
}
