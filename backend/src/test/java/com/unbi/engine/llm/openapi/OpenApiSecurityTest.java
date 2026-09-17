package com.unbi.engine.llm.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenApiSecurityTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void projectsAnonymousAlternativesWithoutInventingAuthentication() throws Exception {
        var doc = OpenApiDocument.parse("""
                {"openapi":"3.1.0","info":{"title":"x","version":"1"},"paths":{}}
                """, "https://example.test/spec.json");
        assertThat(OpenApiSecurity.alternatives(doc, null, "", "").get(0).path("auth").asString()).isEqualTo("none");
        assertThat(OpenApiSecurity.alternatives(doc, JSON.readTree("[]"), "/security", "").get(0).path("id").asString())
                .isEqualTo("/security");
        assertThat(OpenApiSecurity.alternatives(doc, JSON.readTree("[{}]"), "/security", "").get(0).path("available").asBoolean())
                .isTrue();
    }

    @Test
    void preservesApiKeyLocationAndDoesNotImportASecret() throws Exception {
        var doc = parse("""
                {
                  "openapi":"3.0.3","info":{"title":"x","version":"1"},"paths":{},
                  "components":{"securitySchemes":{
                    "header":{"type":"apiKey","in":"header","name":"X-Api-Key"},
                    "query":{"type":"apiKey","in":"query","name":"access_token"},
                    "cookie":{"type":"apiKey","in":"cookie","name":"sid"}
                  }}
                }
                """);
        var security = JSON.readTree("[{\"header\":[]},{\"query\":[]},{\"cookie\":[]}]");
        var choices = OpenApiSecurity.alternatives(doc, security, "/security", "https://example.test/v1");
        assertThat(choices).hasSize(3);
        assertThat(choices.get(0).path("apiKeyLocation").asString()).isEqualTo("header");
        assertThat(choices.get(1).path("apiKeyLocation").asString()).isEqualTo("query");
        assertThat(choices.get(2).path("apiKeyLocation").asString()).isEqualTo("cookie");
        for (JsonNode choice : choices) {
            assertThat(choice.path("auth").asString()).isEqualTo("api_key");
            assertThat(choice.path("credentialDraft").path("complete").asBoolean()).isFalse();
            assertThat(choice.toString()).doesNotContain("secret", "example", "default");
        }
    }

    @Test
    void treatsAndAsUnavailableButKeepsOrChoices() throws Exception {
        var doc = parse("""
                {"openapi":"3.1.0","info":{"title":"x","version":"1"},"paths":{},
                 "components":{"securitySchemes":{
                   "bearer":{"type":"http","scheme":"bearer"},
                   "basic":{"type":"http","scheme":"basic"}
                 }}}
                """);
        var and = OpenApiSecurity.alternatives(doc, JSON.readTree("{\"bearer\":[],\"basic\":[]}"), "/security", "");
        assertThat(and).hasSize(1);
        assertThat(and.get(0).path("available").asBoolean()).isFalse();
        assertThat(and.get(0).path("auth").asString()).isNotEqualTo("none");

        var or = OpenApiSecurity.alternatives(doc, JSON.readTree("[{\"bearer\":[]},{\"basic\":[]}]"), "/security", "");
        assertThat(or).hasSize(2);
        assertThat(or.get(0).path("available").asBoolean()).isTrue();
        assertThat(or.get(1).path("auth").asString()).isEqualTo("basic");
    }

    @Test
    void projectsOauthFlowsScopesAndThreePointTwoFields() throws Exception {
        var doc = parse("""
                {"openapi":"3.2.1","info":{"title":"x","version":"1"},"paths":{},
                 "components":{"securitySchemes":{
                   "oauth":{"type":"oauth2","deprecated":true,"flows":{
                     "authorizationCode":{"authorizationUrl":"/authorize","tokenUrl":"/token","refreshUrl":"/refresh"},
                     "deviceAuthorization":{"deviceAuthorizationUrl":"/device","tokenUrl":"/token"},
                     "implicit":{"authorizationUrl":"/bad"}
                   }}
                 }}}
                """);
        var choices = OpenApiSecurity.alternatives(doc, JSON.readTree("[{\"oauth\":[\"read\",\"write\"]}]"), "/security", "https://example.test/v1");
        var choice = choices.get(0);
        assertThat(choice.path("available").asBoolean()).isTrue();
        assertThat(choice.path("deprecated").asBoolean()).isTrue();
        var draft = choice.path("credentialDraft");
        assertThat(draft.path("type").asString()).isEqualTo("oauth2");
        assertThat(draft.path("complete").asBoolean()).isFalse();
        assertThat(draft.path("configuration").path("scopes").toString()).contains("read", "write");
        assertThat(draft.path("flows").size()).isEqualTo(2);
        assertThat(draft.path("flows").get(0).path("id").asText()).contains("authorizationCode");
        assertThat(draft.toString()).doesNotContain("clientSecret", "client_id", "password");
        assertThat(draft.path("flows").get(0).path("authorizationUrl").asText()).isEqualTo("https://example.test/authorize");
    }

    @Test
    void rejectsUnsupportedAndExternalReferencesWithoutAnonymousFallback() throws Exception {
        var doc = parse("""
                {"openapi":"3.1.0","info":{"title":"x","version":"1"},"paths":{},
                 "components":{"securitySchemes":{
                   "mtls":{"type":"mutualTLS"},
                   "external":{"$ref":"https://other.test/security.json#/components/securitySchemes/x"},
                   "cycle":{"$ref":"#/components/securitySchemes/cycle"}
                 }}}
                """);
        var choices = OpenApiSecurity.alternatives(doc,
                JSON.readTree("[{\"mtls\":[]},{\"external\":[]},{\"cycle\":[]},{\"missing\":[]}]"), "/security", "");
        assertThat(choices).allMatch(choice -> !choice.path("available").asBoolean());
        assertThat(choices).allMatch(choice -> !choice.path("auth").asString().equals("none"));
        assertThat(choices.get(1).path("reason").asText()).contains("external");
    }

    private static OpenApiDocument parse(String text) {
        return OpenApiDocument.parse(text, "https://example.test/spec.json");
    }
}
