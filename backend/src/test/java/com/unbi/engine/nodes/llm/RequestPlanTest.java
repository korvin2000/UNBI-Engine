package com.unbi.engine.nodes.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.nodes.llm.RequestPlan.Combine;
import com.unbi.engine.nodes.llm.RequestPlan.Inputs;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shapes a batch can take, as a table.
 *
 * <p>Every use case the request node promises — several system prompts against one document, one
 * file per request, all files in one request, X prompts by Y prompts — is a row here rather than a
 * run against a gateway. The plan is pure, so these are the cheapest tests in the pack and the ones
 * that pin its behaviour down.
 */
class RequestPlanTest {

    private static Inputs inputs(List<String> systems, List<String> users, List<Object> items) {
        return new Inputs(systems, users, items, List.of(), false, Combine.PAIR, Map.of(), true);
    }

    private static Attachment image(String name) {
        return Attachment.image(name, "image/png", new byte[] {1});
    }

    @Test
    void oneOfEverythingIsOneRequest() {
        var plan = RequestPlan.plan(inputs(List.of("sys"), List.of("hi"), null));
        assertThat(plan).hasSize(1);
        assertThat(plan.getFirst().system()).isEqualTo("sys");
        assertThat(plan.getFirst().user()).isEqualTo("hi");
        assertThat(plan.getFirst().item()).isNull();
        assertThat(plan.getFirst().bindings()).containsEntry("index", 1).containsEntry("count", 1);
    }

    @Test
    @DisplayName("a) several system prompts, same data and user prompt")
    void systemPromptsIterate() {
        var plan = RequestPlan.plan(inputs(List.of("S1", "S2", "S3"), List.of("Summarise {{item}}"), List.of("doc")));
        assertThat(plan).extracting(RequestPlan.Request::system).containsExactly("S1", "S2", "S3");
        assertThat(plan).extracting(RequestPlan.Request::user).containsOnly("Summarise doc");
    }

    @Test
    @DisplayName("b) several user prompts against one system prompt")
    void userPromptsIterate() {
        var plan = RequestPlan.plan(inputs(List.of("S"), List.of("Q1", "Q2"), null));
        assertThat(plan).extracting(RequestPlan.Request::user).containsExactly("Q1", "Q2");
        assertThat(plan).extracting(RequestPlan.Request::system).containsOnly("S");
    }

    @Test
    @DisplayName("c) one file per request, d) all files in one request")
    void attachmentsIterateOrTravelTogether() {
        var files = List.of(image("a.png"), image("b.png"));
        var each = RequestPlan.plan(new Inputs(
                List.of(), List.of("Describe {{file}}"), null, files, true, Combine.PAIR, Map.of(), true));
        assertThat(each).hasSize(2);
        assertThat(each).extracting(RequestPlan.Request::user).containsExactly("Describe a.png", "Describe b.png");
        assertThat(each.getFirst().attachments()).containsExactly(files.getFirst());

        var all = RequestPlan.plan(new Inputs(
                List.of(), List.of("Compare"), null, files, false, Combine.PAIR, Map.of(), true));
        assertThat(all).hasSize(1);
        assertThat(all.getFirst().attachments()).containsExactlyElementsOf(files);
    }

    @Test
    @DisplayName("f) X system prompts by Y user prompts is X times Y, system prompt outermost")
    void crossingIsTheProduct() {
        var plan = RequestPlan.plan(new Inputs(
                List.of("S1", "S2"), List.of("U1", "U2", "U3"), null, List.of(), false, Combine.CROSS, Map.of(), true));
        assertThat(plan).hasSize(6);
        assertThat(plan).extracting(RequestPlan.Request::system)
                .containsExactly("S1", "S1", "S1", "S2", "S2", "S2");
        assertThat(plan).extracting(RequestPlan.Request::user)
                .containsExactly("U1", "U2", "U3", "U1", "U2", "U3");
    }

    @Test
    void pairingMatchesByPositionAndBroadcastsSingles() {
        var plan = RequestPlan.plan(inputs(List.of("S"), List.of("A {{item}}", "B {{item}}"), List.of("x", "y")));
        assertThat(plan).extracting(RequestPlan.Request::user).containsExactly("A x", "B y");
    }

    @Test
    void pairingRefusesMismatchedLengthsWithTheLengths() {
        assertThatThrownBy(() -> RequestPlan.plan(inputs(List.of("S1", "S2"), List.of("U"), List.of("a", "b", "c"))))
                .hasMessageContaining("2 system prompts")
                .hasMessageContaining("3 data items")
                .hasMessageContaining("Every combination");
    }

    @Test
    void anEmptyDataListIsAnError() {
        assertThatThrownBy(() -> RequestPlan.plan(inputs(List.of(), List.of("U"), List.of())))
                .hasMessageContaining("empty");
    }

    @Test
    void aRequestWithNeitherPromptNorAttachmentIsRefused() {
        assertThatThrownBy(() -> RequestPlan.plan(inputs(List.of("S"), List.of("  "), null)))
                .hasMessageContaining("user prompt");
    }

    @Test
    @DisplayName("named variables are readable, and the request's own facts win over them")
    void bindingsLayerTheRequestOverSharedVariables() {
        var plan = RequestPlan.plan(new Inputs(
                List.of(), List.of("{{tone}} {{index}}/{{count}} {{item}}"), List.of("a", "b"),
                List.of(), false, Combine.PAIR, Map.of("tone", "kind", "index", "ignored"), true));
        assertThat(plan).extracting(RequestPlan.Request::user).containsExactly("kind 1/2 a", "kind 2/2 b");
    }

    @Test
    void aRecordItemBindsItsColumnsWithoutShadowingNamedVariables() {
        var rows = List.<Object>of(Map.of("title", "T", "tone", "loud"));
        var plan = RequestPlan.plan(new Inputs(
                List.of(), List.of("{{title}} {{tone}} {{item.title}}"), rows,
                List.of(), false, Combine.PAIR, Map.of("tone", "kind"), true));
        assertThat(plan.getFirst().user()).isEqualTo("T kind T");
    }

    @Test
    void strictRenderingNamesTheRequestAndTheVariable() {
        assertThatThrownBy(() -> RequestPlan.plan(inputs(List.of(), List.of("{{missing}}"), List.of("a", "b"))))
                .hasMessageContaining("Request 1")
                .hasMessageContaining("missing");
    }

    @Test
    void lenientRenderingLeavesAHole() {
        var plan = RequestPlan.plan(new Inputs(
                List.of(), List.of("x {{missing}} y"), null, List.of(), false, Combine.PAIR, Map.of(), false));
        assertThat(plan.getFirst().user()).isEqualTo("x  y");
    }
}
