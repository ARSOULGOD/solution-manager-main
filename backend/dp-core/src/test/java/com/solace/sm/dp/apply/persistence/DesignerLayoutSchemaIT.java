package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OR-96 — {@code sm_data} V013: the per-instance presentation record for designer canvas layout, OUTSIDE
 * {@code config_generation}. Every statement runs as {@code sm_dp_runtime} (a privilege proof made as the superuser
 * proves nothing about the credential the application holds), and every refusal is asserted by SQLSTATE and name.
 */
@Testcontainers(disabledWithoutDocker = true)
class DesignerLayoutSchemaIT {

    private static final String GOOD = "{\"cache-1\":{\"x\":10,\"y\":-3.5}}";

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    private static MapSqlParameterSource row(UUID tenant, UUID instance, String layout) {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance).addValue("l", layout)
                .addValue("u", UUID.randomUUID()).addValue("now", OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static int insert(MapSqlParameterSource p) {
        return runtime.update("INSERT INTO sm_data.designer_layout (tenant_id, instance_id, layout, updated_by,"
                + " updated_at) VALUES (:t, :i, CAST(:l AS jsonb), :u, :now)", p);
    }

    @Test
    void theRuntimeRoleWritesALayoutForADeclaredInstanceAndItDrawsNoStamp() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        long before = runtime.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", row(tenant, instance, GOOD), Long.class);

        assertEquals(1, insert(row(tenant, instance, GOOD)));
        assertEquals(1, runtime.update("UPDATE sm_data.designer_layout SET layout = CAST(:l AS jsonb),"
                + " updated_by = :u, updated_at = :now WHERE tenant_id = :t AND instance_id = :i",
                row(tenant, instance, "{}")));

        assertEquals(before, runtime.queryForObject("SELECT state_version FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", row(tenant, instance, GOOD), Long.class),
                "presentation state is not an observable change of the instance (OR-96): no stamp");
    }

    @Test
    void aLayoutNamesADeclaredInstanceOfTheSameTenantAndNothingElse() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);

        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "designer_layout_has_instance",
                () -> insert(row(tenant, UUID.randomUUID(), GOOD)), "an undeclared instance");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "designer_layout_has_instance",
                () -> insert(row(SmDataPostgres.freshTenant(), instance, GOOD)), "another tenant's instance");
    }

    @Test
    void oneRecordPerInstance() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        insert(row(tenant, instance, GOOD));

        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "designer_layout_pkey",
                () -> insert(row(tenant, instance, "{}")));
    }

    @Test
    void theLayoutIsAnObjectOfInstanceKeysToNumericPositionsAndNothingElse() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "designer_layout_is_object",
                () -> insert(row(tenant, instance, "[]")), "an array");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "designer_layout_is_object",
                () -> insert(row(tenant, instance, "\"s\"")), "a string");
        for (String bad : List.of("{\"c\":1}", "{\"c\":{\"x\":1}}", "{\"c\":{\"x\":\"1\",\"y\":2}}",
                "{\"c\":{\"x\":null,\"y\":2}}", "{\"c\":{\"x\":[1],\"y\":2}}", "{\"c\":{}}", "{\"c\":[]}", "{\"c\":[{\"x\":1,\"y\":2}]}", "{\"c\":{\"x\":1,\"y\":2,\"z\":3}}", "{\"ok\":{\"x\":1,\"y\":2},\"c\":[]}")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "designer_layout_positions",
                    () -> insert(row(tenant, instance, bad)), bad);
        }
        assertEquals(0, insert0(tenant, instance), "nothing was written by any refusal");
        for (String good : List.of("{}", GOOD, "{\"a\":{\"x\":0,\"y\":0},\"b\":{\"y\":1e3,\"x\":-0.25}}")) {
            UUID other = UUID.randomUUID();
            InventoryFixtures.declare(tenant, other);
            assertEquals(1, insert(row(tenant, other, good)), good);
        }
    }

    private static int insert0(UUID tenant, UUID instance) {
        return runtime.queryForObject("SELECT count(*)::int FROM sm_data.designer_layout WHERE tenant_id = :t"
                + " AND instance_id = :i", row(tenant, instance, "{}"), Integer.class);
    }

    @Test
    void theRuntimeRoleCannotDeleteTruncateOrRetargetARecord() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        insert(row(tenant, instance, GOOD));

        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.designer_layout WHERE tenant_id = :t", row(tenant, instance, GOOD)), "DELETE");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "TRUNCATE sm_data.designer_layout", Map.of()), "TRUNCATE");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.designer_layout SET instance_id = :u WHERE tenant_id = :t",
                row(tenant, instance, GOOD)), "UPDATE instance_id");
        assertEquals(List.of("layout", "updated_at", "updated_by"), superuser.queryForList(
                "SELECT column_name FROM information_schema.column_privileges WHERE table_schema = 'sm_data'"
                        + " AND table_name = 'designer_layout' AND grantee = 'sm_dp_runtime'"
                        + " AND privilege_type = 'UPDATE' ORDER BY column_name", Map.of(), String.class),
                "exactly the three presentation columns are updatable");
    }
}
