package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.kit.AbstractAgentTelemetryDeclarationsPortContractTest;
import com.solace.sm.dp.api.agw.kit.AgentKitWorld;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The {@code TelemetryDeclarations} port contract kit over the real service, repositories and ladder settle, as the runtime role, on
 * PostgreSQL 14 (plan 015 W4 lane D; AGW-D2).
 */
@Testcontainers(disabledWithoutDocker = true)
class JdbcAgentTelemetryDeclarationsPortContractIT extends AbstractAgentTelemetryDeclarationsPortContractTest {

    private final AgentJdbcWorld world = new AgentJdbcWorld();

    @Override
    protected AgentKitWorld world() {
        return world;
    }
}
