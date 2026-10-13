package com.solace.sm.cp.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.ScopePath;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The cost shape of the reach subset: bounded reads per call, never one authorization per item. */
class GrantReachAuthorizationTest {

    private final UUID organization = UUID.randomUUID();
    private final UUID unit = UUID.randomUUID();
    private final UUID environment = UUID.randomUUID();
    private final PrincipalRef principal = new PrincipalRef(UUID.randomUUID());

    @Test
    void oneGrantReadAndOneOrganizationReadServeAWholeListNotOnePerItem() {
        AtomicInteger grantReads = new AtomicInteger();
        AtomicInteger organizationReads = new AtomicInteger();
        AtomicInteger directoryReads = new AtomicInteger();
        GrantReachAuthorization reach = new GrantReachAuthorization(
                ignored -> {
                    organizationReads.incrementAndGet();
                    return Optional.of(organization);
                },
                ignored -> {
                    grantReads.incrementAndGet();
                    return List.of(new AuthorizationGrant(ScopePath.organization(organization),
                            Set.of(Permission.INSTANCE_VIEW)));
                },
                new ScopePathResolver(new ResourceScopeDirectory() {
                    @Override
                    public Optional<ScopePath> findScopePath(ResourceRef resource) {
                        directoryReads.incrementAndGet();
                        return Optional.of(ScopePath.environment(organization, unit, resource.id()));
                    }

                    @Override
                    public Map<UUID, ScopePath> findScopePaths(ResourceKind kind, UUID inOrganization,
                            Collection<UUID> ids) {
                        directoryReads.incrementAndGet();
                        Map<UUID, ScopePath> out = new LinkedHashMap<>();
                        ids.forEach(id -> out.put(id, ScopePath.environment(inOrganization, unit, id)));
                        return out;
                    }
                }));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(UUID.randomUUID());
        }

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ENVIRONMENT, ids))
                .hasSize(50);
        assertThat(grantReads).as("grants are read once for the whole list").hasValue(1);
        assertThat(organizationReads).as("the organization is read once for the whole list").hasValue(1);
        assertThat(directoryReads).as("the directory is read once for the whole list, not once per id")
                .hasValue(1);
    }

    /**
     * The default directory read (for a directory that has no batched query of its own) must keep the
     * contract the JDBC one enforces in SQL: only resources IN the asked organization come back.
     */
    @Test
    void theDefaultBatchedDirectoryReadStillFiltersToTheAskedOrganization() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID absent = UUID.randomUUID();
        ResourceScopeDirectory perId = resource -> {
            if (resource.id().equals(mine)) {
                return Optional.of(ScopePath.environment(organization, unit, mine));
            }
            if (resource.id().equals(theirs)) {
                return Optional.of(ScopePath.environment(UUID.randomUUID(), unit, theirs));
            }
            return Optional.empty();
        };

        assertThat(perId.findScopePaths(ResourceKind.ENVIRONMENT, organization, List.of(mine, theirs, absent)))
                .containsOnlyKeys(mine);
    }

    @Test
    void anEmptyListReadsNothingAndAnswersEmpty() {
        AtomicInteger reads = new AtomicInteger();
        GrantReachAuthorization reach = new GrantReachAuthorization(
                ignored -> {
                    reads.incrementAndGet();
                    return Optional.of(organization);
                },
                ignored -> {
                    reads.incrementAndGet();
                    return List.of();
                },
                new ScopePathResolver(resource -> Optional.empty()));

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ENVIRONMENT, List.of()))
                .isEmpty();
        assertThat(reads).hasValue(0);
    }

    @Test
    void theSubsetIsReturnedForTheRequestedIdsOnlyNeverMore() {
        UUID asked = UUID.randomUUID();
        GrantReachAuthorization reach = new GrantReachAuthorization(
                ignored -> Optional.of(organization),
                ignored -> List.of(new AuthorizationGrant(ScopePath.INSTALL, Set.of(Permission.INSTANCE_VIEW))),
                new ScopePathResolver(resource -> Optional.of(ScopePath.environment(organization, unit, environment))));

        assertThat(reach.authorizedSubset(principal, Permission.INSTANCE_VIEW, ResourceKind.ENVIRONMENT, List.of(asked)))
                .containsExactly(asked);
    }
}
