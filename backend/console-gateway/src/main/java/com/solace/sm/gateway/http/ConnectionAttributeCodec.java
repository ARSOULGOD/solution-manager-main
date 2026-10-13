package com.solace.sm.gateway.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Validates a Connection's {@code attributes} against its connection type's descriptor and splits a secret-typed
 * value out of the request before it ever reaches {@code cp-core} (plan 013 T4, {@code BE-D66}, {@code UI-D27}):
 * {@code ConnectionRegistry}'s own javadoc makes that split THIS layer's job, not the control plane's.
 *
 * <p>⛔ {@code I6}: {@link Split#secretPlaintext()} holds exactly what the caller submitted for a secret-typed key,
 * in memory only, for {@link ConnectionOrchestrator} to seal and then discard; it is never put in
 * {@link Split#nonSecretAttributes()}, which is what reaches {@code cp-core} and is echoed back on a failed test.
 *
 * <p>Validation here is deliberately bounded: unknown keys and missing REQUIRED keys (honoring a discriminator's
 * {@code when} gate) are refused; per-type value shape (min/max/pattern/options) is left to the connection type's
 * own test — a wrong value fails {@code testBeforeSave} or is simply stored as submitted when untested, exactly as
 * the brief's two known descriptors (no enum/range caller input exists yet that this would catch sooner).
 */
@Component
public final class ConnectionAttributeCodec {

    private final ConnectionTypeCatalog catalog;

    public ConnectionAttributeCodec(ConnectionTypeCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    /** The secret-typed and non-secret attributes split out of ONE submission (not a merge). */
    public record Split(Map<String, Object> nonSecretAttributes, Map<String, Boolean> secretStatus,
                        Map<String, String> secretPlaintext) {
    }

    /**
     * {@code createConnection}: every attribute the descriptor requires (respecting a discriminator's {@code when}
     * gate, evaluated against the SUBMITTED values) must be present.
     *
     * @throws ConnectionValidationException an unknown {@code connectionType}, an unknown attribute key, or a
     *                                        missing required one
     */
    public Split validateCreate(String connectionType, Map<String, Object> submitted) {
        ConnectionType descriptor = descriptorOf(connectionType);
        Split split = split(descriptor, submitted);
        for (ConnectionTypeAttribute attribute : descriptor.attributes()) {
            if (!applies(attribute, submitted)) {
                continue;
            }
            boolean present = attribute.isSecret() ? split.secretPlaintext().containsKey(attribute.key())
                    : submitted.containsKey(attribute.key());
            if (attribute.isRequired() && !present) {
                throw new ConnectionValidationException("attributes");
            }
        }
        return split;
    }

    /**
     * {@code updateConnection}: only the submitted keys are validated (unknown-key check); whether the result is
     * complete once merged over the existing record is {@code ConnectionService}'s merge, not re-checked here —
     * the connection was valid before the merge and a partial update narrows, never widens, what changes.
     *
     * @throws ConnectionValidationException an unknown attribute key
     */
    public Split validateUpdate(String connectionType, Map<String, Object> submittedOverlay) {
        ConnectionType descriptor = descriptorOf(connectionType);
        return split(descriptor, submittedOverlay);
    }

    /** Whether {@code connectionType} is one the catalog knows. */
    public boolean knows(String connectionType) {
        return catalog.byConnectionType(connectionType).isPresent();
    }

    private ConnectionType descriptorOf(String connectionType) {
        return catalog.byConnectionType(connectionType)
                .orElseThrow(() -> new ConnectionValidationException("connectionType"));
    }

    private static Split split(ConnectionType descriptor, Map<String, Object> submitted) {
        Map<String, ConnectionTypeAttribute> byKey = new LinkedHashMap<>();
        descriptor.attributes().forEach(a -> byKey.put(a.key(), a));

        Map<String, Object> nonSecret = new LinkedHashMap<>();
        Map<String, Boolean> secretStatus = new LinkedHashMap<>();
        Map<String, String> secretPlaintext = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : submitted.entrySet()) {
            ConnectionTypeAttribute attribute = byKey.get(entry.getKey());
            if (attribute == null) {
                throw new ConnectionValidationException("attributes");
            }
            if (attribute.isSecret()) {
                if (entry.getValue() != null) {
                    if (!(entry.getValue() instanceof String text) || text.isEmpty()) {
                        throw new ConnectionValidationException("attributes");
                    }
                    secretPlaintext.put(attribute.key(), text);
                    secretStatus.put(attribute.key(), true);
                }
            } else {
                if (entry.getValue() == null) {
                    // Fix round 2, item 4: a null non-secret value must be refused with the same 422
                    // VALIDATION_FAILED shape as every other rejected attribute, not reach Map.copyOf (which
                    // disallows null values) and surface as an uncaught NullPointerException/500.
                    throw new ConnectionValidationException("attributes");
                }
                nonSecret.put(attribute.key(), entry.getValue());
            }
        }
        return new Split(Map.copyOf(nonSecret), Map.copyOf(secretStatus), Map.copyOf(secretPlaintext));
    }

    /** Whether {@code attribute} is in play given its {@code when} gate, evaluated against {@code submitted}. */
    private static boolean applies(ConnectionTypeAttribute attribute, Map<String, Object> submitted) {
        if (attribute.when() == null || attribute.when().isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> gate : attribute.when().entrySet()) {
            Object actual = submitted.get(gate.getKey());
            if (actual == null || !actual.equals(gate.getValue())) {
                return false;
            }
        }
        return true;
    }
}
