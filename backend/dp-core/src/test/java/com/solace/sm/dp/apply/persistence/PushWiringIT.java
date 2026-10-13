package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.ssh.SshSessionPoolProperties;
import com.solace.sm.dp.meshbridge.ingest.BrokerSubscriptionManager;
import com.solace.sm.dp.meshbridge.ingest.MeshbridgeIngestConfiguration;
import com.solace.sm.dp.metrics.MetricsConfiguration;
import com.solace.sm.dp.metrics.collect.MetricsBackgroundLoop;
import com.solace.sm.dp.metrics.collect.MetricsCollectConfiguration;
import com.solace.sm.dp.metrics.collect.PushReconcile;
import com.solace.sm.dp.metrics.collect.fetch.MetricsFetchConfiguration;
import com.solace.sm.dp.metrics.ingest.MetricsIngestConfiguration;
import com.solace.sm.dp.metrics.push.MetricsPushConfiguration;
import com.solace.sm.dp.metrics.push.PushIngestService;
import com.solace.sm.dp.metrics.rollup.MetricsRollupConfiguration;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretResolver;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-5.1: the production wiring MEASURED, not read. The configuration classes {@code DataPlaneConfiguration}
 * imports for the push path, on a real Spring context over the runtime credential: the push service and the session manager exist,
 * the metrics loop is running and is handed the push step, the path is withdrawn by {@code sm.dp.metrics.push.enabled=false}, and a
 * database that lacks the migration is refused at startup with the missing objects named.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(120)
class PushWiringIT {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SshSessionPoolProperties.class)
    static class Support {
        @Bean
        Clock smDataClock() {
            return Clock.systemUTC();
        }

        /** The master-key canary the loop waits for in production ({@code SecretConfiguration}'s). */
        @Bean
        Object kekCanaryCheck() {
            return new Object();
        }

        /**
         * The placement-transport lookup the collector requires since T3.1 (finding 15: no fail-open default). This wiring test
         * composes no agent path, so nothing is an agent target; the production lookup is AgentGatewayPersistenceConfiguration's.
         */
        @Bean
        com.solace.sm.dp.apply.runtime.PlacementTransports placementTransports() {
            return com.solace.sm.dp.apply.runtime.PlacementTransports.NONE;
        }

        /** Stands for {@code EnvelopeSecretStore}: the push path's session manager resolves a credential through it. */
        @Bean
        SecretResolver secretResolver() {
            return (tenantId, secretId, expected) -> SecretMaterial.adopt(new byte[] {'x'});
        }
    }

    private static ApplicationContextRunner runner(MetricsDb db, String... extra) {
        return new ApplicationContextRunner().withUserConfiguration(Support.class, ApplyPersistenceConfiguration.class,
                        InventoryPersistenceConfiguration.class, ConfigPersistenceConfiguration.class,
                        MetricsPersistenceConfiguration.class, MetricDefinitionPersistenceConfiguration.class,
                        IngestPersistenceConfiguration.class, MetricsConfiguration.class, MetricsIngestConfiguration.class,
                        RollupPersistenceConfiguration.class, MetricsRollupConfiguration.class,
                        MetricsFetchConfiguration.class, CollectPersistenceConfiguration.class,
                        MetricsCollectConfiguration.class, BrokerConnectionPersistenceConfiguration.class,
                        MeshbridgeIngestConfiguration.class, MetricsPushConfiguration.class)
                .withPropertyValues("sm.data.runtime.url=" + db.url(), "sm.data.runtime.username=sm_dp_runtime",
                        "sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD, "sm.dp.health.enabled=false")
                .withPropertyValues(extra);
    }

    @Test
    void thePushServicesExistAndTheRunningLoopIsHandedTheStep() {
        MetricsDb db = MetricsDb.fresh("push_wiring");
        runner(db).run(context -> {
            assertTrue(context.getStartupFailure() == null, String.valueOf(context.getStartupFailure()));
            assertNotNull(context.getBean(PushIngestService.class));
            assertNotNull(context.getBean(BrokerSubscriptionManager.class));
            assertEquals(1, context.getBeansOfType(PushReconcile.class).size(), "the loop's ONE push step");
            assertTrue(context.getBean(MetricsBackgroundLoop.class).isRunning(), "the loop runs, and its tick calls the push step");
        });
    }

    @Test
    void thePathCanBeWithdrawnAndTheLoopStillRuns() {
        MetricsDb db = MetricsDb.fresh("push_wiring_off");
        runner(db, "sm.dp.metrics.push.enabled=false").run(context -> {
            assertTrue(context.getStartupFailure() == null, String.valueOf(context.getStartupFailure()));
            assertFalse(context.containsBean("pushIngestService"));
            assertTrue(context.getBean(MetricsBackgroundLoop.class).isRunning());
        });
    }

    @Test
    void aDatabaseWithoutTheMigrationIsRefusedAtStartupAndTheMissingObjectsAreNamed() {
        // sm_data migrated, sm_metrics not (an install that skipped the new step), and the purpose CHECK put back to its old three
        MetricsDb db = MetricsDb.fresh("push_wiring_unmigrated", MetricsDb.Migrated.SM_DATA_ONLY);
        db.asOwner("ALTER TABLE sm_secret.secret DROP CONSTRAINT secret_purpose",
                "ALTER TABLE sm_secret.secret ADD CONSTRAINT secret_purpose CHECK (purpose IN ('SSH_PRIVATE_KEY',"
                        + " 'CONFIG_FIELD', 'CONFIG_HASH_KEY'))");
        new ApplicationContextRunner().withUserConfiguration(Support.class, ApplyPersistenceConfiguration.class,
                        BrokerConnectionPersistenceConfiguration.class)
                .withPropertyValues("sm.data.runtime.url=" + db.url(), "sm.data.runtime.username=sm_dp_runtime",
                        "sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD, "sm.dp.health.enabled=false")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "an unmigrated database must not boot the push path");
                    String chain = chain(failure);
                    assertFalse(chain.contains("table sm_data.broker_connection"), "that table is there: " + chain);
                    assertTrue(chain.contains("table sm_metrics.broker_ingest_counter"), chain);
                    assertTrue(chain.contains("purpose BROKER_CREDENTIAL"), chain);
                    assertFalse(chain.contains(SmDataPostgres.RUNTIME_PASSWORD), "never the credential");
                });
    }

    private static String chain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            b.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append('\n');
        }
        return b.toString();
    }
}
