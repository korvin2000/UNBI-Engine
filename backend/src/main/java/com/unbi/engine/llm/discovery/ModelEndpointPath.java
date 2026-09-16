package com.unbi.engine.llm.discovery;

import tools.jackson.databind.JsonNode;

/**
 * The path that lists the hosts serving one model.
 *
 * <p>A pure function because the one thing it must never do is <em>split</em> the id. An OpenRouter
 * id is {@code vendor/model}, sometimes {@code vendor/model:free} and sometimes
 * {@code ~vendor/model-latest}: every separator a naive parser would reach for is a character that
 * legitimately appears in the middle of one. The id goes into the template whole, which is why
 * {@link com.unbi.engine.llm.spec.ProviderProfile#modelEndpointsPath()} is a template rather than a
 * prefix and a suffix.
 *
 * <p>Live-verified against OpenRouter: {@code /models/<full id>/endpoints} answers 200 for ids with
 * {@code :free}, with {@code :batch} and with a leading {@code ~}, so the slash, colon and tilde are
 * left exactly as the gateway wrote them. Only what a URI path cannot hold is escaped.
 */
public final class ModelEndpointPath {

    private ModelEndpointPath() {}

    /**
     * The slug to put in the path: the gateway's own canonical one when it published it.
     *
     * <p>{@code canonical_slug} is the dated, unambiguous name ({@code qwen/qwen3.5-35b-a3b-20260224}
     * for {@code qwen/qwen3.5-35b-a3b}), and it is what the entry's own {@code links.details} uses.
     * Preferring it means an alias resolved through this path answers about the model it names.
     */
    public static String slugOf(JsonNode entry, String id) {
        var canonical = entry == null ? "" : entry.path("canonical_slug").asString("").trim();
        return canonical.isEmpty() ? id : canonical;
    }

    /** @return blank when the gateway publishes no such path */
    public static String of(String template, String slug) {
        if (template == null || template.isBlank() || slug == null || slug.isBlank()) {
            return "";
        }
        return template.replace("{slug}", encode(slug));
    }

    /**
     * Escapes only what a URI path segment cannot carry.
     *
     * <p>Not {@code URLEncoder}: that is the form-body encoder, and it turns a space into {@code +}
     * and every {@code /} into {@code %2F} — which is how a perfectly good model id becomes a 404
     * against a gateway that was happy to receive it verbatim.
     */
    private static String encode(String slug) {
        var out = new StringBuilder(slug.length());
        for (var character : slug.toCharArray()) {
            if (character <= 0x20 || character == '#' || character == '?' || character == '%'
                    || character == 0x7F) {
                out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", (int) character));
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }
}
