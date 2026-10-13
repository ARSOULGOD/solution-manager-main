package com.solace.sm.dp.home;

import com.solace.sm.dp.api.home.AttentionService;
import com.solace.sm.dp.api.home.InventorySummaryService;
import com.solace.sm.dp.health.PlacementHealthRows;
import com.solace.sm.dp.inventory.InstanceRows;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The home reads (plan 010 T10.5), reached through the data-plane aggregator. Persisted rows only: no poller, no host,
 * no secret, so there is nothing to wait for at boot beyond the tables the inventory, health and apply configurations
 * already check. The {@link ApplyAttentionRows} bean is {@code HomePersistenceConfiguration}'s, beside the other JDBC
 * repositories.
 */
@Configuration(proxyBeanMethods = false)
public class HomeConfiguration {

    @Bean
    InventorySummaryService inventorySummaryService(InstanceRows instanceRows, PlacementHealthRows placementHealthRows) {
        return new InventorySummaryServiceImpl(instanceRows, placementHealthRows);
    }

    @Bean
    AttentionService attentionService(InstanceRows instanceRows, PlacementHealthRows placementHealthRows,
            ApplyAttentionRows applyAttentionRows) {
        return new AttentionServiceImpl(instanceRows, placementHealthRows, applyAttentionRows);
    }
}
