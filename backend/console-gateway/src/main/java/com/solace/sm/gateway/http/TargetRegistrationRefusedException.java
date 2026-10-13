package com.solace.sm.gateway.http;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A registration or a scan refused for a reason the caller can act on (plan 013 T1.4). One reason, one {@link ErrorCode},
 * one FIXED detail: nothing the request carried (a host, a name, a key, a fingerprint) and no id of another target is
 * ever echoed ({@code I6}, and the {@code 409}s would otherwise tell a caller what another target holds).
 *
 * <p>Every refusal that can be decided before a write is raised before it, so none of these leaves a row, a secret or an
 * event behind (D-REG section 8).
 */
public final class TargetRegistrationRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** A field name is a fixed identifier chosen in this module; the pattern is the proof nothing request-derived is in it. */
    static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9.]{0,63}");

    public enum Reason {
        CREDENTIAL_INVALID(ErrorCode.CREDENTIAL_INVALID,
                "the private key is not an unencrypted SSH private key that can be opened"),
        HOST_KEY_NOT_OFFERED(ErrorCode.HOST_KEY_NOT_OFFERED, "the host did not offer the pinned host key"),
        ADDRESS_REFUSED(ErrorCode.TARGET_ADDRESS_REFUSED,
                "the host's address is not one the data plane may connect to"),
        HOST_UNREACHABLE(ErrorCode.SERVICE_UNAVAILABLE,
                "the host could not be reached or presented no SSH host key; nothing was written, retry"),
        HOST_KEY_HELD(ErrorCode.CONFLICT, "a host key of this machine is registered to another target"),
        NAME_TAKEN(ErrorCode.CONFLICT, "a target with this name is already registered differently"),
        RECORD_DIFFERS(ErrorCode.CONFLICT,
                "this target is already registered with a different connection record"),
        KEY_DIFFERS(ErrorCode.CONFLICT,
                "this target is registered with a different private key; rotate its credential instead"),
        /** W3C-4: nothing could be compared, so neither "different key" nor "different record" is true. */
        CREDENTIAL_UNREADABLE(ErrorCode.CONFLICT,
                "this target's stored private key could not be opened to compare; rotate its credential to repair it"),
        /** W3C-4: a concurrent writer won twice; the caller's record is not what differs. */
        RECORD_CHANGED(ErrorCode.CONFLICT,
                "this target's connection record changed while it was being registered; retry"),
        /** Plan 013 T1.5 (OQ-12 (a)): an apply or a fleet member is in flight on a placement of the target. */
        WORK_IN_FLIGHT(ErrorCode.CONFLICT,
                "work is in flight on a placement of this target; let it finish, then edit the connection"),
        /** Plan 013 T1.5 (V007's retire guard, mapped). */
        LIVE_PLACEMENTS(ErrorCode.CONFLICT, "a target cannot be retired while it has live placements"),
        INVALID_FIELD(ErrorCode.VALIDATION_FAILED, null);

        private final ErrorCode code;
        private final String detail;

        Reason(ErrorCode code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        public ErrorCode code() {
            return code;
        }
    }

    private final Reason reason;
    private final String field;

    public TargetRegistrationRefusedException(Reason reason) {
        this(reason, null);
    }

    /** @param field for {@link Reason#INVALID_FIELD}: the fixed name of the field that failed, never its value */
    public TargetRegistrationRefusedException(Reason reason, String field) {
        super(Objects.requireNonNull(reason, "reason").name(), null, false, false);
        if (reason == Reason.INVALID_FIELD ? field == null || !FIELD.matcher(field).matches() : field != null) {
            throw new IllegalArgumentException("only an invalid-field refusal names a field, and by a fixed identifier");
        }
        this.reason = reason;
        this.field = field;
    }

    public Reason reason() {
        return reason;
    }

    /** The fixed detail text of the response body. */
    public String detail() {
        return reason == Reason.INVALID_FIELD ? "field '" + field + "' failed validation" : reason.detail;
    }
}
