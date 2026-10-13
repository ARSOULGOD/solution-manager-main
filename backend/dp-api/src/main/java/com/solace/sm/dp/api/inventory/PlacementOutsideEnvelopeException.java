package com.solace.sm.dp.api.inventory;

/** A placement field lies outside the target's registered envelope (B2). Names the FIELD, never the value. */
public final class PlacementOutsideEnvelopeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public PlacementOutsideEnvelopeException(String field) {
        super("placement field '" + field + "' is outside the target's envelope");
        this.field = field;
    }

    public String field() {
        return field;
    }
}
