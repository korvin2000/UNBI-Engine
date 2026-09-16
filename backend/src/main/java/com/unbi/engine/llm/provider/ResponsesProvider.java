package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatResult;
import com.unbi.engine.llm.spec.LlmFailure;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The Responses API: hosted web search, and the only dialect the Codex endpoint speaks.
 *
 * <p>A {@code failed} response arrives with HTTP 200 and an error inside it, which is the one shape
 * that would otherwise sail past every status check and reach a downstream node as an empty answer.
 */
@Component
public class ResponsesProvider implements LlmProvider {

    private final HttpTransport transport;

    public ResponsesProvider(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public ApiFormat format() {
        return ApiFormat.RESPONSES;
    }

    @Override
    public ChatResult complete(ChatCall call, Credential credential, StreamSink sink) {
        var endpoint = call.model().endpoint();
        var url = endpoint.urlFor(ApiFormat.RESPONSES);
        var headers = LlmProvider.headers(endpoint, credential);
        var timeout = Duration.ofMillis(endpoint.timeoutMillis());
        var startedAt = System.nanoTime();

        if (!endpoint.stream()) {
            var response = transport.post(url, headers, ResponsesWire.request(call, false), timeout);
            assertNotFailed(response);
            return ResponsesWire.parse(response, ChatCompletionsProvider.millisSince(startedAt));
        }

        var accumulator = new ResponsesWire.Accumulator();
        transport.postStreaming(
                url,
                headers,
                ResponsesWire.request(call, true),
                timeout,
                event -> {
                    var chunk = accumulator.accept(event);
                    if (!chunk.isEmpty()) {
                        sink.chunk(chunk);
                        sink.progress(accumulator.textSoFar().length());
                    }
                },
                sink::cancelled);
        var response = accumulator.response();
        assertNotFailed(response);
        return ResponsesWire.parse(response, ChatCompletionsProvider.millisSince(startedAt));
    }

    private static void assertNotFailed(tools.jackson.databind.JsonNode response) {
        if (!"failed".equals(response.path("status").asString(""))) {
            return;
        }
        var message = response.path("error").path("message").asString("The Responses request failed");
        throw new LlmFailure(LlmFailure.classify(400, message), message);
    }
}
