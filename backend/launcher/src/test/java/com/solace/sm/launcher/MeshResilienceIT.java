package com.solace.sm.launcher;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Plan 010 T6.6 / plan 007 Task 9: scenarios 1-3, 11 and R1 against the operator-provided SSH-Docker host, SM's mesh events
 * on a LOCAL broker this run starts. Gated exactly as {@link SkeletonWalkthroughIT} is (and for the same reasons); the
 * scenarios are {@link AbstractMeshResilience}'s and run first, every run, against an in-memory host in
 * {@code MeshResilienceRehearsalIT}.
 *
 * <p>Scenario 2 and the restore at the end restart the Cache container; scenario 11 and R1 overwrite its configuration file.
 * The original keyed hash is captured first and the last step restores it (see {@link SkeletonWalkthroughIT}).
 */
@Tag("walkthrough")
@EnabledIfSystemProperty(named = WalkthroughTarget.GATE_PROPERTY, matches = ".+",
        disabledReason = "plan 010 T6.6 needs an operator-provided SSH-Docker host: run with "
                + "-Dsm.walkthrough.ssh.host=<host> and the rest of the target (backend/launcher/scripts/run-e2e-walkthrough.sh);"
                + " without it the OR-46 claim is not made")
class MeshResilienceIT extends AbstractMeshResilience {

    @Override
    E2eStack createStack(EvidenceLog evidence) {
        return E2eStack.live(WalkthroughTarget.fromSystem(), evidence);
    }

    @Override
    String evidencePrefix() {
        return "MeshResilienceIT";
    }
}
