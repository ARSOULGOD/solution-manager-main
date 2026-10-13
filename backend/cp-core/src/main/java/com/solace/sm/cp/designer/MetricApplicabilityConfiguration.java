package com.solace.sm.cp.designer;

import com.solace.sm.cp.api.designer.MetricApplicability;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the metric applicability port (plan 011 P11-4.1, M-Q15) BY ITS {@code cp-api} INTERFACE TYPE, so the console
 * gateway depends on {@code cp-api} only ({@code I7}). Pure (no datasource, no clock, no I/O), so there is nothing to
 * qualify against the Data Plane's beans. Imported by {@link com.solace.sm.cp.config.ControlPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class MetricApplicabilityConfiguration {

    @Bean
    MetricApplicability metricApplicability() {
        return new MetricApplicabilityImpl();
    }
}
