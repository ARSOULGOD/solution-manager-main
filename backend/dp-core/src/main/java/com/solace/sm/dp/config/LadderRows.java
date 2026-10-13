package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.LadderRung;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import java.util.OptionalLong;

/**
 * The journal read and the {@code reached_rung} write behind the config ladder (plan 010 T6.5). A port: the JDBC
 * lives in {@code dp.apply.persistence} (JDBC is confined there), this package holds the rules.
 *
 * <p>{@code I5}: every method names the tenant and binds it.
 */
public interface LadderRows {

    /** Read-only. An undeclared instance has no evidence ({@link LadderEvidence#none()}); that is not an error here. */
    LadderEvidence evidence(TenantId tenant, InstanceId instance);

    /**
     * Compare-and-set {@code instance_state.reached_rung} from {@code expected} (null = never written) to
     * {@code next}. The column write stamps {@code instance_state} (V007's trigger), which is the
     * {@code stateVersion} {@code instance.config-state} carries.
     *
     * @return the instance's new {@code state_version}, or empty if the column was not {@code expected}
     */
    OptionalLong storeReachedRung(TenantId tenant, InstanceId instance, LadderRung expected, LadderRung next);
}
