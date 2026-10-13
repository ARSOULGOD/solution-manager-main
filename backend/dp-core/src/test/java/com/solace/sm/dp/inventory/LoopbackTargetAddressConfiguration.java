package com.solace.sm.dp.inventory;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The seam of plan 013 T1.2 (D-REG section 7.3, R7): an integration test that points a target at a loopback stub
 * {@code @Import}s this, and the data plane's connectors then use {@link PermissiveTargetAddressPolicy} instead of the
 * strict policy production wires. A <b>bean</b>, not a property: no setting reaches it in production, because it
 * exists only in test sources (dp-core publishes them as a test-jar for the launcher).
 */
@TestConfiguration(proxyBeanMethods = false)
public class LoopbackTargetAddressConfiguration {

    @Bean
    @Primary
    TargetAddressPolicy loopbackPermissiveTargetAddressPolicy() {
        return new PermissiveTargetAddressPolicy();
    }
}
