package com.solace.sm.dp.apply.model;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code apply_attempt.pre_baseline} — the process/container start baseline a {@code LIFECYCLE}
 * attempt records BEFORE it acts, and compares against on resume.
 *
 * <p>Design §2.2 rule 3: "compare {@code status(replica)}'s start baseline against the attempt's
 * recorded {@code pre_baseline}: restart observed → continue; baseline unchanged → re-issue under the
 * same key; baseline missing or unreadable → {@code APPLY_INDETERMINATE}". So the whole content of this
 * row is process METADATA — when the thing was last started, and which process it was.
 *
 * <p><b>{@code processRef} is shape-controlled, not content-controlled.</b> It carries the same
 * grammar as a {@link RuntimeRef} value, which excludes an environment line, a {@code Bearer} header or
 * any structured secret representation, padded base64, PEM, a JSON blob, a stack trace and exception
 * text — and admits any bare identifier-shaped token, a compact JWT or a hex secret included. Keeping a
 * secret out of the VALUE is the {@code I6} obligation of whichever adapter reads the baseline (docker
 * inspect, systemd, the Kubernetes API); its own contract test against sentinel secrets is where that
 * is enforced, and Plan 005 carries it by name.
 *
 * <p>{@code baseline_only_for_lifecycle} is the schema half of the same statement: no other step may
 * carry one at all.
 *
 * @param startedAt  when the observed process/container started
 * @param processRef the adapter's identifier for it — a container id, a PID-and-boot-id, a pod name
 */
public record PreBaseline(Instant startedAt, String processRef) {

    public PreBaseline {
        Objects.requireNonNull(startedAt, "pre-baseline started at");
        ValueGrammar.require(ValueGrammar.VALUE, processRef, "a pre-baseline process ref",
                "an adapter identifier for the observed process (design §2.2 rule 3) — process "
                        + "metadata, never configuration (I6)");
    }
}
