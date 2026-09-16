package com.unbi.engine.llm.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** The arithmetic and the formatting that would otherwise be done twice, in two languages. */
class AmountsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("a balance reads as a balance, and a per-million price keeps its precision")
    void moneyPicksItsDecimals() {
        assertThat(Amounts.money(50d)).isEqualTo("$50.00");
        assertThat(Amounts.money(37.66)).isEqualTo("$37.66");
        assertThat(Amounts.money(0.6)).isEqualTo("$0.60");
        assertThat(Amounts.money(0.1625)).isEqualTo("$0.1625");
        assertThat(Amounts.money(1234.5)).isEqualTo("$1,234.50");
        assertThat(Amounts.money(0d)).isEqualTo("$0.00");
    }

    @Test
    @DisplayName("cents above a dollar, precision below one — the cap would round away the answer")
    void decimalsFollowTheMagnitude() {
        // Both figures are live: an OpenRouter balance and the same account's usage for the day.
        assertThat(Amounts.money(44.545926)).isEqualTo("$44.55");
        assertThat(Amounts.money(0.000362)).isEqualTo("$0.000362");
        // Half-up, so a balance shown beside the sum that produced it still adds up.
        assertThat(Amounts.money(80d - 35.454074)).isEqualTo("$44.55");
        assertThat(Amounts.money(35.454074)).isEqualTo("$35.45");
    }

    @Test
    @DisplayName("an absent figure is blank, never a zero — a zero is a price and blank is silence")
    void absentIsNotZero() {
        assertThat(Amounts.money(null)).isEmpty();
        assertThat(Amounts.count((Integer) null)).isEmpty();
        assertThat(Amounts.perMillionText(MAPPER.readTree("{}").path("prompt"))).isEmpty();
        assertThat(Amounts.moneyOrFree(null)).isEqualTo("—");
        assertThat(Amounts.moneyOrFree(0d)).isEqualTo("free");
    }

    @Test
    void countsCarryThousandsSeparators() {
        assertThat(Amounts.count(1_048_576)).isEqualTo("1,048,576");
        assertThat(Amounts.count(443L)).isEqualTo("443");
    }

    @Test
    @DisplayName("a decimal string per token becomes a double per million")
    void perTokenStringsBecomePerMillion() {
        var pricing = MAPPER.readTree(
                "{\"prompt\":\"0.0000001625\",\"completion\":0.0000013,\"image\":\"-1\"}");

        assertThat(Amounts.perMillion(pricing.path("prompt"))).isEqualTo(0.1625);
        assertThat(Amounts.perMillion(pricing.path("completion"))).isEqualTo(1.3);
        // A negative price is not a price; the gateway uses -1 for "ask elsewhere".
        assertThat(Amounts.perMillion(pricing.path("image"))).isNull();
        assertThat(Amounts.pricePair(0.1625, 1.3)).isEqualTo("$0.1625 in / $1.30 out per M");
    }

    @Test
    @DisplayName("a spent budget refuses the next call rather than starting one that cannot finish")
    void anExhaustedBudgetSaysSo() {
        assertThat(new TimeBudget(Duration.ZERO).isExhausted()).isTrue();
        assertThat(new TimeBudget(Duration.ZERO).remaining()).isEqualTo(Duration.ZERO);
        assertThat(new TimeBudget(Duration.ofSeconds(30)).isExhausted()).isFalse();
    }
}
