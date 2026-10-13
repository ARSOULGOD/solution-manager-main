package com.solace.sm.dp.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.ssh.SshSessionPool;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.metrics.collect.fetch.MetricsFetchConfiguration;
import com.solace.sm.dp.metrics.collect.fetch.SshMetricFetcher;
import com.solace.sm.dp.secret.SecretResolver;
import java.lang.reflect.Proxy;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * W3-10: collection and health are independent (B/D2, UI-D8). The shared SSH session pool and the collector's SSH fetcher
 * exist when EITHER the health poller or the collector is enabled, so switching health polling off to cut SSH load does not
 * silently stop SSH metric collection. No database: the row ports are inert proxies, nothing here reads one.
 */
class SshPoolWiringTest {

    @Configuration(proxyBeanMethods = false)
    static class Support {
        @Bean
        Clock smDataClock() {
            return Clock.systemUTC();
        }

        @Bean
        SecretResolver secretResolver() {
            return (tenantId, secretId, expected) -> {
                throw new UnsupportedOperationException();
            };
        }

        @Bean
        PlacementRows placementRows() {
            return inert(PlacementRows.class);
        }

        @Bean
        TargetConnectionRows targetConnectionRows() {
            return inert(TargetConnectionRows.class);
        }

        @Bean
        InstanceRows instanceRows() {
            return inert(InstanceRows.class);
        }

        private static <T> T inert(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            }));
        }
    }

    private ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner().withUserConfiguration(Support.class, SshPoolConfiguration.class,
                MetricsFetchConfiguration.class).withPropertyValues(properties);
    }

    @Test
    void switchingHealthOffLeavesThePoolAndTheSshFetcherForTheCollector() {
        runner("sm.dp.health.enabled=false", "sm.dp.metrics.collect.enabled=true").run(context -> {
            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
            assertEquals(1, context.getBeansOfType(SshSessionPool.class).size(), "the collector still has its pool");
            assertEquals(1, context.getBeansOfType(SshMetricFetcher.class).size(), "and its SSH fetcher");
        });
    }

    @Test
    void switchingTheCollectorOffLeavesThePoolForHealth() {
        runner("sm.dp.health.enabled=true", "sm.dp.metrics.collect.enabled=false").run(context -> {
            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
            assertEquals(1, context.getBeansOfType(SshSessionPool.class).size());
        });
    }

    @Test
    void bothOffMeansNeitherAndTheDefaultIsBothOn() {
        runner("sm.dp.health.enabled=false", "sm.dp.metrics.collect.enabled=false").run(context -> {
            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
            assertTrue(context.getBeansOfType(SshSessionPool.class).isEmpty());
            assertTrue(context.getBeansOfType(SshMetricFetcher.class).isEmpty());
        });
        runner().run(context -> {
            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
            assertEquals(1, context.getBeansOfType(SshSessionPool.class).size());
            assertEquals(1, context.getBeansOfType(SshMetricFetcher.class).size());
        });
    }
}
