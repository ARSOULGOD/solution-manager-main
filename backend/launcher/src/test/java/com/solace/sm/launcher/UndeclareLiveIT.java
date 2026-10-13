package com.solace.sm.launcher;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Plan 012 T5 live gate L5 ({@code OR-110}: the E2E host's second Cache): declare an instance on a second Cache container the
 * customer's CI/CD has deployed (SM deploys nothing, {@code OR-29}), then undeclare it and prove SM touched nothing on the
 * host. The scenarios are {@link AbstractUndeclareWalkthrough}'s and are executed first, every run, against an in-memory host
 * by {@code UndeclareRehearsalIT}.
 *
 * <p><b>Gated</b> exactly as {@link SkeletonWalkthroughIT} and {@link MultiPlacementLiveIT} are, on the walkthrough's own
 * target ({@code -Dsm.walkthrough.ssh.host=...} and the rest, see {@link WalkthroughTarget}), plus the second Cache
 * ({@code -Dsm.walkthrough.undeclare.second.*}, see {@link UndeclareTarget}); {@code
 * backend/launcher/scripts/run-e2e-undeclare.sh} fills them from {@code SM_E2E_*} and does the two things only a host-side
 * session can: {@code docker inspect} before and after (the container's id and start time must be identical) and the runbook's
 * cleanup of the hidden files with a listing before and after. Without the gate property this class is SKIPPED with the
 * reason below and no host is looked for: <b>the L5 claim is not made until the script has passed against the host.</b>
 *
 * <p><b>It writes to the host.</b> The second Cache's configuration file is overwritten once (its directory also receives SM's
 * hidden retained files) and put back to its ORIGINAL keyed hash through the engine's own restore before the undeclare. It
 * stops and restarts nothing.
 */
@Tag("walkthrough")
@EnabledIfSystemProperty(named = WalkthroughTarget.GATE_PROPERTY, matches = ".+",
        disabledReason = "plan 012 T5 live gate L5 needs an operator-provided SSH-Docker host running a second Cache container: "
                + "run with -Dsm.walkthrough.ssh.host=<host>, the rest of the target and -Dsm.walkthrough.undeclare.second.* "
                + "(backend/launcher/scripts/run-e2e-undeclare.sh); without it the L5 claim is not made")
class UndeclareLiveIT extends AbstractUndeclareWalkthrough {

    @Override
    E2eStack createStack(EvidenceLog evidence) {
        return E2eStack.live(WalkthroughTarget.fromSystem(), evidence);
    }

    @Override
    UndeclareTarget undeclareTarget(WalkthroughTarget base) {
        return UndeclareTarget.parse(System::getProperty, base);
    }

    @Override
    String evidencePrefix() {
        return "UndeclareLiveIT";
    }
}
