package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.ConnectionRuntimeSpec;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Plan 015: the gateway projection seam's repository half, against a live sm_data (V038/V039). */
class ConnectionRuntimeProjectionIT {

    private static NamedParameterJdbcTemplate superuser;
    private static JdbcTargetConnectionRepository repository;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        repository = new JdbcTargetConnectionRepository(SmDataPostgres.runtimeJdbc());
    }

    private static ConnectionRuntimeSpec spec(UUID connectionId) {
        return new ConnectionRuntimeSpec(connectionId, "vm-9.corp.example", 22, "sm",
                "SHA256:" + "Q".repeat(43), UUID.randomUUID(), "/srv/sm", "sm-");
    }

    @Test
    void upsertThenReadIsLive() {
        UUID tenant = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        repository.upsertConnectionRuntime(tenant, spec(connectionId));

        assertTrue(repository.connectionRuntimeLive(tenant, connectionId));
    }

    @Test
    void upsertTwiceReplacesHostAndPort() {
        UUID tenant = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        repository.upsertConnectionRuntime(tenant, spec(connectionId));

        ConnectionRuntimeSpec changed = new ConnectionRuntimeSpec(connectionId, "vm-10.corp.example", 2222, "sm",
                "SHA256:" + "Q".repeat(43), UUID.randomUUID(), "/srv/sm", "sm-");
        repository.upsertConnectionRuntime(tenant, changed);

        String host = superuser.queryForObject("SELECT host FROM sm_data.connection_runtime"
                        + " WHERE tenant_id = :t AND connection_id = :c AND retired_at IS NULL",
                new MapSqlParameterSource().addValue("t", tenant).addValue("c", connectionId), String.class);
        assertEquals("vm-10.corp.example", host);
    }

    @Test
    void retireMakesItNotLive() {
        UUID tenant = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        repository.upsertConnectionRuntime(tenant, spec(connectionId));

        repository.retireConnectionRuntime(tenant, connectionId);

        assertFalse(repository.connectionRuntimeLive(tenant, connectionId));
    }
}
