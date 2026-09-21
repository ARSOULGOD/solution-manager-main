package com.solace.sm.launcher;

import com.solace.sm.dp.apply.engine.ApplyEngineConfiguration;
import com.solace.sm.dp.apply.persistence.ApplyPersistenceConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Composes configurations that already own their own beans.
 *
 * <p>There is deliberately no control-plane binding here: the gateway's identity/authentication
 * composition is supplied by its owning slice.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = "com.solace.sm.gateway")
@Import({ApplyPersistenceConfiguration.class, ApplyEngineConfiguration.class})
public class SolutionManagerConfiguration {
}
