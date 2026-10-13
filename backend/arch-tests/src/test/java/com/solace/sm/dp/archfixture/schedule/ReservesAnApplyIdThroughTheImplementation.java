package com.solace.sm.dp.archfixture.schedule;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.apply.service.ApplyServiceImpl;
import java.util.UUID;

/**
 * Planted violation (W5 review W5A-3): calls the reserved-id overloads through the concrete {@code ApplyServiceImpl}, so the
 * bytecode owner of each call is the implementation and not the port. A rule that matched only the interface would miss it.
 * {@code ApplyScheduleBoundaryTest} must report this class for both overloads.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class ReservesAnApplyIdThroughTheImplementation {

    private ReservesAnApplyIdThroughTheImplementation() {
    }

    public static ApplyAcceptedView submit(ApplyServiceImpl apply, UUID organization, UUID instance, SubmitApplyCommand body,
            UUID id) {
        return apply.submitApply(organization, instance, body, id);
    }

    public static ApplyAcceptedView rollback(ApplyServiceImpl apply, UUID organization, UUID instance, RollbackCommand body,
            UUID id) {
        return apply.rollbackApply(organization, instance, body, id);
    }
}
