package com.unbi.engine.llm.discovery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import tools.jackson.databind.JsonNode;

/**
 * Money, counts and per-token prices, as a person reads them.
 *
 * <p>Here rather than in the browser because the arithmetic is the part that can be wrong. A gateway
 * quotes {@code "0.0000001625"} per token; what the user came to see is {@code $0.1625 per M}, and a
 * second implementation of that multiplication in TypeScript is a second place for it to drift — the
 * backend already parsed the body, so the backend says what it means.
 *
 * <p>The blank string is a value here, not an omission: every formatter answers {@code ""} for an
 * absent number, because a display row saying {@code $0.00} for a price the gateway never published
 * is the invented fact this whole layer refuses to produce.
 */
public final class Amounts {

    /** Past this, more digits are noise: no gateway quotes a per-million price to seven places. */
    private static final int MAX_DECIMALS = 6;

    private Amounts() {}

    /**
     * A dollar amount, with thousands separators and as many decimals as the figure needs.
     *
     * <p>Two for anything a dollar or more, because that is the unit money is read in: a live
     * balance of {@code 44.545926} is {@code $44.55}, and six decimals on it make a panel look like
     * a debugger. Up to six below a dollar, because there the cap would round away the answer — a
     * per-million price of {@code $0.1625} shown as {@code $0.16} is a 1.5% lie about what a batch
     * will cost, and a day's usage of {@code $0.000362} shown as {@code $0.00} says nothing was
     * spent when something was.
     *
     * <p>Rounded half-up rather than down, so a balance shown beside the sum that produced it still
     * adds up — which is the only reason to show that sum at all.
     */
    public static String money(Double amount) {
        if (amount == null) {
            return "";
        }
        var decimals = 2;
        if (Math.abs(amount) < 1) {
            var trimmed = BigDecimal.valueOf(amount)
                    .setScale(MAX_DECIMALS, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            decimals = Math.clamp(trimmed.scale(), 2, MAX_DECIMALS);
        }
        return "$" + String.format(Locale.ROOT, "%,." + decimals + "f", amount);
    }

    /** A whole number with thousands separators — {@code 1,048,576} rather than {@code 1048576}. */
    public static String count(Integer value) {
        return value == null ? "" : String.format(Locale.ROOT, "%,d", value);
    }

    public static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * A per-token price as this engine's per-million double.
     *
     * <p>Accepts a number or a decimal string, because OpenRouter sends strings and llama.cpp sends
     * nothing at all. Null for anything unreadable: "not published" and "free" are different facts
     * and only one of them is a zero.
     */
    public static Double perMillion(JsonNode perToken) {
        if (perToken == null || perToken.isMissingNode() || perToken.isNull()) {
            return null;
        }
        try {
            var value = perToken.isNumber()
                    ? perToken.asDouble()
                    : Double.parseDouble(perToken.asString("").trim());
            return value < 0 ? null : value * 1_000_000d;
        } catch (NumberFormatException | NullPointerException notANumber) {
            return null;
        }
    }

    /** A per-token price straight to text: {@code $0.20}, or blank when it was not published. */
    public static String perMillionText(JsonNode perToken) {
        return money(perMillion(perToken));
    }

    /** The pair a call is billed on, in the order it is billed: {@code $0.15 in / $0.60 out per M}. */
    public static String pricePair(Double inputPer1M, Double outputPer1M) {
        if (inputPer1M == null && outputPer1M == null) {
            return "";
        }
        return "%s in / %s out per M".formatted(moneyOrFree(inputPer1M), moneyOrFree(outputPer1M));
    }

    /** A zero price is free and says so; an absent one stays absent. */
    public static String moneyOrFree(Double amount) {
        if (amount == null) {
            return "—";
        }
        return amount == 0 ? "free" : money(amount);
    }
}
