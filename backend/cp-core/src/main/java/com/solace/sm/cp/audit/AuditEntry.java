package com.solace.sm.cp.audit;

import com.solace.sm.cp.api.authz.ConsequenceClass;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.TenantId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** An immutable, secret-free control-plane audit record. */
public record AuditEntry(Instant at, PrincipalRef actor, Permission verb, ResourceKind resourceKind,
                         UUID resourceId, TenantId tenant, Outcome outcome, Map<String, Object> detail) {

    private static final Pattern REASON_CODE = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern STATUS = Pattern.compile("[a-z][a-z_]{0,63}");

    public enum Outcome { ALLOW, DENY }

    public AuditEntry {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(verb, "verb");
        Objects.requireNonNull(resourceKind, "resourceKind");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(outcome, "outcome");
        detail = validatedDetail(detail);
    }

    /**
     * Classifies a verb without collapsing {@code UNDECIDED} into {@code STANDARD}.
     *
     * @return the high-consequence kind, or {@code null} when a standard verb requires no row
     */
    public static AuditKind kindFor(Permission verb) {
        Objects.requireNonNull(verb, "verb");
        return kindFor(verb.consequence());
    }

    static AuditKind kindFor(ConsequenceClass consequence) {
        Objects.requireNonNull(consequence, "consequence");
        return switch (consequence) {
            case HIGH -> AuditKind.HIGH_CONSEQUENCE;
            case STANDARD -> null;
            case UNDECIDED -> throw new ConsequenceClassUndecidedException();
        };
    }

    private static Map<String, Object> validatedDetail(Map<String, Object> detail) {
        Objects.requireNonNull(detail, "detail");
        Map<String, Object> copy = new LinkedHashMap<>();
        detail.forEach((key, value) -> copy.put(requireSafeKey(key), requireSafeValue(key, value)));
        return Map.copyOf(copy);
    }

    private static String requireSafeKey(String key) {
        Objects.requireNonNull(key, "audit detail key");
        if (!key.equals("reason_code") && !key.equals("request_id") && !key.equals("ticket_id")
                && !key.equals("grant_id") && !key.equals("correlation_id") && !key.equals("count")
                && !key.equals("previous_status") && !key.equals("new_status")) {
            throw new IllegalArgumentException("audit detail field is not allowlisted: " + key);
        }
        return key;
    }

    private static Object requireSafeValue(String key, Object value) {
        if (value instanceof Map<?, ?> || value instanceof Iterable<?> || value != null && value.getClass().isArray()) {
            throw new IllegalArgumentException("audit detail field must be a flat scalar: " + key);
        }
        return switch (key) {
            case "reason_code" -> requirePattern(key, value, REASON_CODE);
            case "previous_status", "new_status" -> requirePattern(key, value, STATUS);
            case "request_id", "ticket_id", "grant_id", "correlation_id" -> requireUuid(key, value);
            case "count" -> requireCount(key, value);
            default -> throw new IllegalStateException("unrecognized allowlisted audit detail field: " + key);
        };
    }

    private static String requirePattern(String key, Object value, Pattern pattern) {
        if (!(value instanceof String string) || !pattern.matcher(string).matches()) {
            throw new IllegalArgumentException("audit detail field has an invalid value: " + key);
        }
        return string;
    }

    private static UUID requireUuid(String key, Object value) {
        if (!(value instanceof UUID uuid)) {
            throw new IllegalArgumentException("audit detail field must be a UUID: " + key);
        }
        return uuid;
    }

    private static Long requireCount(String key, Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException("audit detail field must be a non-negative integer: " + key);
        }
        return number.longValue();
    }
}
