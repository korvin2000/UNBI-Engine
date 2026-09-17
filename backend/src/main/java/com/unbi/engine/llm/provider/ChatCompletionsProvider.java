package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.auth.RequestAuthorization;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatResult;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Chat Completions, buffered or streamed.
 *
 * <p>Streaming is not only a display feature. At least one gateway in this pack's world returns
 * <em>another in-flight request's</em> completion when two buffered calls overlap — measured, and
 * fixed entirely by asking for a stream. That is why {@code stream} is an endpoint setting rather
 * than a request one, and why the streamed path reassembles into the buffered shape instead of
 * being a second way to read an answer.
 */
@Component
public class ChatCompletionsProvider implements LlmProvider {

    private final HttpTransport transport;

    public ChatCompletionsProvider(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public ApiFormat format() {
        return ApiFormat.CHAT_COMPLETIONS;
    }

    @Override
    public ChatResult complete(ChatCall call, Credential credential, StreamSink sink) {
        var endpoint = call.model().endpoint();
        var authorization = RequestAuthorization.forEndpoint(endpoint, credential, ApiFormat.CHAT_COMPLETIONS.path());
        var timeout = Duration.ofMillis(endpoint.timeoutMillis());
        var startedAt = System.nanoTime();

        if (!endpoint.stream()) {
            var response = transport.post(authorization, ChatWire.request(call, false), timeout, sink::cancelled);
            return ChatWire.parse(response, endpoint.cachedTokenMode(), millisSince(startedAt));
        }

        var accumulator = new ChatWire.Accumulator();
        transport.postStreaming(
                authorization,
                ChatWire.request(call, true),
                timeout,
                event -> {
                    var chunk = accumulator.accept(event);
                    if (!chunk.isEmpty()) {
                        sink.chunk(chunk);
                        sink.progress(accumulator.textSoFar().length());
                    }
                    return false;
                },
                sink::cancelled);
        return ChatWire.parse(accumulator.completion(), endpoint.cachedTokenMode(), millisSince(startedAt));
    }

    static long millisSince(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
