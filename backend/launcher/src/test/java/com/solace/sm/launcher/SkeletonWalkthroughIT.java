package com.solace.sm.launcher;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Plan 010 T6.6 / OR-46: the first end-to-end walkthrough against an OPERATOR-PROVIDED SSH-Docker host running the Cache
 * container, with the SM mesh events on a LOCAL broker this run starts. The scenarios are {@link AbstractSkeletonWalkthrough}'s
 * and are executed first, every run, against an in-memory host by {@code SkeletonWalkthroughRehearsalIT}.
 *
 * <p><b>Gated.</b> It runs only with {@code -Dsm.walkthrough.ssh.host=...} (and the rest of the target; see
 * {@link WalkthroughTarget}), as plan 005's {@code CacheApplyWalkthroughIT} is gated. No sshd harness is invented here, and
 * no host is ever looked for: without the property this class is SKIPPED with the reason below and the OR-46 claim is NOT
 * made. The run script {@code backend/launcher/scripts/run-e2e-walkthrough.sh} reads the target from {@code SM_E2E_*}
 * environment variables and passes them in.
 *
 * <p><b>It writes to the host.</b> The Cache's configuration file is overwritten (its directory also receives SM's hidden
 * retained pre-image files). The ORIGINAL keyed hash is read first and held in memory and in the evidence; the last step
 * restores the file through the engine's own restore, or says exactly how to restore it by hand.
 */
@Tag("walkthrough")
@EnabledIfSystemProperty(named = WalkthroughTarget.GATE_PROPERTY, matches = ".+",
        disabledReason = "plan 010 T6.6 needs an operator-provided SSH-Docker host: run with "
                + "-Dsm.walkthrough.ssh.host=<host> and the rest of the target (backend/launcher/scripts/run-e2e-walkthrough.sh);"
                + " without it the OR-46 claim is not made")
class SkeletonWalkthroughIT extends AbstractSkeletonWalkthrough {

    @Override
    E2eStack createStack(EvidenceLog evidence) {
        return E2eStack.live(WalkthroughTarget.fromSystem(), evidence);
    }

    @Override
    String evidencePrefix() {
        return "SkeletonWalkthroughIT";
    }
}
