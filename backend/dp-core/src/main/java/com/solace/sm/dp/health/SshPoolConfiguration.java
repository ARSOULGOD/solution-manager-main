package com.solace.sm.dp.health;

import com.solace.sm.dp.apply.production.SecretStoreCredentialSource;
import com.solace.sm.dp.apply.runtime.ssh.HostKeyTypeRecorder;
import com.solace.sm.dp.apply.runtime.ssh.SshSessionPool;
import com.solace.sm.dp.apply.runtime.ssh.SshSessionPoolProperties;
import com.solace.sm.dp.inventory.TargetAddressConfiguration;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.secret.SecretResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The shared per-target SSH session pool (plan 010 T7.1), for the health poller AND plan 011's collector (W3-10).
 *
 * <p>Collection and health are independent (B/D2, UI-D8), so the pool exists when EITHER needs it: switching health polling
 * off to cut SSH load must not withdraw SSH metric collection. {@link #NEEDED} is the one expression both the pool and the
 * collector's SSH fetcher are conditional on, so the two can never disagree.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SshSessionPoolProperties.class)
@Import(TargetAddressConfiguration.class)
public class SshPoolConfiguration {

    /** True when the health poller or the metrics collector is enabled (both default to enabled). */
    public static final String NEEDED = "${sm.dp.health.enabled:true} or ${sm.dp.metrics.collect.enabled:true}";

    @Bean(destroyMethod = "close")
    @ConditionalOnExpression(NEEDED)
    SshSessionPool healthSshSessionPool(SecretResolver secretResolver, SshSessionPoolProperties properties,
            TargetAddressPolicy targetAddressPolicy, TargetConnectionRows targetConnectionRows) {
        return new SshSessionPool(new SecretStoreCredentialSource(secretResolver), properties, targetAddressPolicy,
                HostKeyTypeRecorder.over(targetConnectionRows));
    }
}
