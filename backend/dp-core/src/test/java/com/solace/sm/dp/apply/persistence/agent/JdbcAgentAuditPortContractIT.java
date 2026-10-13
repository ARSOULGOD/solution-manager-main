package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.kit.AbstractAgentAuditPortContractTest;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The {@code Audit} port contract kit over the real repositories, as the runtime role, on PostgreSQL 14 (plan 015 T2.1b). */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAgentAuditPortContractIT extends AbstractAgentAuditPortContractTest {

    private final AgentJdbcWorld world = new AgentJdbcWorld();

    @Override
    protected AgentKitWorld world() {
        return world;
    }
}
