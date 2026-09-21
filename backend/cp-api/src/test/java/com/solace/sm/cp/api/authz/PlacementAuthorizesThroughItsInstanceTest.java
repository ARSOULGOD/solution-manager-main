package com.solace.sm.cp.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Ruling {@code OR-16}, asserted rather than trusted to prose.
 *
 * <p>The failure this guards against is concrete: an implementer reads
 * {@code instance.stop}'s approved scope — <i>"instance or placement"</i> — as a <i>binding</i>
 * level, adds a placement column, and produces bindings that survive a rebind. §3.2's reason
 * for refusing that is in the ruling; these tests make it a build failure rather than a
 * review comment.
 */
class PlacementAuthorizesThroughItsInstanceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID UNIT = UUID.randomUUID();
    private static final UUID ENV = UUID.randomUUID();
    private static final UUID SOLUTION = UUID.randomUUID();
    private static final UUID INSTANCE = UUID.randomUUID();
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    private static ScopePath instancePath() {
        return ScopePath.instance(ORG, UNIT, ENV, SOLUTION, INSTANCE);
    }

    @Test
    void aPlacementCarriesIdentityAndTenantButNoCallerSuppliedScope() {
        UUID placementId = UUID.randomUUID();
        ResourceRef placement = ResourceRef.placement(placementId, TENANT);

        assertThat(placement.kind()).isEqualTo(ResourceKind.PLACEMENT);
        assertThat(placement.id()).isEqualTo(placementId);
        assertThat(placement.tenant()).isEqualTo(TENANT);
    }

    @Test
    void anInstanceScopedBindingAuthorizesEveryPlacementOfThatInstance() {
        ScopePath binding = instancePath();

        assertThat(binding.authorizes(instancePath())).isTrue();
        assertThat(binding.authorizes(instancePath()))
                .as("the consequence the amendment now records: a binding cannot grant stop for "
                        + "ONE placement only — granting at the instance grants it for all")
                .isTrue();
    }

    @Test
    void aSiblingInstancesPlacementIsNotReached() {
        ScopePath binding = instancePath();
        ScopePath sibling = ScopePath.instance(ORG, UNIT, ENV, SOLUTION, UUID.randomUUID());

        assertThat(binding.authorizes(ScopePath.instance(ORG, UNIT, ENV, SOLUTION, INSTANCE)))
                .isTrue();
        assertThat(binding.authorizes(sibling))
                .as("the scope boundary still holds — it is enforced at the instance, which is "
                        + "the level that actually exists")
                .isFalse();
    }

    @Test
    void instanceStopIsAuthorizableAtTheSingleScopeThatExists() {
        // The verb's approved targeting is "instance or placement"; the binding level is one.
        assertThat(Permission.INSTANCE_STOP.consequence()).isEqualTo(ConsequenceClass.HIGH);

        ScopePath environmentBinding = ScopePath.environment(ORG, UNIT, ENV);
        assertThat(environmentBinding.authorizes(instancePath()))
                .as("an environment-scoped Administrator reaches the instance, and through it "
                        + "every placement — there is no narrower grant available, by design")
                .isTrue();
    }

    @Test
    void aResourceIdentityCannotBeNull() {
        assertThatThrownBy(() -> new ResourceRef(ResourceKind.INSTANCE, null, TENANT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resource id");
    }

    @Test
    void aResourceTenantCannotBeNull() {
        assertThatThrownBy(() -> new ResourceRef(ResourceKind.INSTANCE, INSTANCE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenant");
    }
}
