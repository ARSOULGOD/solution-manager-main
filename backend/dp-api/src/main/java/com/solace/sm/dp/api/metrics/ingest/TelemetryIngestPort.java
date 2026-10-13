package com.solace.sm.dp.api.metrics.ingest;

/**
 * The one door telemetry enters the data plane through (plan 011 P11-3.1; AGW-D9's seam, PROPOSED), with M-Q7's
 * dp-remote mapping: {@code bindingId := placementId}, {@code sourceKey := placementId}. The collector (path 1), the
 * broker subscriber (path 2) and, later, the agent gateway all call it, and nothing else writes a sample.
 *
 * <p>What a producer supplies is a name, a timestamp, a number and, optionally, a restart token. What it never supplies
 * is identity: the tenant's placement, its instance, the metric's type and unit, and how a restart is detected are all
 * read by the data plane from its own rows (the I1 check in the plan, and {@code IngestSample}'s five components).
 *
 * <p><b>{@link IngestResult.RetryAfter} means the batch is held</b> in SM's bounded buffer (discovery default D-16)
 * and will be written when the database or the partition runway allows. A producer must not send it again: a second
 * copy is harmless (an exact duplicate is ignored, D-2) but is wasted work.
 *
 * <p>Implementations are tenant-bound: a batch naming a placement the tenant does not own is
 * {@code Rejected("placement.unknown")} and writes nothing, whatever else it carries.
 */
public interface TelemetryIngestPort {

    IngestResult ingest(IngestBatch batch);
}
