package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;

/**
 * Live gate L2's scenarios against a certified IN-MEMORY host (plan 008 S5 Step 2): everything that is not the host is the
 * production code and the real local broker - the operator commands against a Testcontainers PostgreSQL, the REST walk, the
 * real renderer and keyed hasher, the journal, the worker, the fleet scheduler, the emitter and the broker delivery. It
 * exists so the scenario logic has been executed before it meets a real host, and it supports NO claim about two real
 * containers on one host: only {@code MultiPlacementLiveIT} can.
 *
 * <p>Skipped with its reason when the broker image is not in the local daemon (it is loaded from a tarball, never pulled).
 */
@Tag("broker")
class MultiPlacementRehearsalIT extends AbstractMultiPlacementWalkthrough {

    @Override
    E2eStack createStack(EvidenceLog evidence) {
        assumeTrue(LocalMeshBroker.imageAvailable(), "the local image " + LocalMeshBroker.IMAGE
                + " is not in this Docker daemon, so the rehearsal's broker cannot start");
        return E2eStack.rehearsal(evidence);
    }

    @Override
    MultiPlacementTarget multiTarget(WalkthroughTarget base) {
        return MultiPlacementTarget.rehearsal(base);
    }

    @Override
    String evidencePrefix() {
        return "MultiPlacementRehearsal";
    }
}
