package com.solace.sm.cp.agent;

import com.solace.sm.cp.api.agent.AgentRegistration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** An in-memory {@link AgentRegistry} that records every write in order, so a test can see what happened before what. */
final class FakeAgentRegistry implements AgentRegistry {

    static final UUID SOLUTION = UUID.fromString("00000000-0000-0000-0000-0000000000a5");
    final List<String> events;
    final Map<UUID, Enrollment> rows = new LinkedHashMap<>();
    final List<NewRegistration> inserted = new ArrayList<>();
    boolean active = true;

    FakeAgentRegistry(List<String> events) {
        this.events = events;
    }

    Enrollment pending(UUID agentId, UUID org, UUID unit, UUID env, String name, byte[] tokenDigest, Instant expiresAt,
            UUID createdBy) {
        AgentRegistration registration = new AgentRegistration(agentId, null, List.of("apply-config", "lifecycle"), 1, 1,
                Duration.ofSeconds(300), "solution-manager-agent", Duration.ofSeconds(5), true);
        Enrollment row = new Enrollment(registration, org, unit, env, name, "/opt/sm/config", "sm-", createdBy,
                expiresAt, null, null, SOLUTION, "cache", "1");
        rows.put(agentId, row);
        digests.put(agentId, tokenDigest);
        return row;
    }

    private final Map<UUID, byte[]> digests = new LinkedHashMap<>();

    @Override
    public void insert(NewRegistration registration) {
        events.add("registry.insert");
        inserted.add(registration);
    }

    @Override
    public Optional<Enrollment> lockByTokenDigest(byte[] tokenDigest) {
        return rows.values().stream().filter(r -> active
                && Arrays.equals(digests.get(r.registration().agentId()), tokenDigest)).findFirst();
    }

    @Override
    public void consume(UUID agentId, byte[] csrDigest, UUID targetId,
            AgentCertificateAuthority.IssuedCertificate issued, Instant usedAt) {
        events.add("registry.consume");
        Enrollment old = rows.get(agentId);
        AgentRegistration r = old.registration();
        rows.put(agentId, new Enrollment(new AgentRegistration(agentId, targetId, r.capabilities(),
                r.agentRevocationEpoch(), r.fleetRevocationEpoch(), r.leaseTtl(), r.audience(), r.admissionMargin(),
                r.active()), old.organizationId(), old.organizationUnitId(), old.environmentId(), old.targetName(),
                old.allowedConfigRoot(), old.allowedContainerPrefix(), old.createdBy(), old.expiresAt(), csrDigest,
                issued, old.catalogSolutionId(), old.solution(), old.packageVersion()));
    }

    /** Set by a test to mark the agent revoked, as the DB row would read. */
    boolean revoked;

    @Override
    public Optional<Registered> find(UUID organizationId, UUID agentId) {
        Enrollment row = rows.get(agentId);
        if (row == null || !row.organizationId().equals(organizationId)) return Optional.empty();
        AgentRegistration r = row.registration();
        AgentRegistration shown = revoked ? new AgentRegistration(r.agentId(), r.targetId(), r.capabilities(),
                r.agentRevocationEpoch(), r.fleetRevocationEpoch(), r.leaseTtl(), r.audience(), r.admissionMargin(), false) : r;
        return Optional.of(new Registered(shown,
                row.certificate() == null ? null : row.certificate().chainDer().get(0),
                row.certificate() == null ? null : row.certificate().validTo()));
    }

    @Override
    public Map<UUID, AgentRegistration> findAll(Collection<UUID> agentIds) {
        Map<UUID, AgentRegistration> result = new LinkedHashMap<>();
        agentIds.forEach(id -> {
            if (rows.containsKey(id)) result.put(id, rows.get(id).registration());
        });
        return result;
    }
}
