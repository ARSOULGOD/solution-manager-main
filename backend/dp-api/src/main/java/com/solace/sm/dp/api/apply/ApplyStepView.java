package com.solace.sm.dp.api.apply;

import java.util.List;
import java.util.UUID;

/**
 * One journaled step of a request.
 *
 * @param step                the engine's step, lower-case and hyphenated ({@code preflight}, {@code rollback-assess})
 * @param placementId         the request's placement, or {@code null} when the request is instance-wide
 * @param outcome             {@code succeeded}, {@code failed}, {@code timed-out}, {@code in-progress} or {@code pending}
 * @param reason              the CLASSIFIER of a failed attempt ({@code io.timeout}), never raw text - or {@code null}
 * @param gateProved          {@code readiness} or {@code liveness} on a {@code rolling-health-gated} restart step whose gate
 *                            PASSED on a probe SM executed (plan 010 T6.9); {@code null} otherwise. Never derived from a
 *                            declaration alone
 * @param changedPaths        on the step that found a retained pre-image changed ({@code OR-108} (1), {@code OR-102}): the
 *                            declared, non-secret dotted paths that differ, at most 50, or {@code null} when none was
 *                            recorded. Paths only, never a value ({@code I6})
 * @param changedPathsTruncated {@code true} when more paths differed than {@code changedPaths} lists; {@code null} when
 *                            {@code changedPaths} is {@code null}
 * @param otherContentChanged whether the file also differs somewhere that is not a listed path; {@code null} when
 *                            {@code changedPaths} is {@code null}
 */
public record ApplyStepView(String step, UUID placementId, String outcome, String reason, String gateProved,
        List<String> changedPaths, Boolean changedPathsTruncated, Boolean otherContentChanged) {

    public ApplyStepView {
        changedPaths = changedPaths == null ? null : List.copyOf(changedPaths);
    }

    /** A step that proved no gate and found no changed pre-image. */
    public ApplyStepView(String step, UUID placementId, String outcome, String reason, String gateProved) {
        this(step, placementId, outcome, reason, gateProved, null, null, null);
    }

    /** A step that proved no gate. */
    public ApplyStepView(String step, UUID placementId, String outcome, String reason) {
        this(step, placementId, outcome, reason, null);
    }
}
