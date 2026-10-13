package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.logs.LogLevelService;
import com.solace.sm.dp.apply.production.SecretStoreCredentialSource;
import com.solace.sm.dp.apply.runtime.ssh.HostKeyTypeRecorder;
import com.solace.sm.dp.apply.runtime.ssh.HostProbe;
import com.solace.sm.dp.apply.runtime.ssh.SshSessionPool;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import com.solace.sm.dp.inventory.TablePlacementCatalog;
import com.solace.sm.dp.inventory.TargetAddressConfiguration;
import com.solace.sm.dp.inventory.TargetAddressPolicy;
import com.solace.sm.dp.inventory.TargetConnectionRows;
import com.solace.sm.dp.secret.SecretResolver;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The live log-level read (plan 010 T9.3, OR-103), reached through the data-plane aggregator.
 *
 * <p>{@link LogLevelService} is a bounded read on the request thread, so it shares the health poller's SSH session pool
 * when one exists (its probe slots bound the concurrency, and a reader never opens a second connection to a target the
 * poller already holds). With {@code sm.dp.health.enabled=false} there is no pool, and the read falls back to a
 * connection per call through the same fixed command: still bounded by the SSH operation timeout, and never an apply
 * path ({@code SshSessionPool} is not an apply class, and the arch rules keep the HTTP layer from holding a transport).
 */
@Configuration(proxyBeanMethods = false)
@Import(TargetAddressConfiguration.class)
public class LogLevelConfiguration {

    /** The read's own bound, inside the 15 s SSH operation timeout and the probe's 10 s ceiling. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    LogLevelService logLevelService(PlacementRows placementRows, TargetConnectionRows targetRows,
            InstanceRows instanceRows, SecretResolver secretResolver, ObjectProvider<SshSessionPool> pool,
            ConfigRecordService configRecords, TargetAddressPolicy targetAddressPolicy) {
        SshSessionPool shared = pool.getIfAvailable();
        HostProbe probe = shared != null ? shared
                : HostProbe.over(new SecretStoreCredentialSource(secretResolver), targetAddressPolicy,
                        HostKeyTypeRecorder.over(targetRows));
        return new LogLevelServiceImpl(new TablePlacementCatalog(placementRows, targetRows, instanceRows), probe,
                READ_TIMEOUT, (organization, instance, logger) -> java.util.Optional
                        .ofNullable(configRecords.configuredLogLevels(organization, instance).get(logger)));
    }
}
