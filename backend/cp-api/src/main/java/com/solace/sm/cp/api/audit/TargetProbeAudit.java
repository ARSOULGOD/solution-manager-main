package com.solace.sm.cp.api.audit;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.TenantId;
import java.util.UUID;

/**
 * The record that a host-key scan ran (plan 013 T1.4; D-REG section 9): a scan writes nothing to either store, so the
 * change-audit trigger never sees it, but it makes the data plane open connections to a host a caller named, so each
 * one leaves one audit row of the verb that authorized it ({@code target.create}), at the scope it was authorized at.
 *
 * <p>Written by the control plane for the gateway, which cannot reach {@code AuditLog} ({@code cp-core}, {@code OR-10});
 * the precedent is {@link ExportAudit}. ⛔ {@code I6}: nothing here takes a host, a port, an offered key or a fingerprint,
 * so there is nowhere for one to go: the row names the verb, the scope node, the outcome and the correlation id.
 *
 * <p>A scan that was refused before it ran ({@code 403}, {@code 404}, {@code 422} on the request's own fields) is not a
 * scan and is not recorded here; a scan the rate gate refused is ({@link ScanResult#RATE_LIMITED}), as a {@code DENY}
 * row, because it is the one refusal that says someone is probing.
 */
public interface TargetProbeAudit {

    /** How a scan ended. A closed set of fixed codes; none carries anything from the request. */
    enum ScanResult {
        /** The host answered and offered at least one key. */
        COMPLETED("completed", false),
        /** The host could not be reached or offered no key ({@code 503}). */
        UNREACHABLE("unreachable", false),
        /** The address policy refused the host ({@code 422 TARGET_ADDRESS_REFUSED}). */
        ADDRESS_REFUSED("address_refused", false),
        /** This session's scan budget was spent ({@code 429}); recorded as a {@code DENY}. */
        RATE_LIMITED("rate_limited", true);

        private final String code;
        private final boolean denied;

        ScanResult(String code, boolean denied) {
            this.code = code;
            this.denied = denied;
        }

        /** The fixed code recorded in the audit row. */
        public String code() {
            return code;
        }

        /** Whether the row is a {@code DENY} (a refusal) rather than an {@code ALLOW} that ended some way. */
        public boolean denied() {
            return denied;
        }
    }

    /**
     * @param scopeKind the kind of node {@code target.create} was evaluated at: an organization, a sub-organization or an
     *                  environment (never anything deeper: a target is owned at one of those three)
     * @param scopeId   that node's id, also the resource audited
     * @param origin    where the request came from; its correlation id joins the row to the HTTP response
     * @throws IllegalArgumentException {@code scopeKind} is not one of the three
     */
    void scanned(PrincipalRef actor, RequestOrigin origin, TenantId organization, ResourceKind scopeKind, UUID scopeId,
            ScanResult result);

    /** How a connection test ended (plan 013 T1.5): one fixed code per furthest step reached; none carries anything from the host. */
    enum TestResult {
        /** The host key matched the pin, the key authenticated, and Docker answered. */
        PASSED("passed", false),
        /** Authenticated, but {@code docker version} did not answer (a stopped daemon, a user outside the group). */
        DOCKER_UNAVAILABLE("docker_unavailable", false),
        /** The host key matched the pin, but the stored key was not accepted. */
        NOT_AUTHENTICATED("not_authenticated", false),
        /** The host was not reached, was refused by the address policy, or presented another key than the pin. */
        NOT_REACHED("not_reached", false),
        /**
         * The test was refused before any connection was made: this session's probe budget is spent, or a test of this
         * target is already running ({@code 429}; review {@code W4C-4}). Recorded as a {@code DENY}.
         */
        RATE_LIMITED("rate_limited", true);

        private final String code;
        private final boolean denied;

        TestResult(String code, boolean denied) {
            this.code = code;
            this.denied = denied;
        }

        /** The fixed code recorded in the audit row. */
        public String code() {
            return code;
        }

        /** Whether the row is a {@code DENY} (a refusal) rather than an {@code ALLOW} that ended some way. */
        public boolean denied() {
            return denied;
        }
    }

    /**
     * Records that a target's connection was tested (plan 013 T1.5; D-REG section 9): the test writes nothing, but it makes
     * the data plane connect to a host and run a command, so each one leaves one {@code ALLOW} row of {@code target.use} on
     * the target, with the correlation id and the furthest step reached. ⛔ {@code I6}: no host, port, fingerprint, Docker
     * version or message can reach the row; its port has no parameter for one.
     */
    void tested(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID targetId, TestResult result);

    /**
     * Records that {@code editTargetConnection} scanned a host (plan 013 T1.5): an edit of the host, port or pin scans the
     * new machine, exactly as a registration does, so it draws on the same per-session budget and leaves the same kind of
     * row, but of the verb that authorized it ({@code target.edit}) on the target it was authorized at. Same fixed
     * outcomes, same I6 rule: nothing about the host can reach the row.
     */
    void rescanned(PrincipalRef actor, RequestOrigin origin, TenantId organization, UUID targetId, ScanResult result);
}
