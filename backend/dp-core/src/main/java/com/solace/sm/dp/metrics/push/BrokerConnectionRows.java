package com.solace.sm.dp.metrics.push;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistence port for {@code broker_connection} (plan 011 P11-5.1). Every method names the tenant (I5). The runtime role
 * holds only SELECT and INSERT on the table (V017), so this port has no update and no delete.
 */
public interface BrokerConnectionRows {

    /** @throws com.solace.sm.dp.apply.persistence.PgConstraintViolation a named constraint, for example {@code broker_connection_live_once} */
    void insert(BrokerConnectionRecord row);

    Optional<BrokerConnectionRecord> find(UUID tenantId, UUID brokerConnectionId);

    /** The one live (not retired) connection of an environment, if any. */
    Optional<BrokerConnectionRecord> live(UUID tenantId, UUID environmentId);

    /** Every live connection of the organization (one per environment). */
    List<BrokerConnectionRecord> liveOf(UUID tenantId);
}
