package com.solace.sm.dp.apply.persistence;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The log side's own table access (plan 016 T9), imported by {@code LogConfiguration} so the log feature does not depend on
 * the agent-gateway persistence configuration for its data. It lives in the persistence package because the architecture
 * confines {@code org.springframework.jdbc} there.
 *
 * <p>The ONE bean is both the persistence port of the cap service and the {@code AgentLogRetentionSource} the gateway reads when
 * a session opens. It is named distinctly from the service bean ({@code placementLogRetention}); two same-named beans would fail
 * the launcher at start.
 */
@Configuration(proxyBeanMethods = false)
public class LogPersistenceConfiguration {

    @Bean
    JdbcPlacementLogRetentionRepository placementLogRetentionRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcPlacementLogRetentionRepository(smDataJdbc);
    }
}
