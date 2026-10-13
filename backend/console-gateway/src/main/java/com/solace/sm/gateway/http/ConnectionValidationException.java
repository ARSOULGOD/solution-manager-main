package com.solace.sm.gateway.http;

import java.util.Objects;

/**
 * A Connection's {@code connectionType} or {@code attributes} failed validation against its descriptor (plan 013
 * T4, {@code UI-D27}): an unknown {@code connectionType}, an attribute key the descriptor does not declare, or a
 * required attribute left absent. {@code 422 VALIDATION_FAILED}. {@code field} is a FIXED identifier this module
 * chooses, never an echo of a caller-supplied key or value ({@code I6}-adjacent discipline: a submitted key could
 * itself be sensitive-looking text, so it is never reflected).
 */
public final class ConnectionValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public ConnectionValidationException(String field) {
        super(Objects.requireNonNull(field, "field"), null, false, false);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
