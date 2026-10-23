package com.solace.sm.dp.logs;

import com.solace.sm.dp.api.logs.LogPageView;
import com.solace.sm.dp.api.logs.LogService;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.PlacementTransports;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link LogService} the data plane exposes (plan 016 task 8): the transport of the placement's target, read from the
 * tables on every call and bound by {@code (organization, instance, placement)} ({@link PlacementTransports}, the same
 * discriminator the apply router uses), picks the SSH implementation or the agent one. There is no global switch.
 *
 * <p>A placement the tables do not know goes to the SSH path, which declares it unresolvable; a transport this build does
 * not know is declared, never defaulted to the nearest one.
 */
public final class RoutingLogService implements LogService {

    static final String UNSUPPORTED_TRANSPORT = "the placement's logs are not available";

    private final PlacementTransports transports;
    private final LogService ssh;
    private final LogService agent;

    public RoutingLogService(PlacementTransports transports, LogService ssh, LogService agent) {
        this.transports = Objects.requireNonNull(transports, "transports");
        this.ssh = Objects.requireNonNull(ssh, "ssh");
        this.agent = Objects.requireNonNull(agent, "agent");
    }

    @Override
    public LogPageView read(UUID organizationId, UUID instanceId, UUID placementId, int limit, Instant after,
            Instant before) {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(placementId, "placementId");
        Optional<String> transport = transports.transportOf(new PlacementRef(new TenantId(organizationId),
                new InstanceId(instanceId), new PlacementId(placementId)));
        if (transport.isEmpty() || PlacementTransports.SSH_DOCKER.equals(transport.get())) {
            return ssh.read(organizationId, instanceId, placementId, limit, after, before);
        }
        if (PlacementTransports.AGENT.equals(transport.get())) {
            return agent.read(organizationId, instanceId, placementId, limit, after, before);
        }
        return new LogPageView(List.of(), Optional.of(UNSUPPORTED_TRANSPORT));
    }
}
