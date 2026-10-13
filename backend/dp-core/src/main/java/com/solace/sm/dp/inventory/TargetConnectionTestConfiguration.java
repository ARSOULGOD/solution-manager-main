package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.TargetConnectionTester;
import com.solace.sm.dp.apply.production.SecretStoreCredentialSource;
import com.solace.sm.dp.apply.runtime.ssh.SshTargetConnectionTester;
import com.solace.sm.dp.secret.SecretResolver;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * {@code testTargetConnection} as a bean (plan 013 T1.5, OQ-9 (a)). Imported by {@link InventoryConfiguration}, so it
 * reaches production through the line that already exists in {@code DataPlaneConfiguration} and needs no edit there.
 *
 * <p>It builds its OWN credential source over the secret store rather than taking the apply path's bean: that bean exists
 * only when {@code sm.dp.apply.production.enabled} is true, and the connection test is not part of the apply path. It
 * imports {@link TargetAddressConfiguration}, as every configuration whose connector reaches a target does, so the probe is
 * built over the strict address policy and cannot be built without one.
 */
@Configuration(proxyBeanMethods = false)
@Import(TargetAddressConfiguration.class)
class TargetConnectionTestConfiguration {

    @Bean
    TargetConnectionTester targetConnectionTester(TargetConnectionRows targetConnectionRows,
            SecretResolver secretResolver, TargetAddressPolicy targetAddressPolicy,
            @Qualifier("smDataClock") ObjectProvider<Clock> clock) {
        return new SshTargetConnectionTester(targetConnectionRows, new SecretStoreCredentialSource(secretResolver),
                targetAddressPolicy, clock.getIfAvailable(Clock::systemUTC));
    }
}
