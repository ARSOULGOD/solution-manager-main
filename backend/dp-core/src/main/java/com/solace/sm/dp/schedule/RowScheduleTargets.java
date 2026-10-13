package com.solace.sm.dp.schedule;

import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.inventory.InstanceLifecycle;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.apply.persistence.ApplyScopeRepository;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.inventory.InstanceRows;
import com.solace.sm.dp.inventory.PlacementRows;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ScheduleTargets} over the registry, the config of record and the apply journal (plan 012 T7, {@code OR-125} (5)).
 * A named class rather than an anonymous bean body so that an integration test measures THIS adapter - the instance filter
 * ({@code I5}), the generation read and the provenance read - instead of a hand-written stand-in (W5 review W5A-2).
 */
public final class RowScheduleTargets implements ScheduleTargets {

    private final InstanceRows instanceRows;
    private final PlacementRows placementRows;
    private final ConfigGenerationRows configGenerationRows;
    private final ApplyRequestRepository applyRequests;
    private final ApplyScopeRepository scopes;

    public RowScheduleTargets(InstanceRows instanceRows, PlacementRows placementRows,
            ConfigGenerationRows configGenerationRows, ApplyRequestRepository applyRequests, ApplyScopeRepository scopes) {
        this.instanceRows = Objects.requireNonNull(instanceRows, "instanceRows");
        this.placementRows = Objects.requireNonNull(placementRows, "placementRows");
        this.configGenerationRows = Objects.requireNonNull(configGenerationRows, "configGenerationRows");
        this.applyRequests = Objects.requireNonNull(applyRequests, "applyRequests");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    @Override
    public boolean instanceActive(UUID tenantId, UUID instanceId) {
        return instanceRows.find(tenantId, instanceId).map(r -> r.lifecycle() == InstanceLifecycle.ACTIVE).orElse(false);
    }

    @Override
    public boolean lockInstanceScopeAndCheckActive(UUID tenantId, UUID instanceId) {
        // OR-131: the apply_scope lock first (the order beginRetire holds), then the lifecycle read.
        if (!instanceActive(tenantId, instanceId)) {
            return false;
        }
        scopes.ensureScopeRow(new TenantId(tenantId), new InstanceId(instanceId));
        scopes.lockScope(new TenantId(tenantId), new InstanceId(instanceId));
        return instanceActive(tenantId, instanceId);
    }

    @Override
    public boolean placementLive(UUID tenantId, UUID instanceId, UUID placementId) {
        return placementRows.findLive(tenantId, instanceId, placementId).isPresent();
    }

    @Override
    public boolean generationStaged(UUID tenantId, UUID instanceId, long generation) {
        return configGenerationRows.view(new TenantId(tenantId), new InstanceId(instanceId), generation).isPresent();
    }

    @Override
    public Optional<AdmittedRequest> admittedUnder(UUID tenantId, UUID instanceId, UUID applyId) {
        TenantId tenant = new TenantId(tenantId);
        // The instance is part of the lookup (I5): an id that names another instance's request is not found.
        return applyRequests.find(tenant, new ApplyId(applyId))
                .filter(r -> r.scope().instance().value().equals(instanceId) && r.generation() != null)
                .map(r -> new AdmittedRequest(applyId, r.scope().placement().value(), r.generation(), r.requestedAt(),
                        configGenerationRows.view(tenant, new InstanceId(instanceId), r.generation())
                                .map(GenerationView::derivedFromGeneration).orElse(null)));
    }
}
