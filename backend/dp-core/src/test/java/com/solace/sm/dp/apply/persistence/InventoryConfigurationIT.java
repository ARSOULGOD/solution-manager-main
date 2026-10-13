package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.InstanceInventoryService;
import com.solace.sm.dp.api.inventory.TargetConnectionService;
import com.solace.sm.dp.apply.engine.ApplyEngineConfiguration;
import com.solace.sm.dp.inventory.DeclareSweepProperties;
import com.solace.sm.dp.inventory.InventoryConfiguration;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.secret.SecretConfiguration;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Plan 010 T2.5's wiring: the inventory configuration pair boots over the real runtime credential. */
@Testcontainers(disabledWithoutDocker = true)
class InventoryConfigurationIT {

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
    }

    @BeforeEach
    void cleanInstallLevelState() {
        SecretFixtures.resetCanaries();
    }

    @Test
    void theReadinessQueryFindsExactlyTheThreeTablesV007Creates() throws SQLException {
        try (Connection c = SmDataPostgres.runtimeDataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(InventoryPersistenceConfiguration.READINESS_QUERY)) {
            assertTrue(rs.next());
            assertEquals(InventoryPersistenceConfiguration.EXPECTED_TABLES, rs.getInt(1));
        }
    }

    @Test
    void oneInventoryBeanAnswersBothDpApiPortsAndTheContextBoots() {
        new ApplicationContextRunner()
                .withUserConfiguration(ApplyPersistenceConfiguration.class, ApplyEngineConfiguration.class,
                        SecretPersistenceConfiguration.class, SecretConfiguration.class,
                        InventoryPersistenceConfiguration.class, InventoryConfiguration.class,
                        MetricDefinitionPersistenceConfiguration.class)
                .withPropertyValues(
                        "sm.data.runtime.url=" + SmDataPostgres.jdbcUrl(),
                        "sm.data.runtime.username=sm_dp_runtime",
                        "sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD,
                        "sm.data.apply.max-attempts=5",
                        // the literal environment-variable names the provider reads (M16 (i))
                        com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES + "=true",
                        "SM_DP_MASTER_KEY=" + SecretFixtures.keyBase64(31),
                        "SM_DP_MASTER_KEY_ID=" + SecretFixtures.freshKeyId())
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> String.valueOf(context.getStartupFailure()));
                    InventoryService service = context.getBean(InventoryService.class);
                    assertTrue(context.getBean(InstanceInventoryService.class) == service);
                    assertTrue(context.getBean(TargetConnectionService.class) == service);
                    assertEquals(DeclareSweepProperties.DEFAULT_ABANDON_AFTER,
                            context.getBean(DeclareSweepProperties.class).abandonAfter());
                });
    }
}
