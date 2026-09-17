package com.unbi.engine.nodes.llm;

import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.auth.EnvironmentCredentials;
import com.unbi.engine.llm.provider.LlmProvider;
import com.unbi.engine.llm.provider.ProviderRegistry;
import com.unbi.engine.llm.provider.StreamSink;
import com.unbi.engine.llm.runtime.LlmCaller;
import com.unbi.engine.llm.runtime.PacerRegistry;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatResult;
import com.unbi.engine.llm.spec.FinishReason;
import com.unbi.engine.llm.spec.LlmFailure;
import com.unbi.engine.llm.spec.TokenUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A provider that answers from a script, and remembers what it was asked.
 *
 * <p>A stub transport rather than a mocked caller: the node tests then exercise the real capability
 * check, the real pacer and the real retry loop, and only the socket is fake. Mocking the caller
 * would test that a node calls a method, which is not the thing that breaks.
 */
final class StubProvider implements LlmProvider {

    // Concurrent: the request node runs a batch on several virtual threads at once.
    private final List<ChatCall> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Function<ChatCall, ChatResult> answer;
    private final List<String> chunks;

    private StubProvider(Function<ChatCall, ChatResult> answer, List<String> chunks) {
        this.answer = answer;
        this.chunks = chunks;
    }

    static StubProvider answering(String text) {
        return new StubProvider(call -> result(text, FinishReason.STOP), List.of());
    }

    private static ChatResult result(String text, FinishReason reason) {
        return new ChatResult(
                text, reason, new TokenUsage(10, 5, 0, 0, 15), "stub/model", 3, -1, false, List.of());
    }


    static StubProvider streaming(String... chunks) {
        return new StubProvider(
                call -> result(String.join("", chunks), FinishReason.STOP), List.of(chunks));
    }

    static StubProvider failing(LlmFailure.Kind kind, String message) {
        return new StubProvider(call -> {
            throw new LlmFailure(kind, message);
        }, List.of());
    }

    static StubProvider truncating(String text) {
        return new StubProvider(call -> result(text, FinishReason.LENGTH), List.of());
    }

    static StubProvider emitsThenFails(String chunk) {
        return new StubProvider(call -> {
            throw new LlmFailure(LlmFailure.Kind.SERVER, "disconnect after output");
        }, List.of(chunk));
    }

    /** Fails only the calls whose user prompt contains {@code needle}; echoes the rest. */
    static StubProvider failingOn(String needle, LlmFailure.Kind kind, String message) {
        return new StubProvider(call -> {
            if (call.messages().getLast().text().contains(needle)) {
                throw new LlmFailure(kind, message);
            }
            return result(call.messages().getLast().text(), FinishReason.STOP);
        }, List.of());
    }

    /** Answers each call with the item's own prompt echoed back, for batch ordering tests. */
    static StubProvider echoing() {
        return new StubProvider(
                call -> result(call.messages().getLast().text(), FinishReason.STOP), List.of());
    }


    @Override
    public ApiFormat format() {
        return ApiFormat.CHAT_COMPLETIONS;
    }

    @Override
    public ChatResult complete(ChatCall call, Credential credential, StreamSink sink) {
        calls.add(call);
        chunks.forEach(chunk -> {
            sink.chunk(chunk);
            sink.progress(chunk.length());
        });
        return answer.apply(call);
    }

    List<ChatCall> calls() {
        return List.copyOf(calls);
    }

    ChatCall onlyCall() {
        if (calls.size() != 1) {
            throw new AssertionError("Expected exactly one call, got " + calls.size());
        }
        return calls.getFirst();
    }

    /** A caller wired to this provider, with a credential the endpoint fixture can resolve. */
    LlmCaller caller() {
        return new LlmCaller(
                new ProviderRegistry(List.of(this)),
                new CredentialStore(List.of(
                        new EnvironmentCredentials(Map.of("UNBI_LLM_KEY_KEY", "stub-token")))),
                new PacerRegistry());
    }
}
