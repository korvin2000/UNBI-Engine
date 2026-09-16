package com.unbi.engine.llm.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unbi.engine.llm.Fixtures;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.Attachment;
import com.unbi.engine.llm.spec.Capability;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatMessage;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.ProviderRouting;
import com.unbi.engine.llm.spec.ResponseFormat;
import com.unbi.engine.llm.spec.SamplingParams;
import com.unbi.engine.llm.spec.WebSearchMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every incompatibility, and the sentence it produces.
 *
 * <p>The messages are asserted, not only the severities. This is the layer whose whole purpose is to
 * explain, and a finding nobody can act on is the same as no finding.
 */
class CapabilityCheckTest {

    private static ChatCall call(ModelSpec model, ResponseFormat format, ChatCall.WebSearch search) {
        return new ChatCall(
                model, List.of(ChatMessage.user("hi")), format, SamplingParams.UNSET, search, "", "");
    }

    private static List<String> messages(ChatCall call) {
        return CapabilityCheck.inspect(call).stream().map(CapabilityCheck.Finding::message).toList();
    }

    @Nested
    class ResponseFormats {

        @Test
        void jsonModeWithoutTheCapabilityIsAnError() {
            var findings = CapabilityCheck.inspect(
                    call(Fixtures.model(), ResponseFormat.JSON_OBJECT, ChatCall.WebSearch.OFF));

            assertThat(findings).singleElement()
                    .satisfies(finding -> {
                        assertThat(finding.isError()).isTrue();
                        assertThat(finding.message()).contains("json_object").contains("prose");
                    });
        }

        @Test
        void aDeclaredCapabilityPassesQuietly() {
            assertThat(CapabilityCheck.inspect(call(
                            Fixtures.with(Fixtures.model(), Capability.JSON_OBJECT),
                            ResponseFormat.JSON_OBJECT, ChatCall.WebSearch.OFF)))
                    .isEmpty();
        }

        @Test
        void plainTextNeedsNothing() {
            assertThat(CapabilityCheck.inspect(
                            call(Fixtures.model(), ResponseFormat.TEXT, ChatCall.WebSearch.OFF)))
                    .isEmpty();
        }
    }

    @Nested
    class Attachments {

        private ChatCall withAttachment(ModelSpec model, Attachment attachment) {
            return ChatCall.of(model, List.of(ChatMessage.user("look", List.of(attachment))));
        }

        @Test
        void anImageNeedsVision() {
            var findings = CapabilityCheck.inspect(withAttachment(
                    Fixtures.model(), Attachment.image("a.png", "image/png", new byte[] {1})));

            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.isError()).isTrue();
                assertThat(finding.message()).contains("a.png").contains("vision");
            });
        }

        @Test
        void aDocumentNeedsFileInput() {
            assertThat(messages(withAttachment(
                            Fixtures.model(), Attachment.document("a.pdf", "application/pdf", new byte[] {1}))))
                    .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("files");
        }

        @Test
        @DisplayName("text attachments need nothing: they are just text in the prompt")
        void textAttachmentsAreAlwaysAllowed() {
            assertThat(CapabilityCheck.inspect(withAttachment(
                            Fixtures.model(), Attachment.text("a.md", "text/markdown", "body"))))
                    .isEmpty();
        }

        @Test
        void aDeclaredCapabilityAllowsIt() {
            assertThat(CapabilityCheck.inspect(withAttachment(
                            Fixtures.with(Fixtures.model(), Capability.VISION),
                            Attachment.image("a.png", "image/png", new byte[] {1}))))
                    .isEmpty();
        }
    }

    @Nested
    class Search {

        private final ChatCall.WebSearch on = new ChatCall.WebSearch(true, false, "");

        @Test
        @DisplayName("a model without search answers from memory, which is the failure worth naming")
        void searchNeedsTheCapability() {
            assertThat(messages(call(Fixtures.model(), ResponseFormat.TEXT, on)))
                    .anySatisfy(message -> assertThat(message).contains("from memory"));
        }

        @Test
        void searchNeedsAMode() {
            var declared = Fixtures.with(Fixtures.model(), Capability.WEB_SEARCH);
            assertThat(messages(call(declared, ResponseFormat.TEXT, on)))
                    .anySatisfy(message -> assertThat(message).contains("No search mode"));
        }

        @Test
        void theHostedToolRequiresTheResponsesApi() {
            var model = Fixtures.searching(
                    Fixtures.with(Fixtures.model(), Capability.WEB_SEARCH),
                    WebSearchMode.RESPONSES_TOOL, ApiFormat.CHAT_COMPLETIONS);
            assertThat(messages(call(model, ResponseFormat.TEXT, on)))
                    .anySatisfy(message -> assertThat(message).contains("Responses API"));
        }

        @Test
        @DisplayName("the online mode without the suffix is a warning, since the suffix is the mechanism")
        void onlineModeWantsTheSuffix() {
            var model = Fixtures.searching(
                    Fixtures.with(Fixtures.model(), Capability.WEB_SEARCH),
                    WebSearchMode.ONLINE, ApiFormat.CHAT_COMPLETIONS);
            assertThat(messages(call(model, ResponseFormat.TEXT, on)))
                    .anySatisfy(message -> assertThat(message).contains(":online"));
        }

        @Test
        @DisplayName("search plus JSON mode is refused: these gateways answer 400 to the combination")
        void searchAndJsonModeAreMutuallyExclusive() {
            var model = Fixtures.searching(
                    Fixtures.with(Fixtures.model(), Capability.WEB_SEARCH, Capability.JSON_OBJECT),
                    WebSearchMode.RESPONSES_TOOL, ApiFormat.RESPONSES);

            assertThat(CapabilityCheck.inspect(call(model, ResponseFormat.JSON_OBJECT, on)))
                    .anySatisfy(finding -> {
                        assertThat(finding.isError()).isTrue();
                        assertThat(finding.message()).contains("cannot be combined");
                    });
        }

        @Test
        void aProperlyConfiguredSearchTargetPassesQuietly() {
            var model = Fixtures.searching(
                    Fixtures.with(Fixtures.model(), Capability.WEB_SEARCH),
                    WebSearchMode.RESPONSES_TOOL, ApiFormat.RESPONSES);
            assertThat(CapabilityCheck.inspect(call(model, ResponseFormat.TEXT, on))).isEmpty();
        }
    }

    @Nested
    class Sampling {

        private ChatCall sampled(ModelSpec model, SamplingParams sampling) {
            return new ChatCall(model, List.of(ChatMessage.user("hi")), ResponseFormat.TEXT,
                    sampling, ChatCall.WebSearch.OFF, "", "");
        }

        @Test
        void responsesTargetsWarnAboutSamplersTheyWillDrop() {
            var model = Fixtures.searching(Fixtures.model(), WebSearchMode.NONE, ApiFormat.RESPONSES);
            var sampling = new SamplingParams(null, null, 40, null, null, null, null, List.of(), null);
            assertThat(messages(sampled(model, sampling)))
                    .anySatisfy(message -> assertThat(message).contains("top_k"));
        }

        @Test
        @DisplayName("routed samplers without require-parameters are the silently-dropped case")
        void routedSamplersWantParameterEnforcement() {
            var routed = Fixtures.routed(Fixtures.model(), new ProviderRouting(
                    List.of("host-a"), List.of(), List.of(), null, null, null));
            var sampling = new SamplingParams(null, null, 40, null, null, null, null, List.of(), null);

            assertThat(messages(sampled(routed, sampling)))
                    .anySatisfy(message -> assertThat(message).contains("nothing will say so"));
        }

        @Test
        void requireParametersSilencesThat() {
            var routed = Fixtures.routed(Fixtures.model(), new ProviderRouting(
                    List.of("host-a"), List.of(), List.of(), null, true, null));
            var sampling = new SamplingParams(null, null, 40, null, null, null, null, List.of(), null);
            assertThat(CapabilityCheck.inspect(sampled(routed, sampling))).isEmpty();
        }

        @Test
        void anOverLargeOutputLimitIsReportedAndThenCapped() {
            var sampling = new SamplingParams(null, null, null, null, null, null, null, List.of(), 99_999);
            var call = sampled(Fixtures.model(), sampling);

            assertThat(messages(call)).anySatisfy(message -> assertThat(message).contains("caps at 4096"));
            assertThat(call.effectiveMaxOutputTokens()).isEqualTo(4096);
        }
    }

    @Nested
    class Enforcement {

        private final ChatCall failing =
                call(Fixtures.model(), ResponseFormat.JSON_OBJECT, ChatCall.WebSearch.OFF);

        @Test
        void strictModeRefusesWithTheFindingAsItsMessage() {
            var reported = new ArrayList<String>();
            assertThatThrownBy(() ->
                            CapabilityCheck.enforce(CapabilityCheck.inspect(failing), true, reported::add))
                    .isInstanceOf(LlmFailure.class)
                    .hasMessageContaining("json_object");
        }

        @Test
        @DisplayName("lenient mode carries on, but reports every finding rather than going quiet")
        void lenientModeIsLoud() {
            var reported = new ArrayList<String>();
            CapabilityCheck.enforce(CapabilityCheck.inspect(failing), false, reported::add);

            assertThat(reported).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .contains("ERROR")
                    .contains("Response format");
        }

        @Test
        void warningsNeverFailEvenInStrictMode() {
            var sampling = new SamplingParams(null, null, null, null, null, null, null, List.of(), 99_999);
            var warned = new ChatCall(Fixtures.model(), List.of(ChatMessage.user("hi")),
                    ResponseFormat.TEXT, sampling, ChatCall.WebSearch.OFF, "", "");
            var reported = new ArrayList<String>();

            CapabilityCheck.enforce(CapabilityCheck.inspect(warned), true, reported::add);
            assertThat(reported).hasSize(1);
        }
    }
}
