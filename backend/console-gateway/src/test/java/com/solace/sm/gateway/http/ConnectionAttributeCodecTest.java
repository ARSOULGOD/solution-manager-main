package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T4 final-review fix wave ({@code BE-D66}): {@link ConnectionAttributeCodec#validateCreate}/
 * {@code validateUpdate} must reject malformed input with the feature's own {@code 422 VALIDATION_FAILED} shape
 * ({@link ConnectionValidationException}), never let it fall through to {@code Map.copyOf} and surface as an
 * uncaught {@code NullPointerException}/{@code 500}.
 *
 * <p>Called directly against the codec (not through {@link CreateConnectionRequest}/{@link UpdateConnectionRequest})
 * because those wire records already reject a null-valued JSON attribute at deserialization via their own
 * {@code Map.copyOf} — a null cannot reach the codec through the real HTTP path today, but the codec must not NPE
 * on one regardless, since it is reachable by any other caller and that upstream filtering is incidental, not a
 * contract this class may rely on.
 */
class ConnectionAttributeCodecTest {

    private final ConnectionAttributeCodec codec = new ConnectionAttributeCodec(new ConnectionTypeCatalog());

    @Test
    void aNullNonSecretAttributeValueOnCreateIsRejectedAsValidationFailedNotAnUncaughtNullPointerException() {
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("host", "broker.example.com");
        submitted.put("port", null);
        submitted.put("vpn", "default");

        ConnectionValidationException refusal = assertThrows(ConnectionValidationException.class,
                () -> codec.validateCreate("solace-broker", submitted));

        assertEquals("attributes", refusal.field());
    }

    @Test
    void aNullNonSecretAttributeValueOnUpdateIsRejectedAsValidationFailedNotAnUncaughtNullPointerException() {
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("port", null);

        ConnectionValidationException refusal = assertThrows(ConnectionValidationException.class,
                () -> codec.validateUpdate("solace-broker", submitted));

        assertEquals("attributes", refusal.field());
    }
}
