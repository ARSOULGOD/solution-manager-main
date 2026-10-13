package com.solace.sm.gateway.archfixture.schedule;

import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import java.util.UUID;

/**
 * Planted violation (W5 review W5A-3): a gateway route that maps a client-supplied id onto the reserved-id overloads of the
 * {@code ApplyService} port. A matching id of the same placement would be answered with that apply's view, as if the call had
 * been accepted. {@code ApplyScheduleBoundaryTest} must report this class for both overloads.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class AttachesAnExistingApplyByReservedId {

    private AttachesAnExistingApplyByReservedId() {
    }

    public static ApplyAcceptedView submit(ApplyService apply, UUID organization, UUID instance, SubmitApplyCommand body,
            UUID clientSuppliedId) {
        return apply.submitApply(organization, instance, body, clientSuppliedId);
    }

    public static ApplyAcceptedView rollback(ApplyService apply, UUID organization, UUID instance, RollbackCommand body,
            UUID clientSuppliedId) {
        return apply.rollbackApply(organization, instance, body, clientSuppliedId);
    }
}
