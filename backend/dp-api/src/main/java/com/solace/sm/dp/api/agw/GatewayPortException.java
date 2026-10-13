package com.solace.sm.dp.api.agw;

/** Coded, transport-neutral refusal from a dp-api port. */
public final class GatewayPortException extends RuntimeException {
    private final GatewayCode code;

    public GatewayPortException(GatewayCode code, String message) {
        super(message);
        this.code = code;
    }

    public GatewayCode code() { return code; }
}
