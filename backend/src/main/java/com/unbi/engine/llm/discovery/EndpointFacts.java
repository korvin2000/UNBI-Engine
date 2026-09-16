package com.unbi.engine.llm.discovery;

import com.unbi.engine.llm.spec.Capability;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * What a gateway says about the key it was asked with, and about its own catalogue.
 *
 * <p>A pure function of the response bodies, like {@link ModelListingReader} and for the same
 * reason: "what did the gateway publish, and what does that render as" is a mapping worth testing
 * without a socket, and it is the mapping most likely to be quietly wrong.
 *
 * <p>Everything is optional in the honest sense. Read by field name rather than by gateway, so a
 * body that happens to say what is left of a quota reports it and one that does not leaves the
 * corresponding row blank — which the editor draws as nothing at all rather than as a zero. On a
 * gateway whose credential probe <em>is</em> the model listing, that means the whole key half of
 * this record is blank, and the catalogue half is complete.
 *
 * @param keyLabel            the key's own name at the gateway, blank when none was published, and
 *     {@code unnamed key} when the gateway named the key after itself — a label that <em>is</em> a
 *     truncated key is key-shaped text, and a row is saved into the workflow
 * @param limit               the key's spending cap; null means the gateway stated none
 * @param limitReset          how often that cap refills, in the gateway's words; blank when none
 *     was published
 * @param managementKey       whether this key may read account-wide data. Kept because it is the
 *     reason a balance 403s, and "your key is not a management key" is the actionable half of that —
 *     which is what the balance row says when this is false and the refusal was about the key
 * @param includeByokInLimit  whether spend through your own upstream keys counts against that cap
 * @param modelsServed        how many models the listing says are served, or null when unasked
 * @param capabilityCounts    how many of those models declare each capability
 * @param inputModalities     every input modality seen anywhere in the listing
 * @param modelIds            every id in the listing, for the node's list output
 * @param totalCredits        bought credit, from the balance call
 * @param totalUsage          spent credit, from the balance call
 * @param balanceProblem      why the balance is not readable, in the user's words; blank when it is
 */
public record EndpointFacts(
        String keyLabel,
        Double limit,
        Double limitRemaining,
        String limitReset,
        Double usage,
        Double usageDaily,
        Double usageWeekly,
        Double usageMonthly,
        Double byokUsage,
        Boolean freeTier,
        Boolean managementKey,
        Boolean includeByokInLimit,
        String expiresAt,
        Integer freeRequestsUsed,
        Integer freeRequestsLimit,
        Integer modelsServed,
        Map<Capability, Integer> capabilityCounts,
        List<String> inputModalities,
        List<String> modelIds,
        Double totalCredits,
        Double totalUsage,
        String balanceProblem) {

    /**
     * A key label that is a truncated form of the key itself.
     *
     * <p>OpenRouter names an unnamed key after the key — {@code sk-or-v1-0ca...618}. That is already
     * the gateway's own redaction, but it is key-shaped text, and a display row is a widget value:
     * it is persisted in the saved workflow and travels with the file. Key-shaped text does not
     * belong in one, so it is replaced before it can reach a row, a struct or a summary.
     *
     * <p>Two signals are required — a known secret prefix <em>and</em> the truncating ellipsis —
     * because a prefix alone also matches ordinary human labels like {@code api-team} or
     * {@code sk_prod-shared}, and masking a real key name would empty out the one row whose job is
     * to say which key answered.
     */
    private static final Pattern TRUNCATED_KEY = Pattern.compile(
            "(?i)^(sk|pk)[-_][a-z0-9-]*\\w{2,}\\s*(\\.\\.\\.|…)\\s*\\w{2,}$");

    public EndpointFacts {
        keyLabel = keyLabel == null ? "" : keyLabel;
        limitReset = limitReset == null ? "" : limitReset;
        expiresAt = expiresAt == null ? "" : expiresAt;
        capabilityCounts = Map.copyOf(capabilityCounts == null ? Map.of() : capabilityCounts);
        inputModalities = List.copyOf(inputModalities == null ? List.of() : inputModalities);
        modelIds = List.copyOf(modelIds == null ? List.of() : modelIds);
        balanceProblem = balanceProblem == null ? "" : balanceProblem;
    }

    /**
     * Reads whatever answered.
     *
     * @param keyBody     the credential probe's answer, or null when it failed. On a gateway whose
     *     probe path is the model listing this is that listing, and nothing in it matches a key
     *     field — which is the correct outcome, not a case to special-case.
     * @param creditsBody the balance call's answer, or null
     * @param creditsProblem why there is no balance body; blank when there is one
     * @param modelsBody  the model listing, or null when it was not asked or failed
     */
    public static EndpointFacts read(
            JsonNode keyBody, JsonNode creditsBody, String creditsProblem, JsonNode modelsBody) {

        var key = data(keyBody);
        var credits = data(creditsBody);
        var models = modelsBody == null ? List.<DiscoveredModel>of() : ModelListingReader.read(modelsBody);

        return new EndpointFacts(
                label(key),
                number(key, "limit"),
                number(key, "limit_remaining"),
                text(key, "limit_reset"),
                number(key, "usage"),
                number(key, "usage_daily"),
                number(key, "usage_weekly"),
                number(key, "usage_monthly"),
                number(key, "byok_usage"),
                bool(key, "is_free_tier"),
                bool(key, "is_management_key"),
                bool(key, "include_byok_in_limit"),
                text(key, "expires_at"),
                integer(key.path("free_model_daily_requests"), "used"),
                integer(key.path("free_model_daily_requests"), "limit"),
                modelsBody == null ? null : ModelListingReader.count(modelsBody),
                counts(models),
                ModelListingReader.inputModalities(modelsBody),
                models.stream().map(DiscoveredModel::id).toList(),
                number(credits, "total_credits"),
                number(credits, "total_usage"),
                creditsProblem);
    }

    // --- Rows, already formatted ----------------------------------------------

    /** {@code $37.66 of $50.00}, or {@code no limit} when the gateway said there is none. */
    public String creditsRemaining() {
        if (keyLabel.isEmpty() && limit == null && limitRemaining == null) {
            return "";
        }
        if (limit == null) {
            return "no limit";
        }
        return "%s of %s".formatted(Amounts.money(limitRemaining), Amounts.money(limit));
    }

    /** {@code 3 of 200 used}, the way a quota is read out loud. */
    public String freeRequests() {
        if (freeRequestsUsed == null || freeRequestsLimit == null) {
            return "";
        }
        return "%s of %s used".formatted(Amounts.count(freeRequestsUsed), Amounts.count(freeRequestsLimit));
    }

    /**
     * The expiry as a plain date — {@code 2026-12-31}.
     *
     * <p>A date rather than the instant the gateway published, because the editor draws any
     * ISO-8601 instant in a {@code line} row as relative time, and every future instant is under
     * 45 seconds old by that arithmetic. A key expiring in three months read as "just now" is the
     * opposite of the warning this row exists to give. Unparseable text is shown as it arrived,
     * which is still more than blanking the row would say.
     */
    public String expires() {
        if (expiresAt.isBlank()) {
            return "";
        }
        try {
            return OffsetDateTime.parse(expiresAt).atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString();
        } catch (DateTimeParseException notAnInstant) {
            return expiresAt;
        }
    }

    /**
     * The balance with its arithmetic shown: {@code $37.66 = $50.00 credits − $12.34 used}.
     *
     * <p>Shown rather than just the figure, because a balance is the one number a user arrives
     * already doubting, and two numbers they can check against their own records settles it.
     */
    public String balance() {
        if (totalCredits == null && totalUsage == null) {
            return "";
        }
        var bought = totalCredits == null ? 0d : totalCredits;
        var spent = totalUsage == null ? 0d : totalUsage;
        return "%s = %s credits − %s used"
                .formatted(Amounts.money(bought - spent), Amounts.money(bought), Amounts.money(spent));
    }

    /** How many models declare one capability, blank when no listing was read. */
    public String capabilityCount(Capability capability) {
        if (modelsServed == null) {
            return "";
        }
        return Amounts.count(capabilityCounts.getOrDefault(capability, 0));
    }

    public String modelsServedText() {
        return modelsServed == null ? "" : Amounts.count(modelsServed);
    }

    private static Map<Capability, Integer> counts(List<DiscoveredModel> models) {
        var counts = new EnumMap<Capability, Integer>(Capability.class);
        for (var model : models) {
            for (var capability : model.capabilities()) {
                counts.merge(capability, 1, Integer::sum);
            }
        }
        return counts;
    }

    // --- Reading, defensively -------------------------------------------------

    /** Gateways wrap an answer in {@code data}; some do not. Both, without a branch per gateway. */
    private static JsonNode data(JsonNode body) {
        if (body == null) {
            return tools.jackson.databind.node.MissingNode.getInstance();
        }
        return body.path("data").isObject() ? body.path("data") : body;
    }

    /** The key's name, unless the name is the key. */
    private static String label(JsonNode key) {
        var label = text(key, "label").strip();
        return TRUNCATED_KEY.matcher(label).matches() ? "unnamed key" : label;
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isString() ? value.asString("") : "";
    }

    private static Double number(JsonNode node, String field) {
        var value = node.path(field);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static Integer integer(JsonNode node, String field) {
        var value = node.path(field);
        return value.isNumber() ? value.asInt(0) : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        var value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : null;
    }
}
