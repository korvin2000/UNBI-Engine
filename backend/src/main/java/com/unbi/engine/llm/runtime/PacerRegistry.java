package com.unbi.engine.llm.runtime;

import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.RatePolicy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * One pacer per endpoint, shared by every node that talks to it.
 *
 * <p>Shared is the whole point: a rate limit is a property of the gateway, so three request nodes
 * pointed at one llama.cpp server have to queue behind <em>one</em> ceiling, not three. Keying on
 * the endpoint id rather than on the node is what makes that true.
 *
 * <p>The policy is captured the first time an endpoint is seen. Changing a rate limit in the editor
 * therefore takes effect on the next engine start — stated rather than silently half-applied, since
 * swapping a live pacer would let requests already queued under the old ceiling escape the new one.
 */
@Component
public class PacerRegistry {

    private final Map<String, RequestPacer> pacers = new ConcurrentHashMap<>();
    private final RequestPacer.Clock clock;

    public PacerRegistry() {
        this(RequestPacer.Clock.SYSTEM);
    }

    public PacerRegistry(RequestPacer.Clock clock) {
        this.clock = clock;
    }

    public RequestPacer forEndpoint(EndpointSpec endpoint) {
        return forKey(endpoint.id(), endpoint.rate());
    }

    public RequestPacer forKey(String key, RatePolicy policy) {
        return pacers.computeIfAbsent(key, ignored -> new RequestPacer(policy, clock));
    }
}
