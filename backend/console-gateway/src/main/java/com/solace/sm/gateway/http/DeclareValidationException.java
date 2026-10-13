package com.solace.sm.gateway.http;

import java.util.regex.Pattern;

/**
 * A declare refused as {@code 422 VALIDATION_FAILED}, naming the FIELD and never the value (console.yaml:
 * "names the field. Never echoes the value"). The field is a fixed identifier chosen in this module; the pattern is
 * the proof that nothing request-derived can reach the response body ({@code I6}).
 */
public final class DeclareValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9.]{0,63}");

    private final String field;

    public DeclareValidationException(String field) {
        super("declare refused: field failed validation");
        if (!FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("a field name is a fixed identifier");
        }
        this.field = field;
    }

    public String field() {
        return field;
    }
}
