package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.agw.AgentLogPort;
import com.solace.sm.dp.api.logs.LogExportService;
import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
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
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * <p>The {@code LogService} bean routes by the placement's transport ({@link RoutingLogService}): an SSH placement is read
 * over SSH, an agent placement through the gateway's {@code AgentLogPort} ({@link AgentLogService}, plan 016), with
 * {@code sm.dp.logs.agent-request-deadline} (5s) and {@code sm.dp.logs.agent-max-concurrent-reads} (8: a GLOBAL bound, deliberately above the gateway's per-agent bound of 4,
 * which is where a single agent's BUSY comes from).
 *
 * <p>Four knobs, each with a safe default: {@code sm.dp.logs.max-concurrent-reads} (8),
 * {@code sm.dp.logs.acquire-timeout} (2s), and, for a {@code before}-only read that pages backwards ({@code OR-108} (5)),
 * {@code sm.dp.logs.before-window} (15m, the width of one host read) and {@code sm.dp.logs.before-max-windows} (8, how far
 * one read steps back).
 */
@Configuration(proxyBeanMethods = false)
@Import({TargetAddressConfiguration.class, com.solace.sm.dp.apply.persistence.LogPersistenceConfiguration.class})
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
            @Value("${sm.dp.logs.before-max-windows:8}") int beforeMaxWindows,
            @Value("${sm.dp.logs.agent-request-deadline:5s}") Duration agentRequestDeadline,
            @Value("${sm.dp.logs.agent-max-concurrent-reads:8}") int agentMaxConcurrentReads,
            ObjectProvider<PlacementTransports> placementTransports, ObjectProvider<AgentLogPort> agentLogPort) {
        LogService ssh = new LogServiceImpl(new TablePlacementCatalog(placementRows, targetConnectionRows, instanceRows),
                hostLogs, maxConcurrentReads, acquireTimeout, beforeWindow, beforeMaxWindows);
        // the transport is read from the tables per call; a context with no agent path (no PlacementTransports bean)
        // has only SSH placements, and one with no gateway port answers an agent placement "not connected"
        PlacementTransports transports = placementTransports.getIfAvailable(() -> PlacementTransports.NONE);
        AgentLogPort port = agentLogPort.getIfAvailable(AgentLogService::absentPort);
        LogService agent = new AgentLogService(transports, placementRows, port, agentRequestDeadline, agentMaxConcurrentReads,
                Duration.ZERO);      // a full agent pool is an immediate busy page, never a queue
        return new RoutingLogService(transports, ssh, agent);
    }

    /**
     * The bulk export of an agent placement's collected log (plan 016 T10), streamed as a download by the console gateway.
     * <ul>
     *   <li>{@code sm.dp.logs.export-max-bytes} - total bytes of log line text one export carries before it stops with a
     *       trailing marker; default 52428800 (50 MiB).</li>
     *   <li>{@code sm.dp.logs.export-deadline} - the longest one export runs; default 5m. An agent that goes away without a
     *       final chunk ends the export at this bound with a marker, it never hangs.</li>
     *   <li>{@code sm.dp.logs.export-max-concurrent} - exports in flight at once on this data plane; default 2. One more is
     *       a declared busy marker, never a queue (the gateway also bounds requests per agent).</li>
     * </ul>
     */
    @Bean
    LogExportService logExportService(PlacementRows placementRows, ObjectProvider<PlacementTransports> placementTransports,
            ObjectProvider<AgentLogPort> agentLogPort,
            @Value("${sm.dp.logs.export-max-bytes:52428800}") long exportMaxBytes,
            @Value("${sm.dp.logs.export-deadline:5m}") Duration exportDeadline,
            @Value("${sm.dp.logs.export-max-concurrent:2}") int exportMaxConcurrent) {
        return new AgentLogExportService(placementTransports.getIfAvailable(() -> PlacementTransports.NONE), placementRows,
                agentLogPort.getIfAvailable(AgentLogService::absentPort), exportDeadline, exportMaxBytes,
                exportMaxConcurrent);
    }

    /**
     * The per-placement agent log cap (plan 016 T9): internal, no REST write. {@code sm.dp.logs.agent-max-cap-bytes} is the
     * largest cap accepted, default 1 GiB (1073741824): well above the agent's own default so an operator can raise it, and
     * bounded so one placement cannot be told to claim a host's disk. 0 (collection off) is always allowed.
     */
    @Bean
    PlacementLogRetention placementLogRetention(PlacementLogRetentionRows rows, ObjectProvider<AgentLogPort> agentLogPort,
            @Qualifier("smDataClock") Clock smDataClock,
            @Value("${sm.dp.logs.agent-max-cap-bytes:1073741824}") long agentMaxCapBytes) {
        return new PlacementLogRetention(rows, agentLogPort.getIfAvailable(AgentLogService::absentPort), smDataClock,
                agentMaxCapBytes);
    }
}
