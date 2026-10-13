package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.home.ApplyAttentionRows;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The persistence port for the home attention list (plan 010 T10.5). It reads {@code apply_request} and
 * {@code instance_state}, tables the apply and inventory configurations already require, so it declares no readiness
 * check of its own. Imported by {@code DataPlaneConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class HomePersistenceConfiguration {

    @Bean
    ApplyAttentionRows applyAttentionRows(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcApplyAttentionRepository(smDataJdbc);
    }
}
