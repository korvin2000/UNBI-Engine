package com.unbi.engine.llm.spec;

import java.util.Locale;

/**
 * Whether the model should think first, how hard, and in whose spelling.
 *
 * <p>The dialect and the switch are separate fields because they answer different questions.
 * {@link Dialect#NONE} means <em>send no reasoning parameter at all</em>, leaving whatever the model
 * does by default. Any other dialect states the intent in both directions — and stating it is the
 * only way to stop a model that reasons unless told otherwise, whose thinking tokens bill at the
 * output rate.
 *
 * <p>{@code exclude} hides the traces from the answer. It does not make them free; reading it as
 * "off" is the measured trap.
 *
 * @param maxTokens upper bound on thinking tokens; 0 means "do not say"
 */
public record Reasoning(Dialect dialect, boolean enabled, Effort effort, int maxTokens, boolean exclude) {

    /** Say nothing about reasoning; the model keeps its own default. */
    public static final Reasoning UNSPECIFIED = new Reasoning(Dialect.NONE, false, Effort.MEDIUM, 0, true);

    public Reasoning {
        if (dialect == null) {
            dialect = Dialect.NONE;
        }
        if (effort == null) {
            effort = Effort.MEDIUM;
        }
        if (maxTokens < 0) {
            maxTokens = 0;
        }
    }

    /** True when this says anything at all about reasoning. */
    public boolean speaks() {
        return dialect != Dialect.NONE;
    }

    public enum Dialect {
        /** Say nothing; the model keeps its own default. */
        NONE("none"),
        /** Flat {@code reasoning_effort: low|medium|high|none}. */
        REASONING_EFFORT("reasoning_effort"),
        /** Nested {@code reasoning: { effort, max_tokens, exclude }}. */
        REASONING("reasoning"),
        /** Budgeted {@code thinking: { type, budget_tokens }}. */
        THINKING("thinking");

        private final String wireName;

        Dialect(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Dialect of(String raw) {
            if (raw == null) {
                return NONE;
            }
            var trimmed = raw.trim().toLowerCase(Locale.ROOT);
            for (var value : values()) {
                if (value.wireName.equals(trimmed)) {
                    return value;
                }
            }
            return NONE;
        }
    }

    /**
     * How hard to think, in the gateways' own vocabulary.
     *
     * <p>Six tiers rather than four because the gateways measured for this pack offer six —
     * {@code effort_tiers: [low, medium, high, xhigh, max, ultra]} came off a live listing. A model
     * discovery that offered a tier this enum then quietly folded back to medium would be the exact
     * "configured but not sent" failure the whole pack is built to make impossible.
     *
     * <p>The budgets are for the dialects that want a token count instead of a word, and they only
     * have to be ordered and plausible — no gateway is told these numbers when it asked for a word.
     */
    public enum Effort {
        MINIMAL(1024),
        LOW(2048),
        MEDIUM(8192),
        HIGH(16384),
        XHIGH(32768),
        MAX(65536),
        ULTRA(131072);

        private final int budget;

        Effort(int budget) {
            this.budget = budget;
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** What an effort level means to a dialect that wants a token budget instead. */
        public int budgetTokens() {
            return budget;
        }

        public static Effort of(String raw) {
            if (raw == null) {
                return MEDIUM;
            }
            var trimmed = raw.trim().toLowerCase(Locale.ROOT);
            for (var value : values()) {
                if (value.wireName().equals(trimmed)) {
                    return value;
                }
            }
            return MEDIUM;
        }
    }
}
