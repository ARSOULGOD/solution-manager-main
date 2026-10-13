package com.solace.sm.dp.apply.engine;

import com.solace.sm.dp.apply.model.ErrorClass;
import com.solace.sm.dp.apply.model.RequestState;
import java.util.Objects;

/**
 * Where a re-drive ended. The request is no longer {@code RUNNING}: it is terminal, or back on
 * {@code APPLY_INDETERMINATE}.
 *
 * @param gaveUp whether the re-drive ended in a give-up (the request is back on {@code APPLY_INDETERMINATE} carrying the
 *               classifier in {@code reconcile_class}, and its event has been published) rather than an ordinary landing.
 *               Only the former is permanent; an ordinary {@code APPLY_INDETERMINATE} landing is inspected again.
 */
public record RedriveResult(RequestState landed, ErrorClass why, boolean gaveUp) {

    public RedriveResult {
        Objects.requireNonNull(landed, "landed");
    }
}
