package com.solace.sm.cp.hierarchy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.hierarchy.ScopeNode;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.session.SessionId;
import com.solace.sm.cp.authz.AuthorizationGrantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * No database, and deliberately so: {@link HierarchyDirectoryIT}'s
 * {@code crossOrganizationPathParameterIsRefusedAgainstARealHeldGrant} only asserts the RESULT
 * (a {@link ResourceNotFoundException}), which stays green even if {@code requireOwnOrganization}
 * moved below the grant read — its name promises an ordering its body does not check. This class
 * makes the ordering itself the assertion: fakes that throw {@link AssertionError} the moment
 * either dependency is touched prove {@code requireOwnOrganization} short-circuits BEFORE a
 * single grant or tree row is read (§3.1 step 3 / {@code I5}).
 */
class ScopeVisibleHierarchyDirectoryOrderingTest {

    @Test
    void listSubOrganizationsOnAnotherOrganizationNeverReadsAGrantOrATreeRow() {
        UUID own = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ScopeVisibleHierarchyDirectory directory =
                new ScopeVisibleHierarchyDirectory(grantsThrowIfCalled(), treeThrowsIfCalled());

        assertThatThrownBy(() -> directory.listSubOrganizations(sessionFor(own), other))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listEnvironmentsOnAnotherOrganizationNeverReadsAGrantOrATreeRow() {
        UUID own = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ScopeVisibleHierarchyDirectory directory =
                new ScopeVisibleHierarchyDirectory(grantsThrowIfCalled(), treeThrowsIfCalled());

        assertThatThrownBy(
                () -> directory.listEnvironments(sessionFor(own), other, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static Session sessionFor(UUID organizationId) {
        Instant now = Instant.now();
        return new Session(new SessionId(UUID.randomUUID()), new PrincipalRef(UUID.randomUUID()),
                organizationId, false, now, now, now.plusSeconds(3600), now.plusSeconds(86_400));
    }

    private static AuthorizationGrantRepository grantsThrowIfCalled() {
        return principal -> {
            throw new AssertionError("grantsFor must not be called before requireOwnOrganization refuses "
                    + "a cross-organization path parameter");
        };
    }

    private static HierarchyTreeRepository treeThrowsIfCalled() {
        return new HierarchyTreeRepository() {
            @Override
            public boolean subOrganizationExists(UUID organizationId, UUID organizationUnitId) {
                throw new AssertionError("the tree must not be read before requireOwnOrganization refuses "
                        + "a cross-organization path parameter");
            }

            @Override
            public Optional<ScopeNode> organizationOf(UUID organizationId) {
                throw new AssertionError("the tree must not be read before requireOwnOrganization refuses "
                        + "a cross-organization path parameter");
            }

            @Override
            public List<ScopeNode> subOrganizationsOf(UUID organizationId) {
                throw new AssertionError("the tree must not be read before requireOwnOrganization refuses "
                        + "a cross-organization path parameter");
            }

            @Override
            public List<ScopeNode> environmentsOf(UUID organizationId, UUID organizationUnitId) {
                throw new AssertionError("the tree must not be read before requireOwnOrganization refuses "
                        + "a cross-organization path parameter");
            }
        };
    }
}
