package com.solace.sm.cp.api.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The registry's three refusals. */
class OperationRegistryTest {

    @Test
    void localOperationsAreExactlyTheThreeAllowedDuringForcedRotation() {
        assertThat(LocalOperation.values()).containsExactlyInAnyOrder(
                LocalOperation.LOGOUT,
                LocalOperation.READ_OWN_IDENTITY,
                LocalOperation.ROTATE_OWN_CREDENTIAL);
    }

    @Test
    void anOperationResolvesToItsVerb() {
        OperationRegistry r = OperationRegistry.builder()
                .register("stopInstance", Permission.INSTANCE_STOP)
                .build();

        assertThat(r.permissionFor("stopInstance")).contains(Permission.INSTANCE_STOP);
        assertThat(r.size()).isEqualTo(1);
    }

    @Test
    void anUnregisteredOperationIsEmptyRatherThanDenied() {
        OperationRegistry r = OperationRegistry.builder().build();

        assertThat(r.permissionFor("undeclareInstance"))
                .as("empty means 501 — 'nobody may' — which is a different answer to the operator "
                        + "than 403's 'you may not'")
                .isEmpty();
    }

    @Test
    void registeringTheSameOperationTwiceIsRefused() {
        OperationRegistry.Builder b = OperationRegistry.builder()
                .register("getHealth", Permission.HEALTH_VIEW);

        assertThatThrownBy(() -> b.register("getHealth", Permission.INSTANCE_VIEW))
                .as("a silent replacement would change what authorizes an endpoint without "
                        + "anyone editing the endpoint")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    void aBlankOperationIdIsRefused() {
        assertThatThrownBy(() -> OperationRegistry.builder().register("  ", Permission.HEALTH_VIEW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRegistryIsUnmodifiableAfterBuild() {
        OperationRegistry r = OperationRegistry.builder()
                .register("getHealth", Permission.HEALTH_VIEW)
                .build();

        assertThatThrownBy(() -> r.all().put("sneak", Permission.CONFIG_APPLY))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
