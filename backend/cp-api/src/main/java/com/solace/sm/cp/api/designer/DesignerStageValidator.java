package com.solace.sm.cp.api.designer;

import java.util.List;
import java.util.Map;

/**
 * The stage-time re-projection check {@code OR-34} requires: "Stage sends draft values + the
 * rendered set; the server re-projects, refuses a mismatch or an undeclared key, and stores its
 * own result."
 *
 * <p>This validator never trusts the client's claimed {@code rendered} set as the thing that
 * gets stored — it always re-runs {@link DesignerProjector#project} server-side and treats that
 * as ground truth ({@code D4} 🔒: the server, not the browser, decides what a draft renders to).
 * The client's claim is used only to detect drift between what the browser showed the operator
 * and what the server actually computes — refusing rather than silently storing the server's
 * answer under the operator's nose.
 *
 * <p>Building the stage HTTP endpoint itself, wiring it to persistence, and the apply path that
 * consumes the stored result are out of scope here — those belong to the later plan-010 task
 * this port exists to unblock. This is the port and its refusal semantics only.
 */
public interface DesignerStageValidator {

    StageOutcome validateStage(StageRequest request);

    record StageRequest(
            PackageModel.SolutionPackage pkg,
            StateModel.ComponentSelection selection,
            Map<String, Map<String, Object>> draft,
            StateModel.ObservedState observed,
            Map<String, List<Object>> catalog,
            Object requestedPackageVersion,
            /** What the client believes {@code project()} would render, for drift detection only. */
            List<ViewModel.RenderedEntry> claimedRendered) {
    }

    sealed interface StageOutcome {

        /** The server's own re-projected result — this, never the client's claim, is what gets stored. */
        record Accepted(ViewModel serverResult) implements StageOutcome {
        }

        record Refused(List<StageRefusalReason> reasons, ViewModel serverResult) implements StageOutcome {
        }
    }

    /** Every way a stage request can fail server-side re-projection, per {@code OR-34}. */
    sealed interface StageRefusalReason {

        /** Draft holds a key the package does not declare (mirrors the {@code ORPHAN_DRAFT_KEY} diagnostic). */
        record UndeclaredDraftKey(String instanceKey, String key) implements StageRefusalReason {
        }

        /** Same {@code (instanceKey, renderedPath)} in both sets, but the values disagree. */
        record ValueMismatch(String instanceKey, String renderedPath, Object serverValue, Object claimedValue)
                implements StageRefusalReason {
        }

        /** The server projects this entry; the client's claimed set omitted it. */
        record MissingFromClaim(String instanceKey, String renderedPath, Object serverValue)
                implements StageRefusalReason {
        }

        /** The client claimed this entry; the server's re-projection does not produce it. */
        record UnexpectedInClaim(String instanceKey, String renderedPath, Object claimedValue)
                implements StageRefusalReason {
        }
    }
}
