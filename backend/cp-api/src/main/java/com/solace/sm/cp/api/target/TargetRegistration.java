package com.solace.sm.cp.api.target;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Write side of target governance: the provisioning path for a Target (plan 010 T2.7). Two callers: the plane-local
 * operator command, and (plan 013 T1.4) the gateway's {@code registerTarget}, which composes it with the data plane's
 * pre-check and connection record.
 */
public interface TargetRegistration {

    /**
     * Registers a target, or returns the existing one when the same registration is repeated (idempotent on the
     * organization and the name).
     *
     * <p>The caller must hold {@code target.create} at {@code request.ownershipScope()}; the check is a verb at a
     * scope, never a role (OR-8), and runs before anything is written.
     *
     * @param organizationId the authenticated principal's own organization ({@code I5})
     * @throws com.solace.sm.cp.api.authz.AccessDeniedException the verb is not held at the scope
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException the scope does not exist, or is outside the
     *         organization
     * @throws IllegalArgumentException the request is malformed, or its scope is not an organization, an
     *         organization unit or an environment
     * @throws IllegalStateException the name (or the requested id) is already used by a different target
     */
    TargetView register(PrincipalRef actor, UUID organizationId, Request request, RequestOrigin origin);

    /** Updates non-secret metadata of a live linked deployment target. */
    default TargetView update(PrincipalRef actor, UUID organizationId, UUID targetId, UpdateRequest request,
            RequestOrigin origin) {
        throw new UnsupportedOperationException("target metadata update is not supported");
    }

    /**
     * Retires a target's governance row (plan 013 T1.5; D-REG section 5.1 T7, OQ-11 (b)): one audited write
     * ({@code TARGET_RETIRED}), after which {@code listLive} no longer lists it and its name is free for a new
     * registration. Retiring a retired target is an answer, not an error, and writes nothing. The data plane's connection
     * record is retired FIRST by the caller (a retired connection is unusable at once); this is the second step, and the
     * retry that completes an interrupted one.
     *
     * <p>The caller must hold {@code target.edit} at the target's own {@code ownershipScope}; the check runs before the row
     * is read. A target outside the organization is exactly as absent as an unknown one ({@code I5}).
     *
     * @throws com.solace.sm.cp.api.authz.AccessDeniedException the verb is not held at the target's scope
     * @throws com.solace.sm.cp.api.authz.ResourceNotFoundException the target does not exist in the organization
     */
    Retired retire(PrincipalRef actor, UUID organizationId, UUID targetId, RequestOrigin origin);

    /**
     * @param retiredAt        when the target was retired: now for this call, or the original time for a replay
     * @param alreadyRetired   whether it had been retired before this call
     */
    record Retired(TargetView view, Instant retiredAt, boolean alreadyRetired) {

        public Retired {
            Objects.requireNonNull(view, "view");
            Objects.requireNonNull(retiredAt, "retiredAt");
        }
    }

    /**
     * @param targetId optional; when null a random id is minted
     * @param ownershipScope where {@code target.view}/{@code target.use} are evaluated: organization, organization
     *        unit or environment (never solution, instance or install)
     */
    record Request(UUID targetId, String name, String kind, String transport, ScopePath ownershipScope,
            UUID connectionId, String runtime, String runtimeSettingsJson) {

        public Request(UUID targetId, String name, String kind, String transport, ScopePath ownershipScope) {
            this(targetId, name, kind, transport, ownershipScope, null, kind, "{}");
        }

        public Request {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(transport, "transport");
            Objects.requireNonNull(ownershipScope, "ownershipScope");
            if (runtime == null || runtime.isBlank()) {
                throw new IllegalArgumentException("runtime is required");
            }
            if (runtimeSettingsJson == null || runtimeSettingsJson.isBlank()) {
                throw new IllegalArgumentException("runtime settings are required");
            }
        }
    }

    /** Parent connection and runtime stay fixed after declaration; settings and the display name may change. */
    record UpdateRequest(String name, UUID connectionId, String runtime, String runtimeSettingsJson) {
        public UpdateRequest {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(runtimeSettingsJson, "runtimeSettingsJson");
        }
    }
}
