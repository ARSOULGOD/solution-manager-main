package com.solace.sm.dp.inventory;

import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.apply.runtime.ssh.SshHostKeyScanService;
import com.solace.sm.dp.apply.runtime.ssh.SshHostKeyScanner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The address rule and the host-key scan as beans (plan 013 T1.2, D-REG section 7.3).
 *
 * <p>Every configuration whose connector reaches a target {@code @Import}s this class (Spring registers it once), so a
 * connector can never be built without a policy, and {@code DataPlaneConfiguration} imports it for the scan service. The
 * policy bean is the <b>strict</b> one and there is no property that swaps it: the permissive implementation exists only
 * in test sources, where an integration test's own configuration declares a {@code @Primary} bean of the same type. An
 * {@code arch-tests} rule fails the build if any other main-source class implements {@link TargetAddressPolicy}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TargetAddressProperties.class)
public class TargetAddressConfiguration {

    @Bean
    TargetAddressPolicy strictTargetAddressPolicy(TargetAddressProperties properties) {
        return new StrictTargetAddressPolicy(properties);
    }

    @Bean
    HostKeyScanService hostKeyScanService(TargetAddressPolicy targetAddressPolicy) {
        return new SshHostKeyScanService(targetAddressPolicy, new SshHostKeyScanner());
    }
}
