package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V053 (plan 016 task 8): the agent log cap is one nullable, non-negative bigint on the placement. */
@Testcontainers(disabledWithoutDocker = true)
class PlacementLogMaxBytesSchemaIT {

    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
    }

    @Test
    void theColumnIsANullableBigint() {
        List<Map<String, Object>> rows = superuser.queryForList(
                "SELECT data_type, is_nullable FROM information_schema.columns WHERE table_schema = 'sm_data'"
                        + " AND table_name = 'placement' AND column_name = 'log_max_bytes'", Map.of());
        assertEquals(1, rows.size());
        assertEquals("bigint", rows.get(0).get("data_type"));
        assertEquals("YES", rows.get(0).get("is_nullable"), "NULL is 'never set': the agent keeps its default");
    }

    @Test
    void aNegativeCapIsRefusedByAConstraint() {
        String definition = superuser.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'sm_data.placement'::regclass"
                        + " AND conname = 'placement_log_max_bytes_nonnegative'", Map.of(), String.class);
        assertTrue(definition.contains("log_max_bytes >= 0"), definition);
    }
}
