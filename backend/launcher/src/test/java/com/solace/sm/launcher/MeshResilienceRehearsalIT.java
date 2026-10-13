package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;

/**
 * The resilience scenarios against a certified in-memory host and a REAL local broker that the scenarios stop and start
 * (plan 010 T6.6). It supports no claim about the SSH-Docker adapter; see {@link SkeletonWalkthroughRehearsalIT}.
 */
@Tag("broker")
class MeshResilienceRehearsalIT extends AbstractMeshResilience {

    @Override
    E2eStack createStack(EvidenceLog evidence) {
        assumeTrue(LocalMeshBroker.imageAvailable(), "the local image " + LocalMeshBroker.IMAGE
                + " is not in this Docker daemon, so the rehearsal's broker cannot start");
        return E2eStack.rehearsal(evidence);
    }

    @Override
    String evidencePrefix() {
        return "MeshResilienceRehearsal";
    }
}
