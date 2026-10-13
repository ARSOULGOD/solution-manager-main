package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.connection.ConnectionCredentialService;
import com.solace.sm.dp.apply.runtime.connection.ConnectionCredentialServiceImpl;
import com.solace.sm.dp.apply.runtime.connection.RemoteServerConnectionTester;
import com.solace.sm.dp.apply.runtime.connection.SolaceBrokerConnectionTester;
import com.solace.sm.dp.secret.SecretResolver;
import com.solace.sm.dp.secret.SecretStore;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * {@code createConnection}/{@code updateConnection}'s {@code testBeforeSave} as a bean (plan 013 T4, {@code BE-D66}).
 * Imported by {@link InventoryConfiguration}, beside {@link TargetConnectionTestConfiguration}.
 *
 * <p>Wires the Task 3 testers over the SAME {@code strictTargetAddressPolicy} bean Target registration uses
 * ({@link TargetAddressConfiguration}, imported here as every configuration whose connector reaches a host does),
 * closing the obligation Task 3 left open: the testers' constructors took the policy explicitly so this module
 * could not build one over a permissive stand-in by accident.
 */
@Configuration(proxyBeanMethods = false)
@Import(TargetAddressConfiguration.class)
class ConnectionCredentialConfiguration {

    @Bean
    SolaceBrokerConnectionTester solaceBrokerConnectionTester(SecretResolver secretResolver,
            TargetAddressPolicy targetAddressPolicy, @Qualifier("smDataClock") ObjectProvider<Clock> clock) {
        return new SolaceBrokerConnectionTester(secretResolver, targetAddressPolicy,
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    RemoteServerConnectionTester remoteServerConnectionTester(SecretResolver secretResolver,
            TargetAddressPolicy targetAddressPolicy, @Qualifier("smDataClock") ObjectProvider<Clock> clock) {
        return new RemoteServerConnectionTester(secretResolver, targetAddressPolicy,
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ConnectionCredentialService connectionCredentialService(SecretStore secretStore,
            SolaceBrokerConnectionTester solaceBrokerConnectionTester,
            RemoteServerConnectionTester remoteServerConnectionTester) {
        return new ConnectionCredentialServiceImpl(secretStore, solaceBrokerConnectionTester,
                remoteServerConnectionTester);
    }
}
