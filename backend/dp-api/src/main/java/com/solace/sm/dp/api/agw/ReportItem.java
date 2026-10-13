package com.solace.sm.dp.api.agw;

import java.time.Instant;

/**
 * One actual-state observation. <b>Every field is agent-supplied</b>: {@code bindingId} (a placement id on this
 * path) and the replica/incarnation names are claims to be validated against the lease, never a way to resolve a
 * tenant or an instance (I5; T3.3 owns the check). {@code health} and {@code containerStartedAt} are the T2.3
 * additions (facts the DP maps; Docker health is a hint only). {@code toString} omits {@code effectiveHash}, the
 * plain digest of the file on the host.
 *
 * @param health             {@code null} when the agent sent none
 * @param containerStartedAt Docker {@code State.StartedAt}, host clock, no offset; {@code null} when absent
 */
public record ReportItem(String itemId, long sequence, String bindingId, String replicaRef,
        String incarnation, long persistedGeneration, Instant restartObservedAt, String effectiveHash,
        String versionMechanism, String versionValue, String versionConfidence,
        OperationCorrelation correlation, AgentHealthObservation health, Instant containerStartedAt) {

    @Override
    public String toString() {
        return "ReportItem[itemId=" + itemId + ", sequence=" + sequence + ", replicaRef=" + replicaRef
                + ", persistedGeneration=" + persistedGeneration + ", health=" + health
                + ", effectiveHash=<redacted>]";
    }
}
