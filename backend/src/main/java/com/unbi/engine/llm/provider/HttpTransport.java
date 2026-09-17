package com.unbi.engine.llm.provider;

import com.unbi.engine.llm.auth.RequestAuthorization;
import com.unbi.engine.llm.spec.LlmFailure;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** HTTP attempts own a single deadline spanning headers and body, including stalled SSE reads. */
@Component
public class HttpTransport implements AutoCloseable {
    private static final int MAX_ERROR_BODY = 2000;
    private static final int MAX_JSON_BODY = 32 * 1024 * 1024;
    // A mismatched header is verified from the first SSE field, retaining the hard frame cap so
    // valid large first events are not rejected merely for their media label.
    private static final int MAX_STREAM_MEDIA_PROBE = SseReader.MAX_FRAME;
    private final HttpClient client;
    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();

    public HttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER)
                // Avoid h2c upgrades rejected by compatible plain-HTTP gateways.
                .version(HttpClient.Version.HTTP_1_1).build());
    }

    public HttpTransport(HttpClient client) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Gateway redirects must be disabled");
        this.client = client;
    }

    public JsonNode get(RequestAuthorization authorization, Duration timeout, BooleanSupplier cancelled) {
        var builder = HttpRequest.newBuilder(uri(authorization.url())).header("Accept", "application/json").GET();
        authorization.headers().forEach(builder::header);
        return execute(builder.build(), authorization.safeUrl(), timeout, cancelled,
                response -> json(response, authorization.safeUrl()));
    }

    public JsonNode post(RequestAuthorization authorization, ObjectNode body,
                         Duration timeout, BooleanSupplier cancelled) {
        return execute(build(authorization, body, "application/json"), authorization.safeUrl(), timeout, cancelled,
                response -> json(response, authorization.safeUrl()));
    }

    public void postStreaming(RequestAuthorization authorization, ObjectNode body, Duration timeout,
                              Predicate<JsonNode> onEvent, BooleanSupplier cancelled) {
        var url = authorization.safeUrl();
        execute(build(authorization, body, "text/event-stream"), url, timeout, cancelled, response -> {
            checkStatus(response, url);
            var frames = new SseReader();
            boolean streamMediaVerified = isSseContentType(response.headers());
            int mediaProbeSize = 0;
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                var line = new StringBuilder();
                boolean afterCr = false;
                int ch;
                while ((ch = reader.read()) != -1) {
                    if (cancelled.getAsBoolean()) throw cancellation();
                    if (afterCr && ch == '\n') { afterCr = false; continue; }
                    afterCr = ch == '\r';
                    if (ch == '\r' || ch == '\n') {
                        if (!streamMediaVerified) {
                            mediaProbeSize += line.length() + 1;
                            if (mediaProbeSize > MAX_STREAM_MEDIA_PROBE) throw unsupportedStreamMedia(url);
                            var probe = line.toString();
                            if (isSsePreamble(probe)) streamMediaVerified = true;
                            else if (!isSsePadding(probe)) throw unsupportedStreamMedia(url);
                        }
                        var event = frames.accept(line.toString());
                        line.setLength(0);
                        if (event == null || event.data().isEmpty()) continue;
                        if (event.data().equals("[DONE]")) return null;
                        JsonNode node;
                        try { node = mapper.readTree(event.data()); }
                        catch (RuntimeException invalid) { throw SseReader.malformed(); }
                        if (node == null || !node.isObject()) throw SseReader.malformed();
                        if (!node.has("type") && !event.type().isEmpty())
                            ((ObjectNode) node).put("type", event.type());
                        // Consumer exceptions are not parsing errors and must reach the caller.
                        if (onEvent.test(node)) return null;
                    } else {
                        if (!streamMediaVerified && mediaProbeSize + line.length() >= MAX_STREAM_MEDIA_PROBE)
                            throw unsupportedStreamMedia(url);
                        if (line.length() >= SseReader.MAX_FRAME) throw SseReader.malformed();
                        line.append((char) ch);
                        if (!streamMediaVerified && ch == ':' && line.length() <= 7
                                && isSsePreamble(line.toString())) streamMediaVerified = true;
                    }
                }
                if (!streamMediaVerified) throw unsupportedStreamMedia(url);
                return null;
            } catch (IOException failure) { throw transportFailure(url, failure); }
        });
    }

    private JsonNode json(HttpResponse<InputStream> response, String url) {
        checkResponse(response, url, "application/json");
        try {
            var bytes = response.body().readNBytes(MAX_JSON_BODY + 1);
            if (bytes.length > MAX_JSON_BODY) throw format(url);
            JsonNode node;
            try { node = mapper.readTree(bytes); }
            catch (RuntimeException invalid) { throw format(url); }
            if (node == null || node.isMissingNode()) throw format(url);
            return node;
        } catch (IOException failure) { throw transportFailure(url, failure); }
    }

    private void checkResponse(HttpResponse<InputStream> response, String url, String expected) {
        checkStatus(response, url);
        var type = contentType(response.headers());
        if (!type.equalsIgnoreCase(expected)
                && !(expected.equals("application/json") && type.toLowerCase(java.util.Locale.ROOT).endsWith("+json")))
            throw format(url);
    }

    private static void checkStatus(HttpResponse<InputStream> response, String url) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body;
            try { body = new String(response.body().readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8); }
            catch (IOException failure) { throw transportFailure(url, failure); }
            // Do not expose arbitrary upstream bodies, parser excerpts, or echoed credentials.
            throw new LlmFailure(LlmFailure.classify(response.statusCode(), body),
                    "Endpoint returned HTTP " + response.statusCode(), url, response.statusCode(),
                    retryAfterMillis(response.headers()), null);
        }
    }

    private static boolean isSseContentType(java.net.http.HttpHeaders headers) {
        return contentType(headers).equalsIgnoreCase("text/event-stream");
    }

    private static String contentType(java.net.http.HttpHeaders headers) {
        return headers.firstValue("Content-Type").orElse("").split(";", 2)[0].trim();
    }

    /**
     * A few proxies lose or coalesce Content-Type on an otherwise valid stream. Accept only an
     * initial SSE field/comment; this deliberately excludes JSON and HTML before event decoding.
     */
    private static boolean isSsePreamble(String line) {
        line = withoutInitialBom(line);
        return line.startsWith(":")
                || isSseField(line, "data")
                || isSseField(line, "event")
                || isSseField(line, "id")
                || isSseField(line, "retry");
    }

    private static boolean isSsePadding(String line) {
        return withoutInitialBom(line).isEmpty();
    }
    private static boolean isSseField(String line, String field) {
        return line.equals(field) || line.startsWith(field + ":");
    }

    private static String withoutInitialBom(String line) {
        return line.startsWith("\ufeff") ? line.substring(1) : line;
    }

    private static LlmFailure unsupportedStreamMedia(String url) {
        return new LlmFailure(LlmFailure.Kind.RESPONSE_FORMAT,
                "The endpoint returned a successful response with an unsupported streaming media type",
                url, 0, -1, null);
    }

    private <T> T execute(HttpRequest request, String safeUrl, Duration timeout, BooleanSupplier cancelled,
                          Function<HttpResponse<InputStream>, T> consume) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancellation();
        long deadline = System.nanoTime() + timeout.toNanos();
        var responseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response = null;
        Future<T> reading = null;
        try {
            response = await(responseFuture, deadline, cancelled);
            var received = response;
            reading = readers.submit(() -> consume.apply(received));
            return await(reading, deadline, cancelled);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw cancellation();
        } catch (TimeoutException timeoutFailure) {
            throw new LlmFailure(LlmFailure.Kind.TIMEOUT, "The endpoint exceeded the request deadline",
                    safeUrl, 0, -1, null);
        } catch (ExecutionException failed) {
            var cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw transportFailure(safeUrl, cause);
        } finally {
            if (response != null) closeBody(response.body());
            else responseFuture.thenAccept(late -> closeBody(late.body()));
            responseFuture.cancel(true);
            if (reading != null) reading.cancel(true);
        }
    }

    private static <T> T await(Future<T> future, long deadline, BooleanSupplier cancelled)
            throws InterruptedException, TimeoutException, ExecutionException {
        while (true) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw cancellation();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            try { return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
            catch (TimeoutException pending) { /* Poll the logical call's cancellation and deadline. */ }
        }
    }

    private static HttpRequest build(RequestAuthorization authorization, ObjectNode body,
                                     String accept) {
        var builder = HttpRequest.newBuilder(uri(authorization.url()))
                .header("Content-Type", "application/json").header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        authorization.headers().forEach(builder::header);
        return builder.build();
    }

    private static URI uri(String url) {
        try { return URI.create(url); }
        catch (IllegalArgumentException invalid) {
            throw new LlmFailure(LlmFailure.Kind.INVALID_REQUEST, "Not a usable endpoint URL");
        }
    }

    private static LlmFailure format(String url) {
        return new LlmFailure(LlmFailure.Kind.RESPONSE_FORMAT, "The endpoint returned an invalid response",
                url, 0, -1, null);
    }

    private static LlmFailure cancellation() {
        return new LlmFailure(LlmFailure.Kind.CANCELLED, "The request was cancelled");
    }

    private static LlmFailure transportFailure(String url, Throwable failure) {
        var kind = failure instanceof HttpTimeoutException ? LlmFailure.Kind.TIMEOUT : LlmFailure.Kind.NETWORK;
        return new LlmFailure(kind, "Could not read the endpoint response", url, 0, -1, null);
    }

    private static long retryAfterMillis(java.net.http.HttpHeaders headers) {
        var value = headers.firstValue("retry-after").orElse("");
        try { return Math.max(0, (long) (Double.parseDouble(value.trim()) * 1000)); }
        catch (NumberFormatException notSeconds) {
            try {
                var time = java.time.ZonedDateTime.parse(value.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                return Math.max(0, time.toInstant().toEpochMilli() - System.currentTimeMillis());
            } catch (RuntimeException invalid) { return -1; }
        }
    }

    private static void closeBody(InputStream body) {
        try { body.close(); }
        catch (IOException ignored) { /* The attempt has already finished or failed. */ }
    }

    @Override
    @PreDestroy
    public void close() {
        readers.shutdownNow();
        client.shutdownNow();
    }
}
