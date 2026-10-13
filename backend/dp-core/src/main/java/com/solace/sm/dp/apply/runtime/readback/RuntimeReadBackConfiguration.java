package com.solace.sm.dp.apply.runtime.readback;

import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.readback.RuntimeReadBackService;
import com.solace.sm.dp.api.secret.SecretCompare;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.RenderedConfigSource;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import com.solace.sm.dp.config.OverlayKeys;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.secret.SecretCompareImpl;
import com.solace.sm.dp.secret.SecretResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D-ACCEPT's data-plane beans (plan 010 T10.4), reached through the data-plane aggregator.
 *
 * <p>This class lives in {@code dp.apply.runtime..} on purpose: the only code allowed to name {@link RuntimeAdapter} is the
 * engine, this package tree, the demo and {@code ProductionApplyConfiguration}
 * ({@code onlyProductionCodeTouchesTheMutatingRuntimeAdapter}), and the read-back is a read of the seam, not an apply.
 *
 * <p>The adapter and the renderer are looked up lazily, as {@code ApplyServiceConfiguration} looks up the executor: with
 * {@code sm.dp.apply.production.enabled=false} and none supplied there is none, and a read-back then answers
 * {@code HOST_UNREADABLE} (a {@code 503}) instead of failing the context.
 */
@Configuration(proxyBeanMethods = false)
public class RuntimeReadBackConfiguration {

    /**
     * The shared overlay predicate ({@code BE-D37}): the baseline paths of the function the stage and the renderer use. The
     * configured-level paths are per generation and join it in the service ({@link OverlayKeys#ownsIn}), so the read-back
     * strips exactly the keys SM wrote and nothing else: an operator's own {@code logging.level.<other>} stays theirs.
     */
    @Bean
    SmOverlayKeys smOverlayKeys() {
        return OverlayKeys::ownsBaseline;
    }

    /** The compare-only port: the one place a read-back can reach a stored secret, and only to answer same or differs. */
    @Bean
    SecretCompare secretCompare(SecretResolver secretResolver) {
        return new SecretCompareImpl(secretResolver);
    }

    @Bean
    RuntimeReadBackService runtimeReadBackService(PlacementRows placementRows, ObjectProvider<RuntimeAdapter> adapter,
            ObjectProvider<RenderedConfigSource> renderer, SecretCompare secretCompare, ConfigHasher configHasher,
            SmOverlayKeys smOverlayKeys, ConfigRecordService configRecords) {
        return new RuntimeReadBackServiceImpl(
                (tenant, instance, placement) -> placementRows.findLive(tenant, instance, placement).isPresent(),
                adapter::getIfAvailable, renderer::getIfAvailable, secretCompare, configHasher, smOverlayKeys,
                (organization, instance) -> configRecords.configuredLogLevels(organization, instance).keySet().stream()
                        .map(logger -> OverlayKeys.LEVEL_PREFIX + logger).collect(java.util.stream.Collectors.toSet()));
    }
}
