package com.solace.sm.dp.api.fleet;

import java.util.Objects;

/**
 * An authorized member failed a semantic check ({@code 422}). Names the member's ordinal and the RULE from a closed set
 * - never an id, a generation or any value from the request ({@code I6}).
 */
public final class FleetValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The failed rule, with the fixed sentence the response carries. */
    public enum Rule {
        DUPLICATE_MEMBER("is named more than once"),
        INSTANCE_NOT_ELIGIBLE("is not an active instance of this solution"),
        PLACEMENT_NOT_LIVE("names no live placement of its instance"),
        GENERATION_NOT_STAGED("names a generation that was never staged for its instance"),
        /**
         * {@code OPS-Q8} K-2 (plan 012 T6, {@code BE-D50}): a {@code rolling-*} fleet batch holds two placements of one
         * instance, so the "rolling" restart would take the instance down in one step. {@code restart-all} and
         * {@code manual} are exempt. The only rule with a wire classifier.
         */
        BATCH_INSTANCE_REPEATED("shares its batch with another placement of its instance",
                "fleet.batch.instance.repeated");

        private final String sentence;
        private final String code;

        Rule(String sentence) {
            this(sentence, null);
        }

        Rule(String sentence, String code) {
            // the classifier is written once and the sentence is built from it, so the two cannot drift (W4 review W4B-4)
            this.sentence = code == null ? sentence : sentence + " (" + code + ")";
            this.code = code;
        }

        public String sentence() {
            return sentence;
        }

        /** The dotted classifier the contract documents for this rule, or {@code null} for a rule that has none. */
        public String code() {
            return code;
        }
    }

    private final int ordinal;
    private final transient Rule rule;

    public FleetValidationException(int ordinal, Rule rule) {
        super("member " + ordinal + " " + Objects.requireNonNull(rule, "rule").sentence());
        this.ordinal = ordinal;
        this.rule = rule;
    }

    public int ordinal() {
        return ordinal;
    }

    public Rule rule() {
        return rule;
    }
}
