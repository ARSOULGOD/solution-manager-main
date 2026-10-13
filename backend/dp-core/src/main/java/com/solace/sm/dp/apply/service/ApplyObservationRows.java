package com.solace.sm.dp.apply.service;

import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.ApplyRequestRow;
import com.solace.sm.dp.apply.model.AttemptOutcome;
import com.solace.sm.dp.apply.model.ChangedContent;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Step;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The read port of the apply journal (plan 010 T6.3): {@code apply_request}, {@code apply_step_intent} and
 * {@code apply_attempt}, and nothing else. There is no adapter anywhere behind it, which is the guarantee that an
 * observation can be answered when the host cannot.
 */
public interface ApplyObservationRows {

    /**
     * Requests of one instance ascending by {@code state_version}, at most {@code limit}, with each one's steps.
     *
     * @param applyId select one request, or {@code null} for all
     * @param sinceStateVersion strictly newer than this, or {@code null}
     */
    List<ObservedRequest> observe(TenantId tenant, InstanceId instance, ApplyId applyId, Long sinceStateVersion,
            int limit);

    /**
     * One request and the steps the engine journaled for it, in the order it journaled them.
     *
     * @param reconcileClass the indeterminate reconciler's give-up classifier (plan 010 T6.8, {@code
     *                       apply_request.reconcile_class}), or {@code null} while it has not given up
     */
    record ObservedRequest(ApplyRequestRow row, List<ObservedStep> steps, String reconcileClass) {

        public ObservedRequest {
            Objects.requireNonNull(row, "row");
            steps = List.copyOf(steps);
        }

        public ObservedRequest(ApplyRequestRow row, List<ObservedStep> steps) {
            this(row, steps, null);
        }
    }

    /**
     * One intent and its latest attempt.
     *
     * @param attempted  whether any attempt exists (an intent with none provably issued nothing)
     * @param outcome    the latest attempt's outcome, {@code null} while that attempt is open (or none exists)
     * @param errorClass the latest attempt's classifier, {@code null} when it carries none
     * @param changed    the declared paths a retained pre-image differs at, recorded by the attempt that found it
     *                   ({@code OR-108} (1)); {@code null} when none was recorded. Paths only, never a value
     * @param placementId the placement this step addressed ({@code BE-D44}): the step's own, from the intent's
     *                   {@code placement_id}. {@code null} when unknown — the intent of a stop that landed before
     *                   {@code V021} read back the nil value, which is history, and a hand-built row that does not say
     */
    record ObservedStep(Step step, boolean attempted, AttemptOutcome outcome, String errorClass,
            ChangedContent changed, UUID placementId) {

        public ObservedStep(Step step, boolean attempted, AttemptOutcome outcome, String errorClass,
                ChangedContent changed) {
            this(step, attempted, outcome, errorClass, changed, null);
        }

        public ObservedStep(Step step, boolean attempted, AttemptOutcome outcome, String errorClass) {
            this(step, attempted, outcome, errorClass, null, null);
        }
    }
}
