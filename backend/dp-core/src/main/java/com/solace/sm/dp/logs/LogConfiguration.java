package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.apply.production.SecretStoreCredentialSource;
import com.solace.sm.dp.apply.runtime.ssh.HostKeyTypeRecorder;
import com.solace.sm.dp.apply.runtime.ssh.HostLogs;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import com.solace.sm.dp.inventory.TargetAddressConfiguration;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.secret.SecretResolver;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The log read side (plan 010 T9.1), reached through the data-plane aggregator.
 *
 * <p>It builds its OWN placement catalog and credential source - exactly as the health poller does - so it is live whether
 * or not {@code ProductionApplyConfiguration} is switched on: a log read is not part of the apply path. The transport
 * opens a session per read and closes it (it never borrows from the health poller's pool), so a read can neither queue
 * behind a probe nor hold a probe's slot.
 *
 * <p>Four knobs, each with a safe default: {@code sm.dp.logs.max-concurrent-reads} (8),
 * {@code sm.dp.logs.acquire-timeout} (2s), and, for a {@code before}-only read that pages backwards ({@code OR-108} (5)),
 * {@code sm.dp.logs.before-window} (15m, the width of one host read) and {@code sm.dp.logs.before-max-windows} (8, how far
 * one read steps back).
 */
@Configuration(proxyBeanMethods = false)
@Import(TargetAddressConfiguration.class)
public class LogConfiguration {

    @Bean
    HostLogs hostLogs(SecretResolver secretResolver, TargetAddressPolicy targetAddressPolicy,
            TargetConnectionRows targetConnectionRows) {
        return HostLogs.over(new SecretStoreCredentialSource(secretResolver), targetAddressPolicy,
                HostKeyTypeRecorder.over(targetConnectionRows));
    }

    @Bean
    LogService logService(PlacementRows placementRows, TargetConnectionRows targetConnectionRows,
            InstanceRows instanceRows, HostLogs hostLogs,
            @Value("${sm.dp.logs.max-concurrent-reads:8}") int maxConcurrentReads,
            @Value("${sm.dp.logs.acquire-timeout:2s}") Duration acquireTimeout,
            @Value("${sm.dp.logs.before-window:15m}") Duration beforeWindow,
            @Value("${sm.dp.logs.before-max-windows:8}") int beforeMaxWindows) {
        return new LogServiceImpl(new TablePlacementCatalog(placementRows, targetConnectionRows, instanceRows),
                hostLogs, maxConcurrentReads, acquireTimeout, beforeWindow, beforeMaxWindows);
    }
}
