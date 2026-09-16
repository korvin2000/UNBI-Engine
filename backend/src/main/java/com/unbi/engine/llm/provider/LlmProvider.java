package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.auth.Credential;
import com.unbi.engine.llm.spec.ApiFormat;
import com.unbi.engine.llm.spec.ChatCall;
import com.unbi.engine.llm.spec.ChatResult;
import com.unbi.engine.llm.spec.EndpointSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Something that can answer a {@link ChatCall}.
 *
 * <p>The seam between nodes and gateways. A node builds a call and never learns which dialect
 * carried it; a provider answers one and never learns which node asked. Adding a genuinely different
 * protocol — a native Anthropic Messages transport, a local in-process model — is one more bean and
 * changes nothing above it.
 *
 * <p>Note what is <em>not</em> here: no per-gateway subclass. OpenRouter, llama.cpp, OmniRoute and
 * OpenAI all speak one of the two dialects below, and everything they do differently is a field on
 * {@link EndpointSpec} or {@code ModelSpec}. A provider per vendor would be four copies of one
 * request builder drifting apart.
 */
public interface LlmProvider {

    ApiFormat format();

    /**
     * @param sink receives partial text while the answer arrives, and is asked whether to stop
     * @throws com.unbi.engine.llm.spec.LlmFailure classified, so the caller can decide what to do
     */
    ChatResult complete(ChatCall call, Credential credential, StreamSink sink);

    /**
     * The headers one request needs: the endpoint's own, then whatever the credential requires.
     *
     * <p>The credential goes on last deliberately. An endpoint header must never be able to
     * overwrite an {@code Authorization} that a credential source produced — that is the difference
     * between a misconfiguration and a silently unauthenticated request.
     */
    static Map<String, String> headers(EndpointSpec endpoint, Credential credential) {
        var headers = new LinkedHashMap<>(endpoint.headers());
        if (credential == null || credential.isEmpty()) {
            return headers;
        }
        headers.put("Authorization", "Bearer " + credential.token());
        headers.putAll(credential.headers());
        return headers;
    }
}
