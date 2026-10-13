package com.solace.sm.dp.api.inventory;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where a change to a target's connection record came from, recorded with it in {@code target_connection_event}
 * (plan 013 T1.3/T1.4, {@code BE-D56}): the request's correlation id, so an event joins to the HTTP response that caused
 * it, and one short client-address token. {@code dp-api} cannot name {@code cp-api}'s {@code RequestOrigin} ({@code I7}),
 * so the gateway translates.
 *
 * <p>Both values are the server's own (the gateway's {@code RequestOriginFactory}); neither is client-supplied. The
 * address grammar is the table's CHECK, stated once more so a bad value is refused in code, before a write.
 */
public record TargetChangeOrigin(UUID correlationId, String clientAddress) {

    /** What the operator command records as the address of a change made on the plane's own machine. */
    public static final String OPERATOR_COMMAND_ADDRESS = "operator-cli";

    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9:._%-]{1,64}$");

    public TargetChangeOrigin {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(clientAddress, "clientAddress");
        if (!ADDRESS.matcher(clientAddress).matches()) {
            throw new IllegalArgumentException("a client address is one short token");
        }
    }

    /** A change made by the plane-local operator command: a fresh correlation id, and the fixed address token. */
    public static TargetChangeOrigin operatorCommand() {
        return new TargetChangeOrigin(UUID.randomUUID(), OPERATOR_COMMAND_ADDRESS);
    }
}
