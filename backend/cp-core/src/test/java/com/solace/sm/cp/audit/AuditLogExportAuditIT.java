package com.solace.sm.cp.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.audit.ExportAudit;
import com.solace.sm.cp.api.audit.ExportAudit.ExportResult;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.ConsequenceClass;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.config.ControlPlaneConfiguration;
import com.solace.sm.cp.events.StubMeshPublisherConfiguration;
import com.solace.sm.cp.persistence.JdbcAuditLog;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AuditLogExportAuditIT {

    private static final String KEY = "export-audit-it";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, SmControlPostgres.LATEST_VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final ExportAudit audit = new AuditLogExportAudit(new JdbcAuditLog(runtime), Clock.systemUTC());

    @Test
    void anExportWritesOneHighConsequenceAuditRow() {
        Fixture f = fixture();
        UUID correlation = UUID.randomUUID();

        audit.record(f.actor, origin(), f.tenant, correlation, 1234, ExportResult.COMPLETED);

        List<Map<String, Object>> rows = rowsFor(f);
        assertThat(rows).as("exactly one row for one export").hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("verb")).isEqualTo("metrics.export");
        Permission verb = Permission.byId((String) row.get("verb"));
        assertThat(verb.consequence()).isEqualTo(ConsequenceClass.HIGH);
        assertThat(AuditEntry.kindFor(verb)).as("high-consequence by the verb").isEqualTo(AuditKind.HIGH_CONSEQUENCE);
        assertThat(row.get("outcome")).as("the authorization decision: this export was allowed").isEqualTo("allow");
        assertThat(row.get("resource_kind")).isEqualTo("organization");
        assertThat(row.get("resource_id")).isEqualTo(f.organization);
        assertThat(row.get("tenant")).isEqualTo(f.organization);
        assertThat(row.get("actor_principal_id")).isEqualTo(f.actor.id());
    }

    @Test
    void theExportResultIsCarriedInNewStatus() throws IOException {
        for (Map.Entry<ExportResult, String> expected : Map.of(ExportResult.COMPLETED, "completed",
                ExportResult.FAILED, "failed", ExportResult.CUT, "cut").entrySet()) {
            Fixture f = fixture();
            UUID correlation = UUID.randomUUID();

            audit.record(f.actor, origin(), f.tenant, correlation, 42, expected.getKey());

            JsonNode detail = JSON.readTree((String) rowsFor(f).get(0).get("detail"));
            assertThat(detail.path("new_status").asText()).isEqualTo(expected.getValue());
            assertThat(detail.path("count").asLong()).isEqualTo(42);
            assertThat(detail.path("correlation_id").asText()).isEqualTo(correlation.toString());
            Set<String> keys = new java.util.TreeSet<>();
            detail.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).as("nothing else about the export is recorded")
                    .containsExactly("correlation_id", "count", "new_status");
        }
    }

    @Test
    void aRefusedExportWritesOneDenyRowOfTheSameVerbWithAFixedReasonCode() throws IOException {
        for (ExportAudit.RefusalReason reason : ExportAudit.RefusalReason.values()) {
            Fixture f = fixture();
            UUID correlation = UUID.randomUUID();

            audit.refused(f.actor, origin(), f.tenant, correlation, reason);

            List<Map<String, Object>> rows = rowsFor(f);
            assertThat(rows).as("exactly one row for one refusal: " + reason).hasSize(1);
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("verb")).isEqualTo("metrics.export");
            assertThat(row.get("outcome")).as("the authorization decision: this export was refused").isEqualTo("deny");
            assertThat(row.get("resource_kind")).isEqualTo("organization");
            assertThat(row.get("resource_id")).isEqualTo(f.organization);
            assertThat(row.get("tenant")).isEqualTo(f.organization);
            assertThat(row.get("actor_principal_id")).isEqualTo(f.actor.id());
            JsonNode detail = JSON.readTree((String) row.get("detail"));
            assertThat(detail.path("reason_code").asText()).isEqualTo(reason.code());
            assertThat(detail.path("correlation_id").asText()).isEqualTo(correlation.toString());
            Set<String> keys = new java.util.TreeSet<>();
            detail.fieldNames().forEachRemaining(keys::add);
            assertThat(keys).as("a refusal carries no metric, range, id or count")
                    .containsExactly("correlation_id", "reason_code");
        }
    }

    @Test
    void theThreeReasonCodesAreTheNamedStatusesAndFixed() {
        assertThat(java.util.Arrays.stream(ExportAudit.RefusalReason.values()).map(ExportAudit.RefusalReason::code))
                .containsExactly("forbidden", "export_too_large", "rate_limited");
    }

    @Test
    void anExportAuditRefusesWhatTheEntryAllowListWouldRefuse() {
        Fixture f = fixture();

        assertThatThrownBy(() -> audit.record(f.actor, origin(), f.tenant, UUID.randomUUID(), -1,
                ExportResult.COMPLETED)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rowsFor(f)).isEmpty();
    }

    @Test
    void exactlyOneProductionAuditLogBeanExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(ControlPlaneConfiguration.class, StubMeshPublisherConfiguration.class)
                .withPropertyValues(
                        "sm.control.runtime.url=" + SmControlPostgres.runtimeUrlAtVersion(KEY),
                        "sm.control.runtime.username=" + SmControlPostgres.RUNTIME_USERNAME,
                        "sm.control.runtime.password=" + SmControlPostgres.runtimePasswordAtVersion(KEY))
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeansOfType(AuditLog.class)).as("one AuditLog bean").hasSize(1);
                    assertThat(context.getBeansOfType(ExportAudit.class)).as("one ExportAudit bean").hasSize(1);
                    assertThat(context.getBean(ExportAudit.class)).isInstanceOf(AuditLogExportAudit.class);
                });
    }

    // ---------------------------------------------------------------- harness

    private record Fixture(UUID organization, PrincipalRef actor, TenantId tenant) {
    }

    private Fixture fixture() {
        UUID organization = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, 'fixture-' || gen_random_uuid()::text)",
                organization);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, organization, "export-audit-" + principal);
        return new Fixture(organization, new PrincipalRef(principal), new TenantId(organization));
    }

    private static RequestOrigin origin() {
        return new RequestOrigin("203.0.113.50", UUID.randomUUID());
    }

    private List<Map<String, Object>> rowsFor(Fixture f) {
        return migrator.queryForList("SELECT verb, outcome, resource_kind, resource_id, tenant, actor_principal_id,"
                + " detail::text AS detail FROM sm_control.audit_log WHERE tenant = :tenant",
                Map.of("tenant", f.organization));
    }
}
