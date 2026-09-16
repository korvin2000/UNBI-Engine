package com.unbi.engine.llm.spec;

import java.util.List;
import java.util.Locale;

/**
 * Which of a gateway's upstream providers may serve this model, and on what terms.
 *
 * <p>This is a <em>correctness</em> setting, not a price one. One model id can be served by dozens
 * of hosts whose sampling support differs: most accept the request and quietly drop whatever they do
 * not implement, so an unhonoured {@code min_p} looks exactly like an honoured one — same status,
 * same text, same bill — and which happened depends on who had capacity that second.
 *
 * <p>{@code requireParameters} is what makes that audible: it tells the gateway to route only to a
 * provider supporting every field in the request, so a dropped parameter comes back as an error
 * instead of as a silently different sampler. Any target carrying more than a temperature wants it.
 *
 * <p>Every field is omitted from the wire when empty, and the whole block disappears when they all
 * are — an endpoint that has never heard of provider routing must not be sent an empty object.
 *
 * @param allowFallbacks null means "do not say"; false pins routing to {@code order}/{@code only}
 */
public record ProviderRouting(
        List<String> order,
        List<String> only,
        List<String> ignore,
        Boolean allowFallbacks,
        Boolean requireParameters,
        Sort sort) {

    public static final ProviderRouting NONE = new ProviderRouting(List.of(), List.of(), List.of(), null, null, null);

    public ProviderRouting {
        order = List.copyOf(order == null ? List.of() : order);
        only = List.copyOf(only == null ? List.of() : only);
        ignore = List.copyOf(ignore == null ? List.of() : ignore);
        // A slug that is both required and forbidden is a typo with a silent outcome: the gateway
        // resolves it one way and the configuration says the other.
        for (var slug : ignore) {
            if (order.contains(slug) || only.contains(slug)) {
                throw new IllegalArgumentException(
                        "Provider '" + slug + "' is both preferred and ignored; pick one");
            }
        }
    }

    public boolean isEmpty() {
        return order.isEmpty()
                && only.isEmpty()
                && ignore.isEmpty()
                && allowFallbacks == null
                && requireParameters == null
                && sort == null;
    }

    /** Parses the comma-separated form the node widgets use. */
    public static List<String> slugs(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(slug -> !slug.isEmpty())
                .toList();
    }

    public enum Sort {
        PRICE,
        THROUGHPUT,
        LATENCY;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** @return null for anything unrecognised, which the encoder reads as "do not say" */
        public static Sort of(String raw) {
            if (raw == null) {
                return null;
            }
            var trimmed = raw.trim().toLowerCase(Locale.ROOT);
            for (var value : values()) {
                if (value.wireName().equals(trimmed)) {
                    return value;
                }
            }
            return null;
        }
    }
}
