package com.solace.sm.cp.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.config.ControlPlaneConfiguration;
import com.solace.sm.cp.events.StubMeshPublisherConfiguration;
import com.solace.sm.cp.identity.AuditOperations;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code V013} (plan 010 T2.0): the control-plane governance tables — catalog, packages, entitlement,
 * target — their {@code OR-25} audit contract, and the privilege widening the runtime role now holds.
 *
 * <p><b>Every write under test runs as {@code sm_cp_runtime}</b> over the real two-phase bootstrap
 * ({@link SmControlPostgres}), because a write by the migrator or the owner is exempt from OR-25 and
 * would prove nothing about the role the application actually connects as. Fixture rows (the
 * hierarchy, a principal, a scope) are seeded as the migrator, which is exempt, so each test's own
 * write is the only thing under test.
 */
class GovernanceSchemaIT {

    private static final String CACHE_KEY = "governance-schema-it";

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(CACHE_KEY, "013");
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(CACHE_KEY);
    private final TransactionTemplate runtimeTx =
            new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));

    // ---------------------------------------------------------------- the instance-id uniqueness (OR-27)

    @Test
    void theInstanceIdIsGloballyUnique() {
        Hierarchy first = hierarchy();
        Hierarchy second = hierarchy();
        UUID sharedInstanceId = UUID.randomUUID();
        UUID principal = principal(first.organization);

        asRuntime(AuditOperations.INSTANCE_DECLARED, principal, () -> insertInstance(first, sharedInstanceId));

        Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.INSTANCE_DECLARED, principal,
                () -> insertInstance(second, sharedInstanceId)));

        assertThat(refusal.sqlState).isEqualTo("23505");
        assertThat(refusal.message).contains("instance_id_unique");
    }

    // ---------------------------------------------------------------- audit coverage of the four new tables

    @Test
    void everyNewTableIsAuditedWithItsOwnRowKey() {
        Hierarchy h = hierarchy();
        UUID principal = principal(h.organization);
        UUID scope = organizationScope(h.organization);
        UUID catalogId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.PACKAGE_LOADED, principal, correlation);
            insertCatalogSolution(h.organization, catalogId, "cache");
            insertPackage(h.organization, catalogId, "[1]", digest('a'), "unsigned", principal, "{}");
            setContext(AuditOperations.ENTITLEMENT_SET, principal, correlation);
            insertEntitlement(h.organization, "cache", 3, principal);
            setContext(AuditOperations.TARGET_REGISTERED, principal, correlation);
            insertTarget(targetId, h.organization, scope, "t-" + targetId, "docker", "ssh-docker", principal);
        });

        assertThat(auditRow("catalog_solution", correlation).get("row_key").toString())
                .contains(catalogId.toString());
        assertThat(auditRow("solution_package", correlation).get("row_key").toString())
                .as("the package's row_key is its load_seq").contains("load_seq").doesNotContain("{}");
        assertThat(auditRow("entitlement", correlation).get("row_key").toString())
                .contains(h.organization.toString()).contains("cache");
        assertThat(auditRow("target", correlation).get("row_key").toString()).contains(targetId.toString());
    }

    @Test
    void aRuntimeWriteWithoutAuditContextIsRefusedOR025() {
        Hierarchy h = hierarchy();
        UUID principal = principal(h.organization);
        UUID scope = organizationScope(h.organization);
        UUID catalogId = UUID.randomUUID();
        seedCatalogSolution(h.organization, catalogId, "cache");

        List<Runnable> unauditedWrites = List.of(
                () -> insertCatalogSolution(h.organization, UUID.randomUUID(), "other"),
                () -> insertPackage(h.organization, catalogId, "[9]", digest('b'), "unsigned", principal, "{}"),
                () -> insertEntitlement(h.organization, "cache", 1, principal),
                () -> insertTarget(UUID.randomUUID(), h.organization, scope, "no-context", "docker",
                        "ssh-docker", principal));

        for (Runnable write : unauditedWrites) {
            Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> write.run()));
            assertThat(refusal.sqlState).as(refusal.message).isEqualTo("OR025");
            assertThat(refusal.message).contains("no known audit_operation");
        }
    }

    @Test
    void theNewOperationsAreAcceptedAndAnUnknownOneIsRefused() {
        Hierarchy h = hierarchy();
        UUID principal = principal(h.organization);

        for (String operation : List.of(AuditOperations.INSTANCE_DECLARED, AuditOperations.PACKAGE_LOADED,
                AuditOperations.ENTITLEMENT_SET, AuditOperations.TARGET_REGISTERED,
                AuditOperations.TARGET_RETIRED)) {
            UUID correlation = UUID.randomUUID();
            runtimeTx.executeWithoutResult(status -> {
                setContext(operation, principal, correlation);
                insertEntitlement(h.organization, "sol-" + operation.toLowerCase().replace('_', '-'), 1, principal);
            });
            assertThat(auditRow("entitlement", correlation).get("audit_operation")).isEqualTo(operation);
        }

        Refusal refusal = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext("TARGET_EXPLODED", principal, UUID.randomUUID());
            insertEntitlement(h.organization, "unknown-op", 1, principal);
        }));
        assertThat(refusal.sqlState).isEqualTo("OR025");
        assertThat(refusal.message).contains("no known audit_operation");

        // The CHECK on change_audit was widened in step, so the vocabulary is closed on both sides.
        Refusal checkRefusal = refusalOf(() -> migrator.getJdbcTemplate().update(
                "INSERT INTO sm_control.change_audit (recorded_at, txid, table_name, operation, row_key,"
                        + " audit_operation, db_role) VALUES (now(), 1, 'x', 'INSERT', '{}'::jsonb,"
                        + " 'TARGET_EXPLODED', 'x')"));
        assertThat(checkRefusal.sqlState).isEqualTo("23514");
        assertThat(checkRefusal.message).contains("change_audit_audit_operation");
    }

    @Test
    void truncateOfEachNewTableIsRefused() {
        for (String table : List.of("catalog_solution", "solution_package", "entitlement", "target")) {
            Refusal refusal = refusalOf(() -> migrator.getJdbcTemplate().execute("TRUNCATE sm_control." + table));
            // catalog_solution is referenced by foreign keys, which PostgreSQL refuses before it reaches any
            // BEFORE TRUNCATE trigger — so for it the proof that OUR trigger stands is the catalog check below.
            assertThat(refusal.message).as(table)
                    .containsAnyOf("may not be truncated", "cannot truncate a table referenced");
            assertThat(migrator.queryForObject(
                    "SELECT count(*) FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid"
                            + " WHERE c.relnamespace = 'sm_control'::regnamespace AND c.relname = :t"
                            + " AND t.tgname = 'truncate_refused' AND t.tgenabled IN ('O','A')",
                    Map.of("t", table), Integer.class)).as(table + " carries an enabled truncate_refused").isEqualTo(1);
        }
    }

    // ---------------------------------------------------------------- structural constraints

    @Test
    void aSecondSolutionNodeForTheSameCatalogEntryInOneEnvironmentIsRefused() {
        Hierarchy h = hierarchy();
        UUID principal = principal(h.organization);
        UUID catalogId = UUID.randomUUID();
        seedCatalogSolution(h.organization, catalogId, "cache");

        asRuntime(AuditOperations.INSTANCE_DECLARED, principal,
                () -> insertSolution(h, UUID.randomUUID(), catalogId));
        Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.INSTANCE_DECLARED, principal,
                () -> insertSolution(h, UUID.randomUUID(), catalogId)));

        assertThat(refusal.sqlState).isEqualTo("23505");
        assertThat(refusal.message).contains("solution_one_per_catalog_entry");

        // The index is partial: solution nodes with no catalog entry (every pre-V013 row) never collide.
        asRuntime(AuditOperations.INSTANCE_DECLARED, principal, () -> insertSolution(h, UUID.randomUUID(), null));
        asRuntime(AuditOperations.INSTANCE_DECLARED, principal, () -> insertSolution(h, UUID.randomUUID(), null));
    }

    @Test
    void signatureStatusOtherThanUnsignedIsRefused() {
        Hierarchy h = hierarchy();
        UUID principal = principal(h.organization);
        UUID catalogId = UUID.randomUUID();
        seedCatalogSolution(h.organization, catalogId, "cache");

        Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.PACKAGE_LOADED, principal,
                () -> insertPackage(h.organization, catalogId, "[1]", digest('c'), "signed", principal, "{}")));

        assertThat(refusal.sqlState).isEqualTo("23514");
        assertThat(refusal.message).contains("solution_package_signature");
    }

    @Test
    void aPackageWhoseOrganizationDiffersFromItsEntrysIsRefused() {
        Hierarchy h = hierarchy();
        Hierarchy other = hierarchy();
        UUID principal = principal(h.organization);
        UUID catalogId = UUID.randomUUID();
        seedCatalogSolution(h.organization, catalogId, "cache");

        Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.PACKAGE_LOADED, principal,
                () -> insertPackage(other.organization, catalogId, "[1]", digest('d'), "unsigned", principal, "{}")));

        assertThat(refusal.sqlState).isEqualTo("23503");
        assertThat(refusal.message).contains("solution_package_entry");
    }

    @Test
    void aTargetOwnedByAnotherOrganizationsScopeIsRefused() {
        Hierarchy h = hierarchy();
        Hierarchy other = hierarchy();
        UUID principal = principal(h.organization);
        UUID foreignScope = organizationScope(other.organization);

        Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.TARGET_REGISTERED, principal,
                () -> insertTarget(UUID.randomUUID(), h.organization, foreignScope, "foreign", "docker",
                        "ssh-docker", principal)));

        assertThat(refusal.sqlState).isEqualTo("23503");
        assertThat(refusal.message).contains("target_ownership_in_org");
    }

    @Test
    void aTargetCarriesBothItsRuntimeKindAndItsTransportAndBothAreClosedSets() {
        Hierarchy h = hierarchy();
        UUID principal = principal(h.organization);
        UUID scope = organizationScope(h.organization);

        // Q-C3 (owner ruling 2026-09-29, a): kind is the MI runtime, transport is how SM reaches it.
        asRuntime(AuditOperations.TARGET_REGISTERED, principal, () -> insertTarget(
                UUID.randomUUID(), h.organization, scope, "docker-over-ssh", "docker", "ssh-docker", principal));

        Refusal kind = refusalOf(() -> asRuntime(AuditOperations.TARGET_REGISTERED, principal, () -> insertTarget(
                UUID.randomUUID(), h.organization, scope, "bad-kind", "mainframe", "ssh-docker", principal)));
        assertThat(kind.sqlState).isEqualTo("23514");
        assertThat(kind.message).contains("target_kind");

        Refusal transport = refusalOf(() -> asRuntime(AuditOperations.TARGET_REGISTERED, principal, () -> insertTarget(
                UUID.randomUUID(), h.organization, scope, "bad-transport", "docker", "telnet", principal)));
        assertThat(transport.sqlState).isEqualTo("23514");
        assertThat(transport.message).contains("target_transport");
    }

    // ---------------------------------------------------------------- the privilege widening

    @Test
    void theRuntimeRoleCannotUpdatePackageDocumentsOrDeleteAnything() {
        Hierarchy h = hierarchy();
        UUID principal = principal(h.organization);
        UUID scope = organizationScope(h.organization);
        UUID catalogId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        seedCatalogSolution(h.organization, catalogId, "cache");
        long loadSeq = seedPackage(h.organization, catalogId, principal);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.entitlement VALUES (?, 'cache', 2, ?, now())",
                h.organization, principal);
        migrator.getJdbcTemplate().update(
                "INSERT INTO sm_control.target (target_id, organization_id, ownership_scope_id, name, kind,"
                        + " transport, created_by, created_at) VALUES (?, ?, ?, 'priv', 'docker', 'ssh-docker', ?,"
                        + " now())", targetId, h.organization, scope, principal);

        List<String> forbidden = List.of(
                "UPDATE sm_control.solution_package SET document = '{\"x\":1}'::jsonb WHERE load_seq = " + loadSeq,
                "UPDATE sm_control.solution_package SET digest = 'sha256:" + "e".repeat(64) + "'",
                "UPDATE sm_control.catalog_solution SET solution = 'renamed'",
                "UPDATE sm_control.target SET name = 'renamed'",
                "UPDATE sm_control.entitlement SET solution = 'renamed'",
                "DELETE FROM sm_control.catalog_solution",
                "DELETE FROM sm_control.solution_package",
                "DELETE FROM sm_control.entitlement",
                "DELETE FROM sm_control.target");
        for (String statement : forbidden) {
            Refusal refusal = refusalOf(() -> asRuntime(AuditOperations.TARGET_RETIRED, principal,
                    () -> runtime.getJdbcTemplate().update(statement)));
            assertThat(refusal.sqlState).as(statement + " :: " + refusal.message).isEqualTo("42501");
        }

        // The grants that ARE given: retire a target, adjust an entitlement's three columns.
        asRuntime(AuditOperations.TARGET_RETIRED, principal, () -> assertThat(runtime.getJdbcTemplate().update(
                "UPDATE sm_control.target SET retired_at = now() WHERE target_id = ?", targetId)).isEqualTo(1));
        asRuntime(AuditOperations.ENTITLEMENT_SET, principal, () -> assertThat(runtime.getJdbcTemplate().update(
                "UPDATE sm_control.entitlement SET max_instances = 9, set_by = ?, set_at = now()"
                        + " WHERE organization_id = ?", principal, h.organization)).isEqualTo(1));
    }

    // ---------------------------------------------------------------- the audit row's content

    @Test
    void aPackageLoadsAuditRowCarriesTheDigestNotTheDocument() {
        Hierarchy h = hierarchy();
        UUID principal = principal(h.organization);
        UUID catalogId = UUID.randomUUID();
        seedCatalogSolution(h.organization, catalogId, "cache");
        UUID correlation = UUID.randomUUID();
        String digest = digest('f');
        String bigDocument = "{\"filler\":\"" + "x".repeat(20_000) + "\"}";

        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.PACKAGE_LOADED, principal, correlation);
            insertPackage(h.organization, catalogId, "[1]", digest, "unsigned", principal, bigDocument);
        });

        Map<String, Object> audit = auditRow("solution_package", correlation);
        assertThat(audit.get("new_values").toString()).contains(digest).doesNotContain("filler");
        assertThat(migrator.queryForObject(
                "SELECT jsonb_exists(new_values, 'document') FROM sm_control.change_audit WHERE correlation_id = :c"
                        + " AND table_name = 'solution_package'", Map.of("c", correlation), Boolean.class))
                .as("the document key is stripped from the immutable audit row").isFalse();
        assertThat(migrator.queryForObject(
                "SELECT length(new_values::text) FROM sm_control.change_audit WHERE correlation_id = :c"
                        + " AND table_name = 'solution_package'", Map.of("c", correlation), Integer.class))
                .isLessThan(2_000);
        assertThat(migrator.queryForObject(
                "SELECT length(document::text) FROM sm_control.solution_package WHERE digest = :d",
                Map.of("d", digest), Integer.class)).as("the package itself keeps the document").isGreaterThan(20_000);
    }

    // ---------------------------------------------------------------- the owner ruling Q-E

    @Test
    void entitlementSetWithNoActorIsAcceptedOnEntitlementOnly() {
        Hierarchy h = hierarchy();
        UUID principal = principal(h.organization);
        UUID scope = organizationScope(h.organization);
        UUID catalogId = UUID.randomUUID();
        seedCatalogSolution(h.organization, catalogId, "cache");
        UUID correlation = UUID.randomUUID();

        // Owner ruling 2026-09-29 (Q-E, OR-48): ENTITLEMENT_SET may carry no actor — on entitlement.
        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.ENTITLEMENT_SET, null, correlation);
            insertEntitlement(h.organization, "cache", 4, principal);
        });
        assertThat(auditRow("entitlement", correlation).get("actor_principal_id")).isNull();
        runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.ENTITLEMENT_SET, null, UUID.randomUUID());
            assertThat(runtime.getJdbcTemplate().update(
                    "UPDATE sm_control.entitlement SET max_instances = 5, set_by = ?, set_at = now()"
                            + " WHERE organization_id = ?", principal, h.organization)).isEqualTo(1);
        });

        // ...and nowhere else. Same operation, no actor, a different table: refused.
        Refusal onTarget = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.ENTITLEMENT_SET, null, UUID.randomUUID());
            insertTarget(UUID.randomUUID(), h.organization, scope, "no-actor", "docker", "ssh-docker", principal);
        }));
        assertThat(onTarget.sqlState).isEqualTo("OR025");
        assertThat(onTarget.message).contains("requires an actor principal");

        // No other new operation inherits the exemption on the entitlement table either.
        Refusal otherOperation = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.PACKAGE_LOADED, null, UUID.randomUUID());
            insertEntitlement(h.organization, "other", 1, principal);
        }));
        assertThat(otherOperation.sqlState).isEqualTo("OR025");
        Refusal onPackage = refusalOf(() -> runtimeTx.executeWithoutResult(status -> {
            setContext(AuditOperations.ENTITLEMENT_SET, null, UUID.randomUUID());
            insertPackage(h.organization, catalogId, "[1]", digest('9'), "unsigned", principal, "{}");
        }));
        assertThat(onPackage.sqlState).isEqualTo("OR025");
    }

    // ---------------------------------------------------------------- readiness

    @Test
    void readinessRefusesAV012SchemaNamingV013() {
        String cacheKey = "governance-readiness-v012-it";
        SmControlPostgres.jdbcAtVersion(cacheKey, "012");

        runner(cacheKey).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertThat(failure).as("a V012 sm_control must not be served by a build that requires V013").isNotNull();
            assertThat(causeChain(failure)).contains("V013").contains("SmControlMigrationMain");
        });
    }

    @Test
    void readinessRefusesAV013SchemaWhoseNewTableLostItsAuditOrTruncateTrigger() {
        String cacheKey = "governance-readiness-triggers-it";
        // Fully migrated (the head's readiness stages run too); the V013 stage is what is probed.
        NamedParameterJdbcTemplate owner = SmControlPostgres.jdbcAtVersion(cacheKey, SmControlPostgres.LATEST_VERSION);

        runner(cacheKey).run(context -> assertThat(context.getStartupFailure())
                .as("a fully migrated sm_control starts").isNull());

        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.target DISABLE TRIGGER change_audit_trigger");
        runner(cacheKey).run(context -> {
            assertThat(context.getStartupFailure()).as("an unaudited target table must not be served").isNotNull();
            assertThat(causeChain(context.getStartupFailure())).contains("audit");
        });
        owner.getJdbcTemplate().execute("ALTER TABLE sm_control.target ENABLE TRIGGER change_audit_trigger");

        owner.getJdbcTemplate().execute("DROP TRIGGER truncate_refused ON sm_control.entitlement");
        runner(cacheKey).run(context -> {
            assertThat(context.getStartupFailure()).as("a truncatable entitlement table must not be served")
                    .isNotNull();
            assertThat(causeChain(context.getStartupFailure())).contains("truncate_refused");
        });
        owner.getJdbcTemplate().execute("CREATE TRIGGER truncate_refused BEFORE TRUNCATE ON sm_control.entitlement"
                + " FOR EACH STATEMENT EXECUTE FUNCTION sm_control.reject_change_audit_mutation()");

        // An owner who put V012's body back: still fail-closed (so every V012 probe passes), but it refuses
        // every V013 operation. Presence of the tables is not proof the function that audits them is in force.
        owner.getJdbcTemplate().execute(v012FunctionBody());
        runner(cacheKey).run(context -> {
            assertThat(context.getStartupFailure()).as("record_change_audit() reverted to V012 must not be served")
                    .isNotNull();
            assertThat(causeChain(context.getStartupFailure())).contains("V013");
        });
    }

    private static String v012FunctionBody() {
        try (java.io.InputStream in = GovernanceSchemaIT.class.getClassLoader()
                .getResourceAsStream("db/migration/sm_control/V012__audit_fail_closed.sql")) {
            String sql = new String(java.util.Objects.requireNonNull(in).readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            return sql.substring(sql.indexOf("CREATE OR REPLACE FUNCTION sm_control.record_change_audit()"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- harness

    private record Hierarchy(UUID organization, UUID unit, UUID environment, UUID solution) {
    }

    private record Refusal(String sqlState, String message) {
    }

    private ApplicationContextRunner runner(String cacheKey) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ControlPlaneConfiguration.class, StubMeshPublisherConfiguration.class)
                .withPropertyValues(
                        "sm.control.runtime.url=" + SmControlPostgres.runtimeUrlAtVersion(cacheKey),
                        "sm.control.runtime.username=" + SmControlPostgres.RUNTIME_USERNAME,
                        "sm.control.runtime.password=" + SmControlPostgres.runtimePasswordAtVersion(cacheKey));
    }

    private static String causeChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append(" | ");
        }
        return chain.toString();
    }

    private Refusal refusalOf(Runnable work) {
        try {
            work.run();
        } catch (DataAccessException | IllegalStateException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql) {
                    return new Refusal(sql.getSQLState(), String.valueOf(sql.getMessage()));
                }
            }
            throw e;
        }
        throw new AssertionError("expected the write to be refused, but it succeeded");
    }

    /** Runs {@code work} in one transaction as {@code sm_cp_runtime} with complete audit context. */
    private void asRuntime(String operation, UUID actor, Runnable work) {
        runtimeTx.executeWithoutResult(status -> {
            setContext(operation, actor, UUID.randomUUID());
            work.run();
        });
    }

    /** The same transaction-local settings {@code JdbcAuditContext} writes, spelled out. */
    private void setContext(String operation, UUID actor, UUID correlation) {
        runtime.getJdbcTemplate().queryForList(
                "SELECT set_config('sm.audit.actor', ?, true), set_config('sm.audit.operation', ?, true),"
                        + " set_config('sm.audit.correlation_id', ?, true),"
                        + " set_config('sm.audit.client_address', ?, true)",
                actor == null ? "" : actor.toString(), operation, correlation.toString(), "203.0.113.50");
    }

    private Map<String, Object> auditRow(String table, UUID correlation) {
        List<Map<String, Object>> rows = migrator.queryForList(
                "SELECT row_key::text AS row_key, new_values::text AS new_values, audit_operation,"
                        + " actor_principal_id FROM sm_control.change_audit WHERE table_name = :t"
                        + " AND correlation_id = :c", Map.of("t", table, "c", correlation));
        assertThat(rows).as("exactly one audit row for " + table).hasSize(1);
        return rows.get(0);
    }

    private Hierarchy hierarchy() {
        Hierarchy h = new Hierarchy(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id) VALUES (?)",
                h.organization);
        migrator.getJdbcTemplate().update(
                "INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id) VALUES (?, ?)",
                h.organization, h.unit);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id,"
                + " organization_unit_id, environment_id) VALUES (?, ?, ?)", h.organization, h.unit, h.environment);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.solution (organization_id, organization_unit_id,"
                + " environment_id, solution_id) VALUES (?, ?, ?, ?)", h.organization, h.unit, h.environment,
                h.solution);
        return h;
    }

    private UUID principal(UUID organization) {
        UUID id = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", id, organization, "governance-" + id);
        return id;
    }

    private UUID organizationScope(UUID organization) {
        UUID id = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.scope (scope_id, kind, organization_id)"
                + " VALUES (?, 'organization', ?)", id, organization);
        return id;
    }

    private void seedCatalogSolution(UUID organization, UUID catalogId, String solution) {
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.catalog_solution (organization_id,"
                + " catalog_solution_id, solution, created_at) VALUES (?, ?, ?, now())", organization, catalogId,
                solution);
    }

    private long seedPackage(UUID organization, UUID catalogId, UUID principal) {
        return migrator.getJdbcTemplate().queryForObject(
                "INSERT INTO sm_control.solution_package (organization_id, catalog_solution_id, package_version,"
                        + " applies_to, digest, signature_status, document, loaded_by, loaded_at)"
                        + " VALUES (?, ?, '[1]', '>=1', ?, 'unsigned', '{}'::jsonb, ?, now()) RETURNING load_seq",
                Long.class, organization, catalogId, digest('0'), principal);
    }

    private void insertCatalogSolution(UUID organization, UUID catalogId, String solution) {
        runtime.getJdbcTemplate().update("INSERT INTO sm_control.catalog_solution (organization_id,"
                + " catalog_solution_id, solution, created_at) VALUES (?, ?, ?, now())", organization, catalogId,
                solution);
    }

    private void insertPackage(UUID organization, UUID catalogId, String version, String digest, String signature,
            UUID loadedBy, String document) {
        runtime.getJdbcTemplate().update("INSERT INTO sm_control.solution_package (organization_id,"
                + " catalog_solution_id, package_version, applies_to, digest, signature_status, document,"
                + " loaded_by, loaded_at) VALUES (?, ?, ?, '>=1', ?, ?, ?::jsonb, ?, now())",
                organization, catalogId, version, digest, signature, document, loadedBy);
    }

    private void insertEntitlement(UUID organization, String solution, int max, UUID setBy) {
        runtime.getJdbcTemplate().update("INSERT INTO sm_control.entitlement (organization_id, solution,"
                + " max_instances, set_by, set_at) VALUES (?, ?, ?, ?, now())", organization, solution, max, setBy);
    }

    private void insertTarget(UUID targetId, UUID organization, UUID scope, String name, String kind,
            String transport, UUID createdBy) {
        runtime.getJdbcTemplate().update("INSERT INTO sm_control.target (target_id, organization_id,"
                + " ownership_scope_id, name, kind, transport, created_by, created_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, now())", targetId, organization, scope, name, kind, transport,
                createdBy);
    }

    private void insertSolution(Hierarchy h, UUID solutionId, UUID catalogId) {
        runtime.update("INSERT INTO sm_control.solution (organization_id, organization_unit_id, environment_id,"
                + " solution_id, catalog_solution_id) VALUES (:o, :u, :e, :s, :c)",
                new MapSqlParameterSource().addValue("o", h.organization).addValue("u", h.unit)
                        .addValue("e", h.environment).addValue("s", solutionId).addValue("c", catalogId));
    }

    private void insertInstance(Hierarchy h, UUID instanceId) {
        runtime.getJdbcTemplate().update("INSERT INTO sm_control.instance (organization_id, organization_unit_id,"
                + " environment_id, solution_id, instance_id) VALUES (?, ?, ?, ?, ?)", h.organization, h.unit,
                h.environment, h.solution, instanceId);
    }

    private static String digest(char hex) {
        return "sha256:" + String.valueOf(hex).repeat(64);
    }
}
