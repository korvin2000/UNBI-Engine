package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.spec.LlmFailure;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One HTTP client for every gateway, buffered and streamed.
 *
 * <p>The JDK client rather than a framework one: it is already here, it does HTTP/2 and SSE, and on
 * a virtual thread its blocking API is the simple shape rather than the slow one. Nodes run on
 * virtual threads, so a blocking read costs a carrier thread nothing.
 *
 * <p>Everything a gateway can do wrong arrives as an {@link LlmFailure} with a kind, because the
 * layer above has to decide "retry, change something, or stop" and cannot do that from a stack
 * trace.
 */
@Component
public class HttpTransport {

    /** Past this, a body is almost certainly an HTML error page rather than a completion. */
    private static final int MAX_ERROR_BODY = 2000;

    /** Shared and stateless; used only to pull a message out of an error body. */
    private static final ObjectMapper ERROR_READER = new ObjectMapper();

    private final HttpClient client;
    private final ObjectMapper mapper;

    public HttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                // HTTP/1.1 explicitly, not the JDK's HTTP/2 default. Over plain http:// the client
                // opens with an h2c upgrade, and one of the gateways this pack targets answers that
                // by closing the socket — "header parser received no bytes", measured against a
                // local OmniRoute instance that serves the identical request happily over 1.1.
                // Nothing here multiplexes, so HTTP/2 buys this client nothing to weigh against it.
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    public HttpTransport(HttpClient client) {
        this.client = client;
        this.mapper = new ObjectMapper();
    }

    /**
     * A JSON GET, read in full.
     *
     * <p>Here because every "is this endpoint real?" question is one: a model listing, a key
     * introspection. It goes through the same failure classification as a completion, so a test
     * button and a run describe a bad key, a wrong URL and a dead host in the same words.
     */
    public JsonNode get(String url, Map<String, String> headers, Duration timeout) {
        var builder = HttpRequest.newBuilder(uri(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();
        headers.forEach(builder::header);
        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException failure) {
            throw transportFailure(url, failure);
        }
        if (response.statusCode() >= 400) {
            throw httpFailure(url, response.statusCode(), response.body(), response.headers());
        }
        try {
            return mapper.readTree(response.body());
        } catch (RuntimeException unparsable) {
            throw new LlmFailure(
                    LlmFailure.Kind.RESPONSE_FORMAT,
                    "The endpoint answered with something that is not JSON: " + truncate(response.body()),
                    url, response.statusCode(), -1, unparsable);
        }
    }

    /** A single JSON response, read in full. */
    public JsonNode post(String url, Map<String, String> headers, ObjectNode body, Duration timeout) {
        var request = build(url, headers, body, timeout, "application/json").build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException failure) {
            throw transportFailure(url, failure);
        }
        if (response.statusCode() >= 400) {
            throw httpFailure(url, response.statusCode(), response.body(), response.headers());
        }
        try {
            return mapper.readTree(response.body());
        } catch (RuntimeException unparsable) {
            throw new LlmFailure(
                    LlmFailure.Kind.RESPONSE_FORMAT,
                    "The endpoint answered with something that is not JSON: " + truncate(response.body()),
                    url, response.statusCode(), -1, unparsable);
        }
    }

    /**
     * A server-sent-event response, one decoded {@code data:} payload at a time.
     *
     * <p>Reading line by line rather than collecting is what makes cancellation possible mid-answer:
     * {@code shouldStop} is consulted between events, and returning true closes the connection
     * instead of waiting for a model that may have thousands of tokens still to say.
     *
     * @param onEvent receives each parsed event; the terminal {@code [DONE]} marker is swallowed
     */
    public void postStreaming(
            String url,
            Map<String, String> headers,
            ObjectNode body,
            Duration timeout,
            Consumer<JsonNode> onEvent,
            java.util.function.BooleanSupplier shouldStop) {

        var request = build(url, headers, body, timeout, "text/event-stream").build();
        HttpResponse<java.io.InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException failure) {
            throw transportFailure(url, failure);
        }

        try (var stream = response.body();
                var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            if (response.statusCode() >= 400) {
                // An error body is not an event stream; read it as text so the message is the
                // gateway's own words rather than "unparsable event".
                throw httpFailure(url, response.statusCode(), readAll(reader), response.headers());
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (shouldStop.getAsBoolean()) {
                    return;
                }
                var payload = SseReader.dataPayload(line);
                if (payload == null || payload.equals("[DONE]")) {
                    continue;
                }
                try {
                    onEvent.accept(mapper.readTree(payload));
                } catch (RuntimeException malformed) {
                    // One bad frame in a stream is not worth failing a whole answer that is
                    // otherwise arriving correctly; a gateway sending only bad frames produces an
                    // empty answer, which the caller already reports.
                    continue;
                }
            }
        } catch (IOException interrupted) {
            throw transportFailure(url, interrupted);
        }
    }

    private HttpRequest.Builder build(
            String url, Map<String, String> headers, ObjectNode body, Duration timeout, String accept) {
        var builder = HttpRequest.newBuilder(uri(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        return builder;
    }

    private static URI uri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException malformed) {
            throw new LlmFailure(
                    LlmFailure.Kind.INVALID_REQUEST, "Not a usable URL: " + url, url, 0, -1, malformed);
        }
    }

    private static LlmFailure transportFailure(String url, Exception failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new LlmFailure(LlmFailure.Kind.TIMEOUT, "The request was interrupted", url, 0, -1, failure);
        }
        var kind = failure instanceof HttpTimeoutException ? LlmFailure.Kind.TIMEOUT : LlmFailure.Kind.NETWORK;
        var message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        return new LlmFailure(kind, message, url, 0, -1, failure);
    }

    private static LlmFailure httpFailure(
            String url, int status, String body, java.net.http.HttpHeaders headers) {
        var detail = extractMessage(body);
        return new LlmFailure(
                LlmFailure.classify(status, body),
                "HTTP %d — %s".formatted(status, detail),
                url,
                status,
                retryAfterMillis(headers),
                null);
    }

    /** Gateways nest their message differently; the raw body is the honest fallback. */
    private static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return "no response body";
        }
        try {
            var root = ERROR_READER.readTree(body);
            var error = root.path("error");
            var message = error.isObject() ? error.path("message").asString("") : error.asString("");
            var fallback = message.isBlank() ? root.path("message").asString("") : message;
            return fallback.isBlank() ? truncate(body) : fallback;
        } catch (RuntimeException notJson) {
            return truncate(body);
        }
    }

    /** {@code Retry-After} in seconds or as an HTTP date. */
    private static long retryAfterMillis(java.net.http.HttpHeaders headers) {
        var value = headers.firstValue("retry-after").orElse(null);
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Math.max(0, (long) (Double.parseDouble(value.trim()) * 1000));
        } catch (NumberFormatException notSeconds) {
            try {
                var epoch = java.time.ZonedDateTime.parse(
                                value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli();
                return Math.max(0, epoch - System.currentTimeMillis());
            } catch (RuntimeException notADate) {
                return -1;
            }
        }
    }

    private static String readAll(BufferedReader reader) throws IOException {
        var out = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null && out.length() < MAX_ERROR_BODY) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String truncate(String text) {
        var trimmed = text.strip();
        return trimmed.length() <= MAX_ERROR_BODY ? trimmed : trimmed.substring(0, MAX_ERROR_BODY) + "…";
    }
}
