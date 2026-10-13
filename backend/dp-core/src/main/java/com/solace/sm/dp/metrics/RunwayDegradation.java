package com.solace.sm.dp.metrics;

/**
 * The seam between the partition runway keeper and ingest (plan 011 P11-3.2): when the keeper could not extend the
 * runway (a lock timeout it re-raised), ingest counts {@code RUNWAY_DEGRADED} for every placement that has been
 * ingesting, so the operator sees the degradation per placement even before an insert fails. Implemented by the
 * ingest service; the keeper knows nothing else about ingest.
 */
public interface RunwayDegradation {

    /** The keeper could not extend the runway this time. Never throws. */
    void runwayCouldNotBeExtended();
}
