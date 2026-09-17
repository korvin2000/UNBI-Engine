package com.unbi.engine.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.provider.HttpTransport;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.RatePolicy;
import com.unbi.engine.llm.spec.TokenUsage;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GatewayDirectoryTest {

    @Test
    void codexCatalogUsesCompatibilityVersionAndSharedNormalization() throws Exception {
        var query = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/v1/models", exchange -> {
                query.set(exchange.getRequestURI().getQuery());
                var body = """
                        {"models":[{"slug":"gpt-5-codex","display_name":"GPT-5 Codex",
                        "supported_reasoning_levels":[{"effort":"high"}],
                        "default_reasoning_level":{"effort":"high"},
                        "input_modalities":["text"],"context_window":200000}]}""";
                var bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();

            var endpoint = new EndpointSpec(
                    "codex-test", "custom", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    EndpointSpec.AuthScheme.NONE, "", Map.of(), RatePolicy.UNLIMITED, 10_000, false,
                    TokenUsage.CachedTokenMode.INCLUDED, false, ApiFormat.RESPONSES,
                    EndpointSpec.ResponsesDialect.CODEX, "header", "");
            try (var transport = new HttpTransport()) {
                var directory = new GatewayDirectory(transport, new CredentialStore(List.of()));
                var models = directory.models(endpoint);
                assertThat(models).singleElement().satisfies(model -> {
                    assertThat(model.id()).isEqualTo("gpt-5-codex");
                    assertThat(model.label()).isEqualTo("GPT-5 Codex");
                    assertThat(model.apiFormat()).isEqualTo(ApiFormat.RESPONSES);
                    assertThat(model.contextWindow()).isEqualTo(200_000);
                    assertThat(model.efforts()).containsExactly("high");
                    assertThat(model.inputPer1M()).isNull();
                    assertThat(model.outputPer1M()).isNull();
                });
                assertThat(query).hasValue("client_version=0.154.0");
            }
        } finally {
            server.stop(0);
        }
    }
}