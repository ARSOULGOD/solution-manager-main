package com.solace.sm.launcher;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Plan 008 S5 Step 2, live gate L2 ({@code OR-110}: a second Cache on the E2E host "if needed"): one instance with two
 * placements on one target, against an OPERATOR-PROVIDED SSH-Docker host that runs the first Cache AND a second Cache
 * container the customer's CI/CD has deployed (SM deploys nothing, {@code OR-29}). The scenarios are
 * {@link AbstractMultiPlacementWalkthrough}'s and are executed first, every run, against an in-memory host by
 * {@code MultiPlacementRehearsalIT}.
 *
 * <p><b>Gated</b> exactly as {@link SkeletonWalkthroughIT} is, on the walkthrough's own target
 * ({@code -Dsm.walkthrough.ssh.host=...} and the rest, see {@link WalkthroughTarget}), plus the second Cache
 * ({@code -Dsm.walkthrough.multi.second.*}, see {@link MultiPlacementTarget}); {@code
 * backend/launcher/scripts/run-e2e-multi-placement.sh} fills them from {@code SM_E2E_*}. Without the gate property this
 * class is SKIPPED with the reason below and no host is looked for: <b>the L2 claim is not made until a run of this class has
 * passed against the host.</b>
 *
 * <p><b>It writes to the host.</b> Both Caches' configuration files are overwritten (their directories also receive SM's
 * hidden retained pre-image files); each ORIGINAL keyed hash is read first and held in memory and in the evidence, and the
 * scenario restores both through the engine's own restore. <b>Only with {@code sm.walkthrough.multi.disruptive=yes}</b> does
 * it stop the second Cache, stop both, and restart both (and start any container it stopped again, in {@code @AfterAll},
 * whatever fails).
 */
@Tag("walkthrough")
@EnabledIfSystemProperty(named = WalkthroughTarget.GATE_PROPERTY, matches = ".+",
        disabledReason = "plan 008 S5 live gate L2 needs an operator-provided SSH-Docker host running two Cache containers: "
                + "run with -Dsm.walkthrough.ssh.host=<host>, the rest of the target and -Dsm.walkthrough.multi.second.* "
                + "(backend/launcher/scripts/run-e2e-multi-placement.sh); without it the L2 claim is not made")
class MultiPlacementLiveIT extends AbstractMultiPlacementWalkthrough {

    @Override
    E2eStack createStack(EvidenceLog evidence) {
        return E2eStack.live(WalkthroughTarget.fromSystem(), evidence);
    }

    @Override
    MultiPlacementTarget multiTarget(WalkthroughTarget base) {
        return MultiPlacementTarget.parse(System::getProperty, base);
    }

    @Override
    String evidencePrefix() {
        return "MultiPlacementLiveIT";
    }
}
