package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.ssh.SshSessionPoolProperties;
import com.solace.sm.dp.inventory.LoopbackTargetAddressConfiguration;
import com.solace.sm.dp.metrics.MetricsConfiguration;
import com.solace.sm.dp.metrics.collect.MetricsBackgroundLoop;
import com.solace.sm.dp.metrics.collect.MetricsCollectConfiguration;
import com.solace.sm.dp.metrics.collect.fetch.MetricsFetchConfiguration;
import com.solace.sm.dp.metrics.ingest.MetricsIngestConfiguration;
import com.solace.sm.dp.metrics.rollup.MetricsRollupConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-4.3: the production wiring MEASURED, not read. The same configuration classes
 * {@code DataPlaneConfiguration} imports, on a real Spring context over the runtime credential: the loop bean exists and
 * is running, and an active direct-mode instance on a local HTTP server is collected end to end by it, with no test
 * calling the loop. {@code sm.dp.metrics.collect.enabled=false} withdraws it.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(120)
class CollectWiringIT {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SshSessionPoolProperties.class)
    static class Support {
        @Bean
        Clock smDataClock() {
            return Clock.systemUTC();
        }

        /** The pool the SSH fetcher shares needs the secret store (W3-10: it exists with health off, for the collector). */
        @Bean
        com.solace.sm.dp.secret.SecretResolver secretResolver() {
            return (tenantId, secretId, expected) -> {
                throw new UnsupportedOperationException("no secret is resolved in this wiring test");
            };
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
    }

    private static ApplicationContextRunner runner(MetricsDb db, String... extra) {
        return new ApplicationContextRunner().withUserConfiguration(Support.class, ApplyPersistenceConfiguration.class,
                        InventoryPersistenceConfiguration.class, MetricsPersistenceConfiguration.class,
                        MetricDefinitionPersistenceConfiguration.class, IngestPersistenceConfiguration.class,
                        MetricsConfiguration.class, MetricsIngestConfiguration.class,
                        RollupPersistenceConfiguration.class, MetricsRollupConfiguration.class,
                        MetricsFetchConfiguration.class, LoopbackTargetAddressConfiguration.class, CollectPersistenceConfiguration.class,
                        MetricsCollectConfiguration.class)
                .withPropertyValues("sm.data.runtime.url=" + db.url(), "sm.data.runtime.username=sm_dp_runtime",
                        "sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD, "sm.dp.health.enabled=false")
                .withPropertyValues(extra);
    }

    @Test
    void theProductionLoopIsRunningAndCollectsAnActiveInstanceWithNobodyCallingIt() throws Exception {
        MetricsDb db = MetricsDb.fresh("collect_wiring");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = (exchange.getRequestURI().getPath().equals("/api/cache/size") ? "42"
                    : "{\"hitRate\":0.75,\"hitCount\":9,\"missCount\":3}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try (IngestFixture ingest = IngestFixture.on(db)) {
            CollectFixture fx = new CollectFixture(ingest);
            UUID tenant = fx.tenant();
            UUID solution = UUID.randomUUID();
            fx.declareVersion(tenant, solution, "2.4", CollectFixture.cacheMetrics(), List.of());
            CollectFixture.Instance instance = fx.instance(tenant, solution, "2.4", "direct-http", "active");
            UUID placement = fx.placement(instance, fx.target(tenant, "127.0.0.1"),
                    Map.of(8081, server.getAddress().getPort()));
            fx.activeList(instance, 0, fx.cacheMetricNames().toArray(String[]::new));

            runner(db).run(context -> {
                assertTrue(context.getStartupFailure() == null, String.valueOf(context.getStartupFailure()));
                MetricsBackgroundLoop loop = context.getBean(MetricsBackgroundLoop.class);
                assertTrue(loop.isRunning(), "the loop is a running lifecycle bean in the production wiring");
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
                IngestFixture.Site site = instance.asSite(placement);
                while (System.nanoTime() < deadline && ingest.points(site, "cache.hit_rate").isEmpty()) {
                    Thread.sleep(100);
                }
                assertEquals(1, ingest.points(site, "cache.hit_rate").size(),
                        "collected by the loop with no session, no apply and no test driving it");
                assertEquals(0.75, ingest.points(site, "cache.hit_rate").get(0).value());
                assertNotNull(context.getBean(com.solace.sm.dp.metrics.collect.MetricsCollector.class));
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void theLoopCanBeWithdrawn() {
        MetricsDb db = MetricsDb.fresh("collect_wiring_off");
        runner(db, "sm.dp.metrics.collect.enabled=false").run(context -> {
            assertTrue(context.getStartupFailure() == null, String.valueOf(context.getStartupFailure()));
            assertFalse(context.containsBean("metricsBackgroundLoop"));
        });
    }
}
