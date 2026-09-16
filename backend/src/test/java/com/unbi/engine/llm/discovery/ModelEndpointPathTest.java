package com.unbi.engine.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The one rule this path has: the model id goes in whole.
 *
 * <p>Every case below was verified against the live gateway before being written down.
 * {@code /models/<full id>/endpoints} answers 200 for an id containing a slash, for one suffixed
 * {@code :free}, for one suffixed {@code :batch} and for one prefixed {@code ~} — so none of those
 * characters may be escaped, and none of them may be treated as a separator to split on.
 */
class ModelEndpointPathTest {

    private static final String TEMPLATE = "/models/{slug}/endpoints";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("slashes, colons and tildes reach the gateway exactly as it wrote them")
    void idsAreNotSplitOrEscaped() {
        assertThat(ModelEndpointPath.of(TEMPLATE, "qwen/qwen3.5-35b-a3b"))
                .isEqualTo("/models/qwen/qwen3.5-35b-a3b/endpoints");
        assertThat(ModelEndpointPath.of(TEMPLATE, "deepseek/deepseek-r1:free"))
                .isEqualTo("/models/deepseek/deepseek-r1:free/endpoints");
        assertThat(ModelEndpointPath.of(TEMPLATE, "openai/gpt-6-astra:batch"))
                .isEqualTo("/models/openai/gpt-6-astra:batch/endpoints");
        assertThat(ModelEndpointPath.of(TEMPLATE, "~anthropic/claude-opus-latest"))
                .isEqualTo("/models/~anthropic/claude-opus-latest/endpoints");
    }

    @Test
    @DisplayName("only what a URI path cannot carry is escaped")
    void illegalCharactersAreEscapedAndNothingElse() {
        assertThat(ModelEndpointPath.of(TEMPLATE, "vendor/a model"))
                .isEqualTo("/models/vendor/a%20model/endpoints");
        assertThat(ModelEndpointPath.of(TEMPLATE, "vendor/a#b?c%d"))
                .isEqualTo("/models/vendor/a%23b%3Fc%25d/endpoints");
    }

    @Test
    @DisplayName("a gateway with no such path produces no path, rather than a guessed one")
    void noTemplateMeansNoCall() {
        assertThat(ModelEndpointPath.of("", "vendor/model")).isEmpty();
        assertThat(ModelEndpointPath.of(null, "vendor/model")).isEmpty();
        assertThat(ModelEndpointPath.of(TEMPLATE, " ")).isEmpty();
    }

    @Test
    @DisplayName("the gateway's own canonical slug wins, because its own links use it")
    void canonicalSlugIsPreferred() {
        var entry = MAPPER.readTree(
                "{\"id\":\"qwen/qwen3.5-35b-a3b\",\"canonical_slug\":\"qwen/qwen3.5-35b-a3b-20260224\"}");

        assertThat(ModelEndpointPath.slugOf(entry, "qwen/qwen3.5-35b-a3b"))
                .isEqualTo("qwen/qwen3.5-35b-a3b-20260224");
        assertThat(ModelEndpointPath.slugOf(MAPPER.readTree("{\"id\":\"local-model\"}"), "local-model"))
                .isEqualTo("local-model");
        assertThat(ModelEndpointPath.slugOf(null, "local-model")).isEqualTo("local-model");
    }
}
