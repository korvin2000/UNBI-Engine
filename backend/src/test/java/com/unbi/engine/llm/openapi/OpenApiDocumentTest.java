package com.unbi.engine.llm.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OpenApiDocumentTest {
    @Test
    void parsesJsonYamlAndSupportedVersions() {
        var json = OpenApiDocument.parse("{\"openapi\":\"3.0.3\",\"info\":{}}", null);
        assertThat(json.minor()).isZero();
        assertThat(json.root().path("openapi").asText()).isEqualTo("3.0.3");

        var yaml = OpenApiDocument.parse("openapi: 3.2.1\ninfo: {}\n", "https://example.test/spec.yaml");
        assertThat(yaml.minor()).isEqualTo(2);
        assertThat(yaml.documentUri()).isEqualTo(URI.create("https://example.test/spec.yaml"));
    }

    @Test
    void rejectsSwaggerFutureAndMalformedVersion() {
        for (var version : new String[] {"2.0", "3.3.0", "3.1", "three"}) {
            assertThatThrownBy(() -> OpenApiDocument.parse("{\"openapi\":\"" + version + "\"}", null))
                    .isInstanceOf(OpenApiDocument.Problem.class)
                    .hasMessageContaining("OpenAPI 3");
        }
        assertThatThrownBy(() -> OpenApiDocument.parse("{\"info\":{}}", null))
                .isInstanceOf(OpenApiDocument.Problem.class)
                .satisfies(error -> assertThat(((OpenApiDocument.Problem) error).pointer()).isEqualTo("/openapi"));
    }

    @Test
    void rejectsDuplicateKeysAndMultipleDocuments() {
        assertThatThrownBy(() -> OpenApiDocument.parse("{\"openapi\":\"3.0.0\",\"openapi\":\"3.0.1\"}", null))
                .isInstanceOf(OpenApiDocument.Problem.class);
        assertThatThrownBy(() -> OpenApiDocument.parse("openapi: 3.0.0\ninfo: {}\nopenapi: 3.0.1\n", null))
                .isInstanceOf(OpenApiDocument.Problem.class);
        assertThatThrownBy(() -> OpenApiDocument.parse("---\nopenapi: 3.0.0\n---\nopenapi: 3.0.1\n", null))
                .isInstanceOf(OpenApiDocument.Problem.class);
    }

    @Test
    void enforcesSizeAndNestingBoundsWithoutEchoingSource() {
        var huge = "{\"openapi\":\"3.0.0\",\"description\":\"" + "x".repeat(10 * 1024 * 1024) + "\"}";
        assertThatThrownBy(() -> OpenApiDocument.parse(huge, null))
                .isInstanceOf(OpenApiDocument.Problem.class)
                .hasMessageNotContaining("xxx");

        var nested = IntStream.range(0, 105).mapToObj(ignored -> "{\"x\":")
                .collect(Collectors.joining()) + "\"ok\"" + "}".repeat(105);
        assertThatThrownBy(() -> OpenApiDocument.parse(nested, null))
                .isInstanceOf(OpenApiDocument.Problem.class);
    }

    @Test
    void rejectsYmlAliasExpansionAndNonObjectDocuments() {
        var aliases = "openapi: 3.0.0\nbase: &base [a,b]\na: *base\nb: *base\nc: *base\n";
        assertThat(OpenApiDocument.parse(aliases, null).root().path("a").isArray()).isTrue();
        var tooMany = new StringBuilder("openapi: 3.0.0\nbase: &base [a,b]\n");
        for (var index = 0; index < 55; index++) tooMany.append("a").append(index).append(": *base\n");
        assertThatThrownBy(() -> OpenApiDocument.parse(tooMany.toString(), null))
                .isInstanceOf(OpenApiDocument.Problem.class);
        assertThatThrownBy(() -> OpenApiDocument.parse("[]", null))
                .isInstanceOf(OpenApiDocument.Problem.class);
    }

    @Test
    void resolvesLocalPointersWithActualProvenanceAndEscaping() {
        var doc = OpenApiDocument.parse("""
                {"openapi":"3.1.0","components":{"path items":{"x~y":{"get":{}}}},"paths":{"/x":{"$ref":"#/components/path%20items/x~0y"}}}
                """, null);
        var resolved = doc.resolve(doc.root().path("paths").path("/x"), "/paths/~1x");
        assertThat(resolved.pointer()).isEqualTo("/components/path items/x~0y");
        assertThat(resolved.node().path("get").isObject()).isTrue();
        assertThat(OpenApiDocument.child("/components", "a/b~c"))
                .isEqualTo("/components/a~1b~0c");
    }

    @Test
    void rejectsMissingExternalSiblingAndCyclicReferences() {
        var missing = OpenApiDocument.parse("{\"openapi\":\"3.0.0\",\"paths\":{\"/x\":{\"$ref\":\"#/missing\"}}}", null);
        assertThatThrownBy(() -> missing.resolve(missing.root().path("paths").path("/x"), "/paths/~1x"))
                .isInstanceOf(OpenApiDocument.Problem.class);
        var external = OpenApiDocument.parse("{\"openapi\":\"3.0.0\",\"paths\":{\"/x\":{\"$ref\":\"other.yaml#/x\"}}}", null);
        assertThatThrownBy(() -> external.resolve(external.root().path("paths").path("/x"), "/paths/~1x"))
                .isInstanceOfSatisfying(OpenApiDocument.Problem.class,
                        error -> assertThat(error.getMessage()).isEqualTo("Bundle external references before import"));
        var sibling = OpenApiDocument.parse("{\"openapi\":\"3.0.0\",\"paths\":{\"/x\":{\"$ref\":\"#/x\",\"summary\":\"no merge\"}}}", null);
        assertThatThrownBy(() -> sibling.resolve(sibling.root().path("paths").path("/x"), "/paths/~1x"))
                .isInstanceOf(OpenApiDocument.Problem.class);
        var cycle = OpenApiDocument.parse("{\"openapi\":\"3.0.0\",\"a\":{\"$ref\":\"#/b\"},\"b\":{\"$ref\":\"#/a\"}}", null);
        assertThatThrownBy(() -> cycle.resolve(cycle.root().path("a"), "/a"))
                .isInstanceOfSatisfying(OpenApiDocument.Problem.class,
                        error -> assertThat(error.getMessage()).contains("cycle"));
    }

    @Test
    void resolvesRelativeUrlsOnlyWithAbsoluteHttpDocumentBase() {
        var document = OpenApiDocument.parse("{\"openapi\":\"3.0.0\"}", "https://example.test/spec/openapi.yaml");
        assertThat(document.resolveUrl("../v1", "/servers/0/url")).isEqualTo(URI.create("https://example.test/v1"));
        assertThat(document.resolveUrl("https://gateway.test/v1", "/servers/0/url")).isEqualTo(URI.create("https://gateway.test/v1"));
        var noBase = OpenApiDocument.parse("{\"openapi\":\"3.0.0\"}", null);
        assertThatThrownBy(() -> noBase.resolveUrl("v1", "/servers/0/url"))
                .isInstanceOf(OpenApiDocument.Problem.class);
        assertThatThrownBy(() -> OpenApiDocument.parse("{\"openapi\":\"3.0.0\"}", "file:///tmp/spec.yaml"))
                .isInstanceOf(OpenApiDocument.Problem.class);
    }

    @Test
    void malformedInputErrorsDoNotExposeSource() {
        var secret = "not-json-with-secret-value";
        assertThatThrownBy(() -> OpenApiDocument.parse(secret, null))
                .isInstanceOf(OpenApiDocument.Problem.class)
                .hasMessageNotContaining(secret);
    }
}
