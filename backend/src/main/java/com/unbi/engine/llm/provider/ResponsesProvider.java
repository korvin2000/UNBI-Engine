package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.auth.RequestAuthorization;
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
        var authorization = RequestAuthorization.forEndpoint(endpoint, credential, ApiFormat.RESPONSES.path());
        var timeout = Duration.ofMillis(endpoint.timeoutMillis());
        var startedAt = System.nanoTime();

        if (!endpoint.stream()) {
            var response = transport.post(
                    authorization, ResponsesWire.request(call, false), timeout, sink::cancelled);
            return ResponsesWire.parse(response, ChatCompletionsProvider.millisSince(startedAt));
        }

        var accumulator = new ResponsesWire.Accumulator();
        transport.postStreaming(
                authorization,
                ResponsesWire.request(call, true),
                timeout,
                event -> {
                    var chunk = accumulator.accept(event);
                    if (!chunk.isEmpty()) {
                        sink.chunk(chunk);
                        sink.progress(accumulator.textSoFar().length());
                    }
                    return ResponsesWire.isTerminal(event);
                },
                sink::cancelled);
        var response = accumulator.response();
        return ResponsesWire.parse(response, ChatCompletionsProvider.millisSince(startedAt));
    }

}
