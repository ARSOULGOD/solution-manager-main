package com.solace.sm.dp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.api.agw.AgentLogRetentionSource;
import com.solace.sm.dp.apply.persistence.JdbcPlacementLogRetentionRepository;
import com.solace.sm.dp.logs.PlacementLogRetention;
import com.solace.sm.dp.logs.PlacementLogRetentionRows;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Plan 016 T9 review C1: the aggregator's bean DEFINITIONS (no bean is created) must register with overriding off, as under
 * {@code SpringApplication}, and the log cap feature must contribute exactly one repository, one service and one source.
 */
class DataPlaneLogRetentionWiringTest {

    @Test
    void theAggregatorRegistersWithoutAnOverrideAndOffersOneRetentionSource() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.setAllowBeanDefinitionOverriding(false);
        new AnnotatedBeanDefinitionReader(context).register(DataPlaneConfiguration.class);
        ConfigurationClassPostProcessor processor = new ConfigurationClassPostProcessor();
        processor.setEnvironment(context.getEnvironment());
        processor.setResourceLoader(context);
        assertDoesNotThrow(() -> processor.postProcessBeanDefinitionRegistry(context));    // BeanDefinitionOverrideException before the fix

        var factory = context.getBeanFactory();
        assertEquals(1, factory.getBeanNamesForType(AgentLogRetentionSource.class, true, false).length);
        assertEquals(1, factory.getBeanNamesForType(JdbcPlacementLogRetentionRepository.class, true, false).length);
        assertEquals(1, factory.getBeanNamesForType(PlacementLogRetentionRows.class, true, false).length);
        assertEquals(1, factory.getBeanNamesForType(PlacementLogRetention.class, true, false).length);
        assertEquals(1, factory.getBeanNamesForType(com.solace.sm.dp.api.logs.LogExportService.class, true, false).length,
                "the export service is wired exactly once (plan 016 T10)");
    }
}
