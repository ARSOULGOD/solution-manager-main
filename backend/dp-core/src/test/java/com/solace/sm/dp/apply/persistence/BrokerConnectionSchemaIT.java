package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.solace.sm.dp.secret.SecretPurpose;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 011 P11-5.1 (OR-107 OQ-3 (a), OQ-20 (b)): the {@code sm_data.broker_connection} table, the widened
 * {@code secret_purpose} CHECK ({@code BROKER_CREDENTIAL}, {@code BE-D43} amending {@code BE-D28}), and the
 * {@code sm_metrics.broker_ingest_counter} table (which has NO foreign key: M-Q19 admits {@code sm_data.placement} as the only
 * cross-schema target, and {@code SmMetricsSchemaIT} enforces that). Every statement runs as {@code sm_dp_runtime}, and every refusal is
 * asserted by SQLSTATE AND name.
 */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class BrokerConnectionSchemaIT {

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    private static final String INSERT = "INSERT INTO sm_data.broker_connection (tenant_id, environment_id,"
            + " broker_connection_id, scheme, host, port, vpn, client_username, credential_ref, created_by, created_at)"
            + " VALUES (:t, :e, :c, :scheme, :host, :port, :vpn, :user, :cred, :by, :now)";

    private static MapSqlParameterSource row(UUID tenant, UUID environment, UUID connection) {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("e", environment).addValue("c", connection)
                .addValue("scheme", "tcps").addValue("host", "broker-" + connection + ".example.test").addValue("port", 55443)
                .addValue("vpn", "customer-vpn").addValue("user", "sm-push").addValue("cred", UUID.randomUUID())
                .addValue("by", UUID.randomUUID()).addValue("now", OffsetDateTime.now());
    }

    private static UUID connection(UUID tenant, UUID environment) {
        UUID id = UUID.randomUUID();
        runtime.update(INSERT, row(tenant, environment, id));
        return id;
    }

    // ---- secret purpose ---------------------------------------------------------------------------------------

    @Test
    void theSecretPurposeCheckAdmitsExactlyTheEnumsStoredPurposes() {
        for (SecretPurpose purpose : SecretPurpose.values()) {
            if (purpose == SecretPurpose.CANARY) {
                continue; // never in sm_secret.secret (the canary has its own table)
            }
            UUID tenant = UUID.randomUUID();
            String sql = "INSERT INTO sm_secret.secret (tenant_id, secret_id, purpose, key_id, wrapped_dek, dek_iv, value_iv,"
                    + " ciphertext, aad_version, key_version, created_at, created_by) VALUES (:t, :s, :p, 'k',"
                    + " decode(repeat('00', 48), 'hex'), decode(repeat('00', 12), 'hex'), decode(repeat('00', 12), 'hex'),"
                    + " decode(repeat('00', 20), 'hex'), 1, :kv, now(), :by)";
            boolean hashKey = purpose == SecretPurpose.CONFIG_HASH_KEY;
            runtime.update(sql, new MapSqlParameterSource().addValue("t", tenant).addValue("s", UUID.randomUUID())
                    .addValue("p", purpose.name()).addValue("kv", hashKey ? 1 : null)
                    .addValue("by", hashKey ? null : UUID.randomUUID()));
        }
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "secret_purpose", () -> runtime.update(
                "INSERT INTO sm_secret.secret (tenant_id, secret_id, purpose, key_id, wrapped_dek, dek_iv, value_iv,"
                        + " ciphertext, aad_version, created_at, created_by) VALUES (:t, :s, 'BROKER_PASSWORD', 'k',"
                        + " decode(repeat('00', 48), 'hex'), decode(repeat('00', 12), 'hex'),"
                        + " decode(repeat('00', 12), 'hex'), decode(repeat('00', 20), 'hex'), 1, now(), :by)",
                new MapSqlParameterSource().addValue("t", UUID.randomUUID()).addValue("s", UUID.randomUUID())
                        .addValue("by", UUID.randomUUID())));
    }

    // ---- broker_connection ------------------------------------------------------------------------------------

    @Test
    void oneLiveConnectionPerOrganizationAndEnvironment() {
        UUID tenant = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        connection(tenant, environment);

        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "broker_connection_live_once",
                () -> runtime.update(INSERT, row(tenant, environment, UUID.randomUUID())));

        connection(tenant, UUID.randomUUID());                    // another environment
        connection(UUID.randomUUID(), environment);               // another organization: the index leads with tenant_id (I5)
    }

    // ---- OR-112 (2): one live connection per (host, port, VPN) across the install -------------------------------

    private static MapSqlParameterSource at(String host, int port, String vpn) {
        return row(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()).addValue("host", host).addValue("port", port)
                .addValue("vpn", vpn);
    }

    @Test
    void oneLiveConnectionPerBrokerAndVpnAcrossTheInstall() {
        String host = "shared-" + UUID.randomUUID() + ".example.test";
        runtime.update(INSERT, at(host, 55443, "vpn-a"));

        // another organization, another environment: still one install, one broker, one VPN
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "broker_connection_live_broker_once",
                () -> runtime.update(INSERT, at(host, 55443, "vpn-a")));
        // the host is compared without regard to case (DNS names are case-insensitive)
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "broker_connection_live_broker_once",
                () -> runtime.update(INSERT, at(host.toUpperCase(java.util.Locale.ROOT), 55443, "vpn-a")));

        runtime.update(INSERT, at(host, 55444, "vpn-a"));          // another port: another broker
        runtime.update(INSERT, at(host, 55443, "vpn-b"));          // another VPN: another tenant of the broker
    }

    @Test
    void aRetiredConnectionReleasesItsBrokerAndVpn() {
        UUID tenant = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        String host = "released-" + UUID.randomUUID() + ".example.test";
        runtime.update(INSERT, row(tenant, UUID.randomUUID(), first).addValue("host", host));

        superuser.update("UPDATE sm_data.broker_connection SET retired_at = now() WHERE tenant_id = :t"
                        + " AND broker_connection_id = :c",
                new MapSqlParameterSource().addValue("t", tenant).addValue("c", first));

        runtime.update(INSERT, at(host, 55443, "customer-vpn"));
    }

    @Test
    void aRetiredConnectionNoLongerHoldsTheEnvironment() {
        UUID tenant = UUID.randomUUID();
        UUID environment = UUID.randomUUID();
        UUID first = connection(tenant, environment);

        superuser.update("UPDATE sm_data.broker_connection SET retired_at = now() WHERE tenant_id = :t"
                        + " AND broker_connection_id = :c",
                new MapSqlParameterSource().addValue("t", tenant).addValue("c", first));

        connection(tenant, environment);
    }

    @Test
    void theRuntimeRoleCanReadAndInsertAndNothingElse() {
        UUID tenant = UUID.randomUUID();
        UUID id = connection(tenant, UUID.randomUUID());

        assertEquals(1, runtime.queryForObject("SELECT count(*) FROM sm_data.broker_connection WHERE tenant_id = :t"
                        + " AND broker_connection_id = :c",
                new MapSqlParameterSource().addValue("t", tenant).addValue("c", id), Integer.class));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.broker_connection SET host = 'x.example.test' WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.broker_connection SET retired_at = now() WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant)));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.broker_connection WHERE tenant_id = :t", new MapSqlParameterSource("t", tenant)));
    }

    @Test
    void everyFieldKeepsItsGrammar() {
        UUID tenant = UUID.randomUUID();
        String[][] bad = {
                {"scheme", "https", "broker_connection_scheme"},
                {"host", "broker; rm -rf /", "broker_connection_host"},
                {"host", "tcps://broker:1", "broker_connection_host"},
                {"vpn", "my vpn", "broker_connection_vpn"},
                {"user", "user name", "broker_connection_username"},
        };
        for (String[] b : bad) {
            MapSqlParameterSource p = row(tenant, UUID.randomUUID(), UUID.randomUUID()).addValue(b[0], b[1]);
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, b[2], () -> runtime.update(INSERT, p));
        }
        for (int port : new int[] {0, 65536, -1}) {
            MapSqlParameterSource p = row(tenant, UUID.randomUUID(), UUID.randomUUID()).addValue("port", port);
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "broker_connection_port", () -> runtime.update(INSERT, p));
        }
    }

    // ---- broker_ingest_counter (sm_metrics V002; OQ-20 (b)) -----------------------------------------------------

    private static final String COUNT = "INSERT INTO sm_metrics.broker_ingest_counter (tenant_id, broker_connection_id,"
            + " reason, day, count) VALUES (:t, :c, :r, current_date, :n)";

    private static MapSqlParameterSource count(UUID tenant, UUID connection, String reason, long n) {
        return new MapSqlParameterSource().addValue("t", tenant).addValue("c", connection).addValue("r", reason)
                .addValue("n", n);
    }

    @Test
    void theBrokerLevelCounterTakesTheFourReasonsAndNoOther() {
        UUID tenant = UUID.randomUUID();
        UUID id = connection(tenant, UUID.randomUUID());
        for (String reason : new String[] {"undeclared_dropped", "ambiguous_placement", "parse_failed", "buffer_dropped"}) {
            runtime.update(COUNT, count(tenant, id, reason, 3));
        }
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "broker_ingest_counter_reason",
                () -> runtime.update(COUNT, count(tenant, id, "scrape_failed", 1)));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, null,
                () -> runtime.update(COUNT, count(tenant, id, "parse_failed", 0)));
    }

    @Test
    void theRuntimeRoleMayBumpACountAndNothingElseAboutIt() {
        UUID tenant = UUID.randomUUID();
        UUID id = connection(tenant, UUID.randomUUID());
        runtime.update(COUNT, count(tenant, id, "parse_failed", 1));
        MapSqlParameterSource key = new MapSqlParameterSource().addValue("t", tenant).addValue("c", id);

        runtime.update("UPDATE sm_metrics.broker_ingest_counter SET count = count + 5 WHERE tenant_id = :t"
                + " AND broker_connection_id = :c", key);
        assertEquals(6L, runtime.queryForObject("SELECT count FROM sm_metrics.broker_ingest_counter"
                + " WHERE tenant_id = :t AND broker_connection_id = :c", key, Long.class));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_metrics.broker_ingest_counter SET reason = 'buffer_dropped' WHERE tenant_id = :t", key));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_metrics.broker_ingest_counter WHERE tenant_id = :t", key));
    }
}
