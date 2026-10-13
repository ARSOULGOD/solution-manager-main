package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.inventory.InstanceRetirementService;
import com.solace.sm.dp.api.inventory.RetirementRefusedException;
import com.solace.sm.dp.api.inventory.WorkloadDisposition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 012 T3: the data plane's undeclare port is in the PRODUCTION boot (it is a bean of {@code DataPlaneConfiguration}'s
 * aggregator, behind its own schema readiness check), and it answers over the runtime credential against a database
 * migrated through V023. Plan 012 T5's gateway orchestrator and sweep consume it; this proves the port itself is reachable without them.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InstanceRetirementBootIT {

    @Autowired
    private InstanceRetirementService retirement;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
    }

    @Test
    void theRetirementPortIsABeanOfTheProductionBootAndReadsTheMigratedSchema() {
        assertNotNull(retirement);
        UUID tenant = UUID.randomUUID();

        assertEquals(List.of(), retirement.retiring(tenant), "reads instance_state and instance_retirement as sm_dp_runtime");
        assertEquals(RetirementRefusedException.Reason.NOT_FOUND,
                assertThrows(RetirementRefusedException.class, () -> retirement.beginRetire(tenant, UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), WorkloadDisposition.LEAVE_RUNNING)).reason(),
                "an unknown instance of an unknown tenant is a 404, with no row written");
    }
}
