package com.solace.sm.dp.config;

import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.ObservedStateService;
import com.solace.sm.dp.api.config.SecretReferences;
import com.solace.sm.dp.api.health.HealthService;
import com.solace.sm.dp.secret.SecretReferencesImpl;
import com.solace.sm.dp.secret.SecretRows;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The data plane's read side for the designer and the ladder (plan 010 T5.3), reached through the DP aggregator.
 * Both beans are exposed by their {@code dp-api} interface so the gateway depends on {@code dp-api} only.
 */
@Configuration(proxyBeanMethods = false)
public class ObservedStateConfiguration {

    @Bean
    ObservedStateService observedStateService(ConfigRecordService configRecordService, LadderRows ladderRows,
            HealthService healthService) {
        return new ObservedStateServiceImpl(new ConfigLadderReader(configRecordService, ladderRows), healthService);
    }

    /** The one writer of {@code reached_rung}; {@code OutcomeService} calls it inside a landing (T6.5). */
    @Bean
    LadderRecorder ladderRecorder(LadderRows ladderRows) {
        return new LadderRecorder(ladderRows);
    }

    @Bean
    SecretReferences secretReferences(SecretRows secretRows) {
        return new SecretReferencesImpl(secretRows);
    }
}
