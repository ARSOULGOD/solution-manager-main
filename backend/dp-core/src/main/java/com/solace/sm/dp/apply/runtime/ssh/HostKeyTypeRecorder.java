package com.solace.sm.dp.apply.runtime.ssh;

import com.solace.sm.dp.api.inventory.HostKeyType;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where a connector reports the key type it DISCOVERED for a pin whose type was not recorded (OR-125 (2)): the first
 * successful connection to a target registered before {@code sm_data} V027 (or by the operator command) learns the exact type
 * of the key that matched, and this persists it so every later connection is a single attempt.
 *
 * <p>Recording is a convenience of the next connection and must never fail this one: an implementation swallows its own
 * failures. The pin is the key, scoped to the tenant (the live target pinned to a fingerprint is unique per tenant).
 */
@FunctionalInterface
public interface HostKeyTypeRecorder {

    /** Records nothing: for a connector that has nowhere to write, and for tests. */
    HostKeyTypeRecorder NONE = (tenant, pinnedFingerprint, type) -> { };

    void learned(TenantId tenant, String pinnedFingerprint, HostKeyType type);

    /** Persists to the target's connection record, only while the record has no type. */
    static HostKeyTypeRecorder over(TargetConnectionRows connections) {
        Objects.requireNonNull(connections, "connections");
        Logger log = LoggerFactory.getLogger(HostKeyTypeRecorder.class);
        return (tenant, pinnedFingerprint, type) -> {
            try {
                connections.recordHostKeyType(tenant.value(), pinnedFingerprint, type);
            } catch (RuntimeException e) {
                // Not the exception's text: a driver message can quote a value. The next discovery tries again.
                log.warn("could not record a pinned host key's type ({})", e.getClass().getSimpleName());
            }
        };
    }
}
