package com.solace.sm.gateway.http;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An accept was refused as {@code 422 VALIDATION_FAILED} by the gateway's own composition (D-ACCEPT §3.7), after the host's
 * file was read and mapped. The detail names the FIELD and the RULE and never the value: a field key is the package's own
 * declaration and a rule code is a closed validation code, and anything else is dropped (the fixed-detail rule of
 * {@link ProblemBody}, {@code I6}).
 */
public final class AcceptRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** A package-declared field key as the contract spells it; anything else is never echoed. */
    private static final Pattern FIELD_KEY = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern RULE_CODE = Pattern.compile("[A-Z_]{1,40}");

    /** Why. */
    public enum Reason {
        /** No generation has been staged, so there is no selection to map the file against. */
        NO_GENERATION("the instance has no staged configuration to read the host's file against"),
        /** The mapped values do not pass the package's own blocking validations. */
        BLOCKING_VALIDATION("the host's file has a value the package refuses"),
        /** The mapping lost or changed a value: the server's own rendering differs from what the file holds. */
        ROUND_TRIP("the host's file does not round-trip through the package's rendering");

        private final String detail;

        Reason(String detail) {
            this.detail = detail;
        }
    }

    /** One field and the rule it broke. */
    public record FieldRule(String fieldKey, String rule) {
    }

    private final transient Reason reason;
    private final transient List<FieldRule> fields;

    public AcceptRefusedException(Reason reason, List<FieldRule> fields) {
        super("accept refused: " + Objects.requireNonNull(reason, "reason"), null, true, false);
        this.reason = reason;
        this.fields = List.copyOf(fields);
    }

    public AcceptRefusedException(Reason reason) {
        this(reason, List.of());
    }

    /** Fixed text plus at most five {@code key (RULE)} pairs, each shaped by a closed grammar. */
    public String detail() {
        StringBuilder out = new StringBuilder(reason.detail);
        int named = 0;
        for (FieldRule field : fields) {
            if (named == 5) {
                break;
            }
            if (!FIELD_KEY.matcher(field.fieldKey()).matches() || !RULE_CODE.matcher(field.rule()).matches()) {
                continue;
            }
            out.append(named++ == 0 ? ": " : ", ").append(field.fieldKey()).append(" (").append(field.rule()).append(')');
        }
        return out.toString();
    }
}
