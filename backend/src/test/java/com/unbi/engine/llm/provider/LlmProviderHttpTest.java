package com.unbi.engine.llm.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.unbi.engine.llm.Fixtures;
import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatMessage;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.FinishReason;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.ModelSpec;
import com.unbi.engine.llm.spec.RatePolicy;
import com.unbi.engine.llm.spec.TokenUsage;
import com.unbi.engine.llm.spec.WebSearchMode;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The real HTTP path, against a real socket.
 *
 * <p>The wire tests prove what the body says; this proves it arrives, that headers go with it, that
 * a stream is read incrementally, and that an error comes back classified. Those are the four things
 * a pure-function test structurally cannot cover, and they are where transports actually break.
 *
 * <p>The JDK's own server, so there is no new dependency and no fixture framework to learn.
 */
class LlmProviderHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<Map<String, List<String>>> lastHeaders = new AtomicReference<>(Map.of());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Registers a handler, recording what the client sent before answering. */
    private void respond(String path, Consumer<HttpExchange> handler) {
        server.createContext(path, exchange -> {
            try (InputStream body = exchange.getRequestBody()) {
                lastBody.set(new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            lastHeaders.set(Map.copyOf(exchange.getRequestHeaders()));
            handler.accept(exchange);
            exchange.close();
        });
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) {
        try {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException undeliverable) {
            throw new IllegalStateException(undeliverable);
        }
    }

    private ModelSpec model(boolean stream, ApiFormat format) {
        var endpoint = new EndpointSpec(
                "local", "custom", baseUrl, EndpointSpec.AuthScheme.BEARER, "key",
                Map.of("X-Title", "UNBI-Engine"), RatePolicy.UNLIMITED, 5000, stream,
                TokenUsage.CachedTokenMode.INCLUDED, false);
        return Fixtures.searching(Fixtures.model(endpoint), WebSearchMode.NONE, format);
    }

    private static ChatCall call(ModelSpec model) {
        return ChatCall.of(model, List.of(ChatMessage.user("hello")));
    }

    private static Credential credential() {
        return Credential.bearer("key", "sk-test");
    }

    @Nested
    class Buffered {

        @Test
        void sendsTheRequestAndReadsTheAnswer() {
            respond("/chat/completions", exchange -> send(exchange, 200, "application/json", """
                    {"model":"served","usage":{"prompt_tokens":7,"completion_tokens":2},
                     "choices":[{"finish_reason":"stop","message":{"content":"hi"}}]}"""));

            var result = new ChatCompletionsProvider(new HttpTransport())
                    .complete(call(model(false, ApiFormat.CHAT_COMPLETIONS)), credential(), StreamSink.DISCARD);

            assertThat(result.text()).isEqualTo("hi");
            assertThat(result.usage().promptTokens()).isEqualTo(7);
            assertThat(MAPPER.readTree(lastBody.get()).path("messages").path(0).path("content").asString(""))
                    .isEqualTo("hello");
        }

        @Test
        @DisplayName("the credential and the endpoint's own headers both reach the gateway")
        void authenticatesAndCarriesEndpointHeaders() {
            respond("/chat/completions", exchange -> send(exchange, 200, "application/json",
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"x\"}}]}"));

            new ChatCompletionsProvider(new HttpTransport())
                    .complete(call(model(false, ApiFormat.CHAT_COMPLETIONS)), credential(), StreamSink.DISCARD);

            var headers = lastHeaders.get();
            assertThat(headers.get("Authorization")).containsExactly("Bearer sk-test");
            assertThat(headers.get("X-title")).containsExactly("UNBI-Engine");
        }

        @Test
        @DisplayName("a gateway error arrives classified, with the gateway's own words in it")
        void errorsAreClassified() {
            respond("/chat/completions", exchange -> send(exchange, 429, "application/json",
                    "{\"error\":{\"message\":\"slow down\"}}"));

            assertThatThrownBy(() -> new ChatCompletionsProvider(new HttpTransport())
                            .complete(call(model(false, ApiFormat.CHAT_COMPLETIONS)), credential(),
                                    StreamSink.DISCARD))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.RATE_LIMIT);
                        assertThat(failure.isRetryable()).isTrue();
                    })
                    .hasMessageContaining("slow down");
        }

        @Test
        @DisplayName("Retry-After is read, so a backoff waits as long as the server asked")
        void retryAfterIsHonoured() {
            respond("/chat/completions", exchange -> {
                exchange.getResponseHeaders().add("Retry-After", "3");
                send(exchange, 429, "application/json", "{}");
            });

            assertThatThrownBy(() -> new ChatCompletionsProvider(new HttpTransport())
                            .complete(call(model(false, ApiFormat.CHAT_COMPLETIONS)), credential(),
                                    StreamSink.DISCARD))
                    .isInstanceOfSatisfying(LlmFailure.class,
                            failure -> assertThat(failure.retryAfterMillis()).isEqualTo(3000));
        }

        @Test
        @DisplayName("an HTML error page is reported as itself, not as a parse failure")
        void nonJsonErrorsStillCarryTheirBody() {
            respond("/chat/completions", exchange ->
                    send(exchange, 502, "text/html", "<html>Bad Gateway</html>"));

            assertThatThrownBy(() -> new ChatCompletionsProvider(new HttpTransport())
                            .complete(call(model(false, ApiFormat.CHAT_COMPLETIONS)), credential(),
                                    StreamSink.DISCARD))
                    .isInstanceOfSatisfying(LlmFailure.class,
                            failure -> assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.SERVER))
                    .hasMessageContaining("Bad Gateway");
        }
    }

    @Nested
    class Streamed {

        private static final String SSE = """
                : keep-alive

                data: {"model":"served","choices":[{"delta":{"content":"Hel"}}]}

                data: {"choices":[{"delta":{"content":"lo"}}]}

                data: {"choices":[{"finish_reason":"stop","delta":{}}],"usage":{"prompt_tokens":4,"completion_tokens":2}}

                data: [DONE]

                """;

        @Test
        void reassemblesAStreamIntoTheSameShapeAsABufferedAnswer() {
            respond("/chat/completions", exchange -> send(exchange, 200, "text/event-stream", SSE));
            var chunks = new StringBuilder();

            var result = new ChatCompletionsProvider(new HttpTransport()).complete(
                    call(model(true, ApiFormat.CHAT_COMPLETIONS)), credential(), chunks::append);

            assertThat(chunks.toString()).isEqualTo("Hello");
            assertThat(result.text()).isEqualTo("Hello");
            assertThat(result.finishReason()).isEqualTo(FinishReason.STOP);
            assertThat(result.usage().completionTokens()).isEqualTo(2);
            assertThat(MAPPER.readTree(lastBody.get()).path("stream").asBoolean(false)).isTrue();
        }

        @Test
        @DisplayName("cancelling stops mid-answer instead of waiting for the model to finish")
        void cancellationStopsReadingTheStream() {
            respond("/chat/completions", exchange -> send(exchange, 200, "text/event-stream", SSE));
            var stop = new AtomicBoolean();
            var chunks = new StringBuilder();

            var sink = new StreamSink() {
                @Override
                public void chunk(String text) {
                    chunks.append(text);
                    // Stop as soon as anything has arrived.
                    stop.set(true);
                }

                @Override
                public boolean cancelled() {
                    return stop.get();
                }
            };

            var result = new ChatCompletionsProvider(new HttpTransport())
                    .complete(call(model(true, ApiFormat.CHAT_COMPLETIONS)), credential(), sink);
            assertThat(chunks.toString()).isEqualTo("Hel");
            assertThat(result.text()).isEqualTo("Hel");
        }

        @Test
        @DisplayName("an error on the streaming path is read as text, not as a broken event stream")
        void streamingErrorsAreClassifiedToo() {
            respond("/chat/completions", exchange -> send(exchange, 401, "application/json",
                    "{\"error\":{\"message\":\"bad key\"}}"));

            assertThatThrownBy(() -> new ChatCompletionsProvider(new HttpTransport())
                            .complete(call(model(true, ApiFormat.CHAT_COMPLETIONS)), credential(),
                                    StreamSink.DISCARD))
                    .isInstanceOfSatisfying(LlmFailure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(LlmFailure.Kind.AUTH);
                        assertThat(failure.isRetryable()).isFalse();
                    })
                    .hasMessageContaining("bad key");
        }
    }

    @Nested
    class Responses {

        @Test
        void usesTheResponsesPathAndReadsItsShape() {
            respond("/responses", exchange -> send(exchange, 200, "application/json", """
                    {"model":"served","status":"completed",
                     "usage":{"input_tokens":5,"output_tokens":1},
                     "output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}"""));

            var result = new ResponsesProvider(new HttpTransport())
                    .complete(call(model(false, ApiFormat.RESPONSES)), credential(), StreamSink.DISCARD);

            assertThat(result.text()).isEqualTo("ok");
            assertThat(result.usage().promptTokens()).isEqualTo(5);
            assertThat(MAPPER.readTree(lastBody.get()).path("input").path(0).path("role").asString(""))
                    .isEqualTo("user");
        }

        @Test
        @DisplayName("a failed response arrives as HTTP 200 and must not read as an empty answer")
        void aFailedResponseIsAFailure() {
            respond("/responses", exchange -> send(exchange, 200, "application/json",
                    "{\"status\":\"failed\",\"error\":{\"message\":\"model refused\"}}"));

            assertThatThrownBy(() -> new ResponsesProvider(new HttpTransport())
                            .complete(call(model(false, ApiFormat.RESPONSES)), credential(), StreamSink.DISCARD))
                    .isInstanceOf(LlmFailure.class)
                    .hasMessageContaining("model refused");
        }

        @Test
        void streamsAndKeepsTheTerminalUsage() {
            respond("/responses", exchange -> send(exchange, 200, "text/event-stream", """
                    data: {"type":"response.output_text.delta","delta":"par"}

                    data: {"type":"response.output_text.delta","delta":"tial"}

                    data: {"type":"response.incomplete","response":{"status":"incomplete",\
                    "incomplete_details":{"reason":"max_output_tokens"},\
                    "usage":{"input_tokens":3,"output_tokens":9},\
                    "output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]}]}}

                    data: [DONE]

                    """));

            var result = new ResponsesProvider(new HttpTransport())
                    .complete(call(model(true, ApiFormat.RESPONSES)), credential(), StreamSink.DISCARD);

            assertThat(result.text()).isEqualTo("partial");
            assertThat(result.wasTruncated()).isTrue();
            assertThat(result.usage().completionTokens()).isEqualTo(9);
        }
    }

    @Nested
    class Unauthenticated {

        @Test
        @DisplayName("a local server that takes no key is sent no Authorization header")
        void noCredentialMeansNoHeader() {
            respond("/chat/completions", exchange -> send(exchange, 200, "application/json",
                    "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"x\"}}]}"));

            var endpoint = new EndpointSpec(
                    "local", "llamacpp", baseUrl, EndpointSpec.AuthScheme.NONE, "",
                    Map.of(), RatePolicy.UNLIMITED, 5000, false,
                    TokenUsage.CachedTokenMode.INCLUDED, false);

            new ChatCompletionsProvider(new HttpTransport())
                    .complete(call(Fixtures.model(endpoint)), null, StreamSink.DISCARD);

            assertThat(lastHeaders.get()).doesNotContainKey("Authorization");
        }
    }
}
