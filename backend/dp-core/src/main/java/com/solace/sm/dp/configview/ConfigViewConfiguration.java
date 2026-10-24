package com.solace.sm.dp.configview;

import com.solace.sm.dp.api.agw.AgentConfigViewPort;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import com.solace.sm.dp.apply.runtime.agent.AgentPlacementCatalog;
import com.solace.sm.dp.inventory.PlacementRows;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The agent config view (plan 017 task 5), reached through the data-plane aggregator. Three knobs, each with a safe default:
 * <ul>
 *   <li>{@code sm.dp.config-view.allow} - the dotted key paths the agent may return, comma-separated (each entry is trimmed);
 *       default {@link #DEFAULT_ALLOW}. An entry is an exact path or a {@code prefix.*} subtree; what is rejected at startup is
 *       listed on {@link AgentConfigViewService#validAllowList}. The default deliberately does NOT list {@code management.*}: that
 *       subtree holds Micrometer export credentials ({@code management.datadog.metrics.export.api-key},
 *       {@code management.dynatrace.metrics.export.api-token}, {@code management.influx.metrics.export.password},
 *       {@code management.otlp.metrics.export.headers.*} and their siblings). {@code info.*} is free-form text an MI may fill
 *       with anything; the owner is asked to confirm keeping it (DECISIONS.md open item 51).</li>
 *   <li>{@code sm.dp.config-view.timeout} - how long a view waits for the agent before it is abandoned
 *       ({@code AGENT_UNAVAILABLE}); default 10s, at most 1m. Set the gateway's {@code config-view-deadline} no longer than it.</li>
 *   <li>{@code sm.dp.config-view.max-bytes} - the output bound sent to the agent and re-checked here (UTF-8 bytes of path plus
 *       value); default 262144, between 1024 and 1048576 (the go-agent's hard cap).</li>
 * </ul>
 * At most {@link #MAX_CONCURRENT_VIEWS} views are in flight on this data plane; one more is {@code BUSY} at once (the gateway
 * also allows one per agent).
 *
 * <p>The bean is named {@code agentConfigViewService}, distinct from the gateway's {@code agentConfigViewPort}: two same-named
 * beans would fail the launcher at start (the plan 016 lesson). A context without the agent path (no {@link PlacementTransports}
 * bean) has only SSH placements; one without the gateway answers an agent placement {@code AGENT_UNAVAILABLE}.
 */
@Configuration(proxyBeanMethods = false)
public class ConfigViewConfiguration {

    static final int MAX_CONCURRENT_VIEWS = 8;

    /** The default allow list (revised in the plan 017 final review, I-1). */
    static final String DEFAULT_ALLOW =
            "server.port,management.server.port,management.endpoints.*,management.endpoint.*,logging.*,info.*";

    @Bean(destroyMethod = "close")
    AgentConfigViewService agentConfigViewService(PlacementRows placementRows,
            ObjectProvider<PlacementTransports> placementTransports, ObjectProvider<AgentPlacementCatalog> agentPlacementCatalog,
            ObjectProvider<AgentConfigViewPort> agentConfigViewPort,
            @Value("${sm.dp.config-view.allow:" + DEFAULT_ALLOW + "}") String allow,
            @Value("${sm.dp.config-view.timeout:10s}") Duration timeout,
            @Value("${sm.dp.config-view.max-bytes:262144}") int maxBytes) {
        return new AgentConfigViewService(placementRows, placementTransports.getIfAvailable(() -> PlacementTransports.NONE),
                agentPlacementCatalog.getIfAvailable(() -> placement -> Optional.empty()),
                agentConfigViewPort.getIfAvailable(AgentConfigViewService::absentPort), allowList(allow), timeout, maxBytes,
                MAX_CONCURRENT_VIEWS);
    }

    /** The comma-separated property as a list, each entry trimmed; an empty entry is kept so validation rejects it. */
    static List<String> allowList(String raw) {
        return Arrays.stream(raw.split(",", -1)).map(String::trim).toList();
    }
}
