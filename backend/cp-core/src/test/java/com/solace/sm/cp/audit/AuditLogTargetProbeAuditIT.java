package com.solace.sm.cp.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.audit.TargetProbeAudit;
import com.solace.sm.cp.api.audit.TargetProbeAudit.ScanResult;
import com.solace.sm.cp.api.audit.TargetProbeAudit.TestResult;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.persistence.JdbcAuditLog;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Plan 013 T1.4 against a real PostgreSQL: a scan's audit row is accepted by {@code sm_control.audit_log}'s constraints
 * (the verb must be in the permission catalog, the actor a principal of the tenant) at each of the three scope kinds a
 * target can be owned at, and holds exactly the fixed detail keys.
 */
class AuditLogTargetProbeAuditIT {

    private static final String KEY = "target-probe-audit-it";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, SmControlPostgres.LATEST_VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final TargetProbeAudit audit = new AuditLogTargetProbeAudit(new JdbcAuditLog(runtime), Clock.systemUTC());

    @Test
    void aScanIsOneRowAtEachScopeKindATargetCanBeOwnedAt() throws IOException {
        for (ResourceKind kind : List.of(ResourceKind.ORGANIZATION, ResourceKind.SUB_ORGANIZATION,
                ResourceKind.ENVIRONMENT)) {
            UUID organization = UUID.randomUUID();
            UUID principal = UUID.randomUUID();
            migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name)"
                    + " VALUES (?, 'fixture-' || gen_random_uuid()::text)", organization);
            migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                    + " VALUES (?, ?, 'idp', ?)", principal, organization, "probe-audit-" + principal);
            UUID node = kind == ResourceKind.ORGANIZATION ? organization : UUID.randomUUID();
            UUID correlation = UUID.randomUUID();

            audit.scanned(new PrincipalRef(principal), new RequestOrigin("203.0.113.60", correlation),
                    new TenantId(organization), kind, node, ScanResult.UNREACHABLE);

            List<Map<String, Object>> rows = migrator.queryForList("SELECT verb, outcome, resource_kind, resource_id,"
                    + " detail::text AS detail FROM sm_control.audit_log WHERE tenant = :tenant",
                    Map.of("tenant", organization));
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("verb")).isEqualTo("target.create");
            assertThat(rows.get(0).get("outcome")).isEqualTo("allow");
            assertThat(rows.get(0).get("resource_kind")).isEqualTo(kind.name().toLowerCase(java.util.Locale.ROOT));
            assertThat(rows.get(0).get("resource_id")).isEqualTo(node);
            JsonNode detail = JSON.readTree((String) rows.get(0).get("detail"));
            assertThat(detail.path("new_status").asText()).isEqualTo("unreachable");
            assertThat(detail.path("correlation_id").asText()).isEqualTo(correlation.toString());
            assertThat(detail.size()).isEqualTo(2);
        }
    }

    /** Plan 013 T1.5: the edit's rescan (target.edit) and the connection test (target.use) are rows the table accepts. */
    @Test
    void aRescanAndAConnectionTestAreOneRowEachOfTheirOwnVerbOnTheTarget() throws IOException {
        UUID organization = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name)"
                + " VALUES (?, 'fixture-' || gen_random_uuid()::text)", organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, organization, "probe-audit-" + principal);
        UUID target = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        RequestOrigin origin = new RequestOrigin("203.0.113.61", correlation);

        audit.rescanned(new PrincipalRef(principal), origin, new TenantId(organization), target, ScanResult.COMPLETED);
        audit.rescanned(new PrincipalRef(principal), origin, new TenantId(organization), target, ScanResult.RATE_LIMITED);
        audit.tested(new PrincipalRef(principal), origin, new TenantId(organization), target, TestResult.DOCKER_UNAVAILABLE);

        List<Map<String, Object>> rows = migrator.queryForList("SELECT verb, outcome, resource_kind, resource_id,"
                + " detail::text AS detail FROM sm_control.audit_log WHERE tenant = :tenant ORDER BY verb, outcome",
                Map.of("tenant", organization));
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("verb")).containsExactly("target.edit", "target.edit", "target.use");
        assertThat(rows).extracting(r -> r.get("outcome")).containsExactly("allow", "deny", "allow");
        assertThat(rows).extracting(r -> r.get("resource_id")).containsOnly(target);
        assertThat(rows).extracting(r -> r.get("resource_kind")).containsOnly("deployment_target");
        for (Map<String, Object> row : rows) {
            JsonNode detail = JSON.readTree((String) row.get("detail"));
            assertThat(detail.path("correlation_id").asText()).isEqualTo(correlation.toString());
            assertThat(detail.size()).isEqualTo(2);
        }
    }
}
