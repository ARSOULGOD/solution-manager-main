package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * V018 admits a no-actor {@code ENTITLEMENT_SET} only from the literal client address {@code 'operator-cli'}; the
 * operator commands send {@link SmControlLockoutRecoveryMain#OPERATOR_CLIENT_ADDRESS}. A pure test that the two cannot
 * drift apart without a failure that needs no database (W2 review W2D-3). The database half is
 * {@code EntitlementMainAtHeadIT}.
 */
class OperatorClientAddressLiteralTest {

    @Test
    void theCommandsClientAddressIsExactlyTheLiteralV018AdmitsForTheNoActorEntitlementSet() throws IOException {
        String sql;
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/sm_control/V018__registration_api.sql")) {
            sql = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains("v_client <> '" + EntitlementMain.OPERATOR_CLIENT_ADDRESS + "'");
        assertThat(EntitlementMain.OPERATOR_CLIENT_ADDRESS).isEqualTo("operator-cli");
        assertThat(EntitlementMain.OPERATOR_CLIENT_ADDRESS).isEqualTo(SmControlLockoutRecoveryMain.OPERATOR_CLIENT_ADDRESS);
    }
}
