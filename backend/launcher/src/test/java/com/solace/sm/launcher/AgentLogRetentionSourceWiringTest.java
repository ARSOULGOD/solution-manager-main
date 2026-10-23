package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.solace.sm.agw.AgentGatewayConfiguration;
import com.solace.sm.agw.AgentGatewayPortSet;
import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentLogRetentionSource;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentTelemetryDeclarationsPort;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import com.solace.sm.dp.apply.persistence.JdbcPlacementLogRetentionRepository;
import com.solace.sm.dp.apply.persistence.LogPersistenceConfiguration;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Plan 016 T9 review C1: the gateway's port set receives the data plane's repository as its {@link AgentLogRetentionSource},
 * not {@code NONE}. The gateway's own bean method (package-private, as in its own test) is called with the provider the
 * Spring context hands out, so the resolution under test is Spring's, over the data plane's real configuration class.
 */
class AgentLogRetentionSourceWiringTest {

    private static <T> T inert(Class<T> port) {
        return port.cast(Proxy.newProxyInstance(port.getClassLoader(), new Class<?>[] {port}, (p, m, a) -> null));
    }

    @Configuration(proxyBeanMethods = false)
    static class StubJdbc {
        @Bean
        NamedParameterJdbcTemplate smDataJdbc() {
            return new NamedParameterJdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource());
        }
    }

    @Test
    void theGatewayPortSetIsGivenTheRepositoryNotNone() throws Exception {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(StubJdbc.class,
                LogPersistenceConfiguration.class)) {
            ObjectProvider<AgentLogRetentionSource> provider = context.getBeanProvider(AgentLogRetentionSource.class);
            Method factory = AgentGatewayConfiguration.class.getDeclaredMethod("agentGatewayPortSet", AgentEnrollmentPort.class,
                    AgentIdentityPort.class, AgentSessionPort.class, AgentLeasePort.class, AgentCommandPort.class,
                    ActualStatePort.class, AgentTelemetryPort.class, AgentAuditPort.class, AgentDiscoveryPort.class,
                    AgentTelemetryDeclarationsPort.class, ObjectProvider.class);
            factory.setAccessible(true);
            AgentGatewayPortSet ports = (AgentGatewayPortSet) factory.invoke(new AgentGatewayConfiguration(),
                    inert(AgentEnrollmentPort.class), inert(AgentIdentityPort.class), inert(AgentSessionPort.class),
                    inert(AgentLeasePort.class), inert(AgentCommandPort.class), inert(ActualStatePort.class),
                    inert(AgentTelemetryPort.class), inert(AgentAuditPort.class), inert(AgentDiscoveryPort.class),
                    inert(AgentTelemetryDeclarationsPort.class), provider);
            assertNotSame(AgentLogRetentionSource.NONE, ports.logRetention());
            assertSame(context.getBean(JdbcPlacementLogRetentionRepository.class), ports.logRetention());
        }
    }
}
