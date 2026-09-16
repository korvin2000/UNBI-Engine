package com.unbi.engine.llm.discovery;

import com.unbi.engine.llm.auth.CredentialStore;
import com.unbi.engine.llm.provider.HttpTransport;
import com.unbi.engine.llm.spec.EndpointSpec;
import com.unbi.engine.llm.spec.LlmFailure;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A {@link GatewayDirectory} that answers from captured files instead of from a socket.
 *
 * <p>A subclass rather than an interface and two implementations, because the thing being replaced
 * is one method — {@code get} — and the credential resolution behind it is the part that must
 * <em>not</em> be replaced anywhere real. Overriding the one call keeps this a stand-in for the
 * network and nothing else; the nodes under test still go through the same object a run does.
 *
 * <p>It also records what was asked, in order, which is half of what these tests assert: that the
 * call the verdict depends on goes first, and that an optional one that 404s is not fatal.
 */
public final class StubGateway extends GatewayDirectory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Where the captured OpenRouter bodies live, relative to the backend module. */
    private static final Path FIXTURES = Path.of("src", "test", "resources", "openrouter");

    private final Map<String, JsonNode> bodies = new LinkedHashMap<>();
    private final Map<String, LlmFailure> failures = new LinkedHashMap<>();
    private final List<String> asked = new ArrayList<>();

    public StubGateway() {
        super(new HttpTransport(), new CredentialStore(List.of()));
    }

    /** Answers {@code path} with a captured fixture body. */
    public StubGateway answering(String path, String fixture) {
        bodies.put(path, read(fixture));
        return this;
    }

    public StubGateway answering(String path, JsonNode body) {
        bodies.put(path, body);
        return this;
    }

    /** Fails {@code path} with a status, the way {@link HttpTransport#get} would. */
    public StubGateway failing(String path, int status, String message) {
        failures.put(path, new LlmFailure(
                LlmFailure.classify(status, message), "HTTP %d — %s".formatted(status, message),
                path, status, -1, null));
        return this;
    }

    /** Every path asked for, in order. */
    public List<String> asked() {
        return List.copyOf(asked);
    }

    @Override
    public JsonNode get(EndpointSpec endpoint, String path, Duration timeout) {
        asked.add(path);
        var failure = failures.get(path);
        if (failure != null) {
            throw failure;
        }
        var body = bodies.get(path);
        if (body == null) {
            // The same shape a gateway gives for a path it does not serve, so a test that forgot to
            // stub something fails the way production would rather than with a null.
            throw new LlmFailure(
                    LlmFailure.Kind.MODEL_UNAVAILABLE, "HTTP 404 — Not Found",
                    endpoint.baseUrl() + path, 404, -1, null);
        }
        return body;
    }

    /** One captured body, by file name. */
    public static JsonNode read(String fixture) {
        try {
            return MAPPER.readTree(Files.readString(FIXTURES.resolve(fixture), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("Missing fixture " + fixture, unreadable);
        }
    }
}
