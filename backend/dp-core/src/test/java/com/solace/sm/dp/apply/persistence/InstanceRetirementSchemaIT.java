package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 012 T3 - {@code sm_data} V023 (OPS-Q4 (a), {@code ops-501.md} sections 4.3 and 4.4): the lifecycle's two new values and
 * its one guarded backward move, the name and the placement resources a retired instance frees, and
 * {@code instance_retirement} (insert-only except a closing that is set once and together). As {@code sm_dp_runtime}, SQL
 * only: what the SERVICE does with these is {@code InstanceRetirementServiceIT}. Every refusal is asserted by SQLSTATE and by
 * the trigger's own wording or the constraint's name, so a refusal that fires for another reason is not a proof.
 */
@Testcontainers(disabledWithoutDocker = true)
class InstanceRetirementSchemaIT {

    private static final String HEALTH_GREEN = "[{\"placementId\": \"00000000-0000-0000-0000-000000000001\","
            + " \"lastHealth\": \"GREEN\"}]";

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;
    private static TransactionTemplate tx;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
        // the runtime template and this manager share ONE DataSource instance (SmDataPostgres caches it), so a
        // callback's statements are one transaction: the same-transaction rule is exactly what these tests probe
        tx = new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource()));
    }

    /** One active instance with one live placement on a target of the same tenant. */
    private record Live(UUID tenant, UUID instance, UUID target, UUID placement) {
    }

    private static Live live() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        UUID target = InventoryFixtures.target(tenant);
        return new Live(tenant, instance, target, InventoryFixtures.placement(tenant, instance, target));
    }

    private static MapSqlParameterSource key(Live l) {
        return new MapSqlParameterSource("t", l.tenant()).addValue("i", l.instance());
    }

    private static void setLifecycle(Live l, String lifecycle) {
        runtime.update("UPDATE sm_data.instance_state SET lifecycle = :l WHERE tenant_id = :t AND instance_id = :i",
                key(l).addValue("l", lifecycle));
    }

    private static String lifecycle(Live l) {
        return superuser.queryForObject("SELECT lifecycle FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", key(l), String.class);
    }

    /** An OPEN whole-instance record, as the service writes it. */
    private static UUID openRecord(Live l) {
        UUID id = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.instance_retirement (tenant_id, retirement_id, instance_id, placement_id,"
                        + " requested_by, requested_at, correlation_id, workload_disposition, last_health)"
                        + " VALUES (:t, :r, :i, NULL, :by, :now, :corr, 'leave-running', CAST(:h AS jsonb))",
                key(l).addValue("r", id).addValue("by", UUID.randomUUID()).addValue("now", OffsetDateTime.now())
                        .addValue("corr", UUID.randomUUID()).addValue("h", HEALTH_GREEN));
        return id;
    }

    private static int close(Live l, UUID record, String outcome) {
        return runtime.update("UPDATE sm_data.instance_retirement SET completed_at = now(), outcome = :o"
                        + " WHERE tenant_id = :t AND retirement_id = :r",
                key(l).addValue("r", record).addValue("o", outcome));
    }

    /** {@code active -> retiring} plus its open record: step 3 of the protocol, in SQL. */
    private static UUID begin(Live l) {
        setLifecycle(l, "retiring");
        return openRecord(l);
    }

    // ---- the lifecycle: exactly the moves that are ruled ----------------------------------------------------------------

    @Test
    void theLegalChainIsActiveToRetiringToRetired() {
        Live l = live();
        UUID record = begin(l);
        assertEquals("retiring", lifecycle(l));

        tx.executeWithoutResult(s -> {
            runtime.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND instance_id = :i"
                    + " AND retired_at IS NULL", key(l));
            close(l, record, "retired");
            setLifecycle(l, "retired");
        });
        assertEquals("retired", lifecycle(l));
    }

    @Test
    void everyOtherMoveIsRefusedByTheTriggerWithItsOwnWording() {
        // active -> {retired, pending, abandoned}
        for (String to : List.of("retired", "pending", "abandoned")) {
            Live l = live();
            Refusals.assertRaised("lifecycle may only move", () -> setLifecycle(l, to));
        }
        // retiring -> {pending, abandoned}
        for (String to : List.of("pending", "abandoned")) {
            Live l = live();
            begin(l);
            Refusals.assertRaised("lifecycle may only move", () -> setLifecycle(l, to));
        }
        // pending -> {retiring, retired}; abandoned -> {retiring, retired, active}
        for (String from : List.of("pending", "abandoned")) {
            for (String to : List.of("retiring", "retired", "active")) {
                if (from.equals("pending") && to.equals("active")) {
                    continue;                                     // the one legal pending move this loop would hit
                }
                UUID tenant = SmDataPostgres.freshTenant();
                UUID instance = UUID.randomUUID();
                InventoryFixtures.declare(tenant, instance, "pending");
                Live l = new Live(tenant, instance, null, null);
                if (from.equals("abandoned")) {
                    setLifecycle(l, "abandoned");
                }
                Refusals.assertRaised("lifecycle may only move", () -> setLifecycle(l, to));
            }
        }
        // a value that is no lifecycle at all is the CHECK's, by name
        Live l = live();
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_lifecycle", () -> setLifecycle(l, "deleted"));
    }

    @Test
    void retiredIsTerminalForEveryWriterTheOwnerIncluded() {
        Live l = live();
        UUID record = begin(l);
        tx.executeWithoutResult(s -> {
            runtime.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND instance_id = :i",
                    key(l));
            close(l, record, "retired");
            setLifecycle(l, "retired");
        });
        for (String to : List.of("pending", "active", "abandoned", "retiring")) {
            Refusals.assertRaised("lifecycle may only move", () -> setLifecycle(l, to));
            Refusals.assertRaised("lifecycle may only move", () -> superuser.update(
                    "UPDATE sm_data.instance_state SET lifecycle = :l WHERE tenant_id = :t AND instance_id = :i",
                    key(l).addValue("l", to)));
        }
    }

    @Test
    void retiringToRetiredIsRefusedWhileAPlacementIsStillLive() {
        Live l = live();
        UUID record = begin(l);
        close(l, record, "retired");
        Refusals.assertRaised("only after its placements are retired", () -> setLifecycle(l, "retired"));
        assertEquals("retiring", lifecycle(l), "refused: nothing moved");

        runtime.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND instance_id = :i", key(l));
        setLifecycle(l, "retired");                              // the control: with the placement retired it passes
        assertEquals("retired", lifecycle(l));
    }

    @Test
    void retiringBackToActiveNeedsItsRecordClosedRolledBackInTheSameTransaction() {
        Live l = live();
        UUID record = begin(l);

        Refusals.assertRaised("return from retiring to active only with its retirement closed rolled-back in the same"
                + " transaction", () -> setLifecycle(l, "active"));                    // (a) no closed record at all

        // (b) closed rolled-back in ANOTHER, earlier transaction: not the same transaction
        close(l, record, "rolled-back");
        Refusals.assertRaised("same transaction", () -> setLifecycle(l, "active"));
        assertEquals("retiring", lifecycle(l));
        assertEquals(1, superuser.queryForObject("SELECT count(*)::int FROM sm_data.instance_retirement"
                + " WHERE tenant_id = :t AND instance_id = :i AND outcome = 'rolled-back'", key(l), Integer.class));
    }

    @Test
    void aRolledBackRecordFromAnEarlierAttemptDoesNotLicenseALaterAttemptsFlip() {
        Live l = live();
        UUID first = begin(l);
        tx.executeWithoutResult(s -> {                           // attempt 1 rolled back, legally, in one transaction
            close(l, first, "rolled-back");
            setLifecycle(l, "active");
        });
        assertEquals("active", lifecycle(l));

        UUID second = begin(l);                                  // attempt 2: a new open record
        Refusals.assertRaised("same transaction", () -> setLifecycle(l, "active"));
        assertEquals("retiring", lifecycle(l), "the earlier attempt's closed row licenses nothing");

        tx.executeWithoutResult(s -> {                           // attempt 2 rolled back in its own transaction
            close(l, second, "rolled-back");
            setLifecycle(l, "active");
        });
        assertEquals("active", lifecycle(l));
        assertEquals(2, superuser.queryForObject("SELECT count(*)::int FROM sm_data.instance_retirement"
                + " WHERE tenant_id = :t AND instance_id = :i", key(l), Integer.class), "one record per attempt, both kept");
    }

    @Test
    void anotherInstancesRolledBackRecordLicensesNothing() {
        Live mine = live();
        Live other = live();
        begin(mine);
        UUID theirs = begin(other);
        Refusals.assertRaised("same transaction", () -> tx.executeWithoutResult(s -> {
            close(other, theirs, "rolled-back");              // closed rolled-back IN THIS transaction, but for the other instance
            setLifecycle(mine, "active");
        }));
        assertEquals("retiring", lifecycle(mine));
        assertEquals("retiring", lifecycle(other), "and the transaction rolled back whole");
    }

    // ---- the readiness stage (W2C-4) -----------------------------------------------------------------------------------

    @Test
    void theRetirementReadinessQueryFindsBothPartsOfV023AndEachPartCounts() {
        assertEquals(RetirementPersistenceConfiguration.EXPECTED, runtime.getJdbcTemplate().queryForObject(
                RetirementPersistenceConfiguration.READINESS_QUERY, Integer.class), "both parts present on the V023 schema");
        // each part must count: a LIKE that matches nothing drops the second part, so the query would read one short and the
        // stage would refuse (the V022 shape: a half-applied schema)
        String withoutTheVocabulary = RetirementPersistenceConfiguration.READINESS_QUERY.replace("%retiring%", "%no-such-value%");
        assertEquals(RetirementPersistenceConfiguration.EXPECTED - 1,
                runtime.getJdbcTemplate().queryForObject(withoutTheVocabulary, Integer.class));
        String withoutTheTable = RetirementPersistenceConfiguration.READINESS_QUERY.replace("'instance_retirement'", "'no_such_table'");
        assertEquals(RetirementPersistenceConfiguration.EXPECTED - 1,
                runtime.getJdbcTemplate().queryForObject(withoutTheTable, Integer.class));
    }

    // ---- what a retired instance frees (OPS-Q4) -------------------------------------------------------------------------

    @Test
    void aRetiringInstanceStillHoldsItsNameAndARetiredOneFreesIt() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID env = UUID.randomUUID();
        UUID first = declare(tenant, env, "orders");
        Live l = new Live(tenant, first, null, null);
        setLifecycle(l, "active");
        UUID record = begin(l);

        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "instance_name_once",
                () -> declare(tenant, env, "orders"), "retiring: it may be rolled back, so the name is held");

        close(l, record, "retired");
        setLifecycle(l, "retired");
        UUID again = declare(tenant, env, "orders");             // freed
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "instance_name_once",
                () -> declare(tenant, env, "orders"), "the new pending instance holds it again");
        assertTrue(again != null);

        // any number of retired (and abandoned) rows may share one name
        UUID second = declare(tenant, env, "twice");
        Live s = new Live(tenant, second, null, null);
        setLifecycle(s, "active");
        UUID secondRecord = begin(s);
        close(s, secondRecord, "retired");
        setLifecycle(s, "retired");
        UUID third = declare(tenant, env, "twice");
        Live t = new Live(tenant, third, null, null);
        setLifecycle(t, "active");
        UUID thirdRecord = begin(t);
        close(t, thirdRecord, "retired");
        setLifecycle(t, "retired");
        declare(tenant, env, "twice");
        assertEquals(3, superuser.queryForObject("SELECT count(*)::int FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND name = 'twice'", new MapSqlParameterSource("t", tenant), Integer.class));
    }

    @Test
    void aRetiredInstanceFreesItsContainerNameDirectoryHealthPortAndMetricsHostPort() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = InventoryFixtures.target(tenant);
        UUID instance = UUID.randomUUID();
        InventoryFixtures.declare(tenant, instance);
        Live l = new Live(tenant, instance, target, null);
        UUID placement = insertProbedPlacement(tenant, instance, target, "sm-cache", "/srv/sm/cache", 18080);
        insertMetricsEndpoint(tenant, placement, target, 8081, 28081);
        insertMetricsEndpoint(tenant, placement, target, 8082, 28082);   // W2C-2: the health-port direction's retired port

        // the controls: while the placement is live, each resource is held (V007's indexes, V016's two triggers)
        UUID other = UUID.randomUUID();
        InventoryFixtures.declare(tenant, other);
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "placement_container_once",
                () -> insertProbedPlacement(tenant, other, target, "sm-cache", "/srv/sm/other", 18081));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "placement_config_dir_once",
                () -> insertProbedPlacement(tenant, other, target, "sm-other", "/srv/sm/cache", 18081));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "placement_health_port_once",
                () -> insertProbedPlacement(tenant, other, target, "sm-other", "/srv/sm/other", 18080));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "health_port_is_a_metrics_port",
                () -> insertProbedPlacement(tenant, other, target, "sm-other", "/srv/sm/other", 28081));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "health_port_is_a_metrics_port",
                () -> insertProbedPlacement(tenant, other, target, "sm-other", "/srv/sm/other", 28082));
        UUID probe = insertProbedPlacement(tenant, other, target, "sm-probe", "/srv/sm/probe", 18082);
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "metrics_host_port_in_use",
                () -> insertMetricsEndpoint(tenant, probe, target, 8081, 28081));

        UUID record = begin(l);
        tx.executeWithoutResult(s -> {
            runtime.update("UPDATE sm_data.placement SET retired_at = now() WHERE tenant_id = :t AND instance_id = :i",
                    key(l));
            close(l, record, "retired");
            setLifecycle(l, "retired");
        });

        // after, the health-port direction (placement_health_port_free's retired_at filter, W2C-2): a placement of ANOTHER
        // instance may now take the retired placement's metrics host port as its health port, where the control above refused it
        UUID healthOnFreed = UUID.randomUUID();
        InventoryFixtures.declare(tenant, healthOnFreed);
        insertProbedPlacement(tenant, healthOnFreed, target, "sm-freed", "/srv/sm/freed", 28082);

        // after: the same names, directory and ports are free for a new declare
        UUID reborn = UUID.randomUUID();
        InventoryFixtures.declare(tenant, reborn);
        UUID again = insertProbedPlacement(tenant, reborn, target, "sm-cache", "/srv/sm/cache", 18080);
        insertMetricsEndpoint(tenant, again, target, 8081, 28081);
        assertEquals(1, superuser.queryForObject("SELECT count(*)::int FROM sm_data.placement_metrics_endpoint"
                + " WHERE tenant_id = :t AND placement_id = :p AND host_port = 28081",
                new MapSqlParameterSource("t", tenant).addValue("p", again), Integer.class));
    }

    @Test
    void aWholeInstanceRecordCannotBeInsertedAlreadyClosedSoItCannotForgeTheRollbackProof() {
        Live l = live();
        begin(l);
        // W2C-1: a closed whole-instance row born in one INSERT would license retiring -> active while the real open record
        // stays open. Refused by the stamp trigger, by constraint name, for each way of being closed.
        for (String closed : List.of("now(), 'rolled-back'", "now(), 'retired'")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_retirement_whole_born_open", () -> runtime.update(
                    "INSERT INTO sm_data.instance_retirement (tenant_id, retirement_id, instance_id, placement_id,"
                            + " requested_by, requested_at, correlation_id, workload_disposition, last_health,"
                            + " completed_at, outcome) VALUES (:t, gen_random_uuid(), :i, NULL, gen_random_uuid(), now(),"
                            + " gen_random_uuid(), 'leave-running', '[]'::jsonb, " + closed + ")", key(l)));
        }
        // and the forgery it would have enabled stays refused: the open record is still the only one
        Refusals.assertRaised("only with its retirement closed rolled-back in the same transaction",
                () -> setLifecycle(l, "active"));
        assertEquals("retiring", lifecycle(l));
        assertEquals(1, superuser.queryForObject("SELECT count(*)::int FROM sm_data.instance_retirement WHERE tenant_id = :t"
                + " AND instance_id = :i AND completed_at IS NULL", key(l), Integer.class));
    }

    // ---- instance_retirement: insert-only, closed once and together ---------------------------------------------------

    @Test
    void anUpdateMustCloseAnOpenRecordAndChangeNothingElse() {
        Live l = live();
        UUID record = begin(l);
        MapSqlParameterSource r = key(l).addValue("r", record);

        // a no-op, a half-closing and a clearing are all refused: completed_at and outcome are set together
        Refusals.assertRaised("must close it", () -> runtime.update("UPDATE sm_data.instance_retirement SET completed_at = now()"
                + " WHERE tenant_id = :t AND retirement_id = :r", r));
        Refusals.assertRaised("must close it", () -> runtime.update("UPDATE sm_data.instance_retirement SET outcome = 'retired'"
                + " WHERE tenant_id = :t AND retirement_id = :r", r));
        // the outcome is the closed vocabulary, by name
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_retirement_outcome", () -> runtime.update(
                "UPDATE sm_data.instance_retirement SET completed_at = now(), outcome = 'bogus'"
                        + " WHERE tenant_id = :t AND retirement_id = :r", r));
        // any other column is the privilege's (the runtime role has no UPDATE on it) ...
        for (String change : List.of("requested_by = gen_random_uuid()", "requested_at = now()",
                "correlation_id = gen_random_uuid()", "instance_id = gen_random_uuid()", "last_health = '[]'",
                "workload_disposition = 'leave-running'", "placement_id = NULL", "state_version = 1")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                    "UPDATE sm_data.instance_retirement SET " + change + " WHERE tenant_id = :t AND retirement_id = :r", r),
                    change);
        }
        // ... and the OWNER is held to it by the trigger
        Refusals.assertRaised("may change only completed_at and outcome", () -> superuser.update(
                "UPDATE sm_data.instance_retirement SET completed_at = now(), outcome = 'retired', requested_by ="
                        + " gen_random_uuid() WHERE tenant_id = :t AND retirement_id = :r", r));

        assertEquals(1, close(l, record, "retired"));
        for (String second : List.of("completed_at = now(), outcome = 'retired'", "completed_at = now(), outcome = 'rolled-back'")) {
            Refusals.assertRaised("closed once and never changes again", () -> runtime.update(
                    "UPDATE sm_data.instance_retirement SET " + second + " WHERE tenant_id = :t AND retirement_id = :r", r));
            Refusals.assertRaised("closed once and never changes again", () -> superuser.update(
                    "UPDATE sm_data.instance_retirement SET " + second + " WHERE tenant_id = :t AND retirement_id = :r", r));
        }
    }

    @Test
    void theRecordIsStampedFromTheOneSequenceOnInsertAndOnClosing() {
        Live l = live();
        UUID record = openRecord(l);
        long born = stamp(l, record);
        assertTrue(born > 0, "stamped by the trigger");
        close(l, record, "retired");
        assertTrue(stamp(l, record) > born, "closing is observable, so it draws the next value");
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "INSERT INTO sm_data.instance_retirement (tenant_id, retirement_id, instance_id, requested_by, requested_at,"
                        + " correlation_id, workload_disposition, last_health, state_version) VALUES (:t, gen_random_uuid(), :i,"
                        + " gen_random_uuid(), now(), gen_random_uuid(), 'leave-running', '[]', 1)", key(l)),
                "the runtime role cannot write the stamp");
    }

    @Test
    void onlyOneRecordMayBeOpenPerInstanceAndAClosedOneFreesTheSlot() {
        Live l = live();
        UUID first = openRecord(l);
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "instance_retirement_open_once", () -> openRecord(l));
        close(l, first, "rolled-back");
        openRecord(l);                                           // the control: a closed record frees the slot
    }

    @Test
    void theShapeOfARecordIsClosedAtEveryColumn() {
        Live l = live();
        String insert = "INSERT INTO sm_data.instance_retirement (tenant_id, retirement_id, instance_id, requested_by,"
                + " requested_at, correlation_id, workload_disposition, last_health) VALUES (:t, gen_random_uuid(), :i,"
                + " gen_random_uuid(), now(), gen_random_uuid(), :w, CAST(:h AS jsonb))";
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_retirement_disposition", () -> runtime.update(insert,
                key(l).addValue("w", "stop").addValue("h", "[]")));
        for (String bad : List.of("{}", "[\"x\"]", "[1]", "null",
                "[{\"placementId\": \"00000000-0000-0000-0000-000000000001\"}]",
                "[{\"lastHealth\": \"GREEN\"}]",
                "[{\"placementId\": \"not-a-uuid\", \"lastHealth\": \"GREEN\"}]",
                "[{\"placementId\": \"00000000-0000-0000-0000-000000000001\", \"lastHealth\": 5}]",
                "[{\"placementId\": \"00000000-0000-0000-0000-000000000001\", \"lastHealth\": \"a free text sentence\"}]",
                "[{\"placementId\": \"00000000-0000-0000-0000-000000000001\", \"lastHealth\": \"GREEN\", \"host\": \"vm-1\"}]")) {
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_retirement_health_shape", () -> runtime.update(insert,
                    key(l).addValue("w", "leave-running").addValue("h", bad)), bad);
        }
        for (String good : List.of("[]", HEALTH_GREEN,
                "[{\"placementId\": \"00000000-0000-0000-0000-000000000001\", \"lastHealth\": null},"
                        + " {\"placementId\": \"00000000-0000-0000-0000-000000000002\", \"lastHealth\": \"RESTART_EXPECTED\"}]")) {
            UUID fresh = SmDataPostgres.freshTenant();
            UUID instance = UUID.randomUUID();
            InventoryFixtures.declare(fresh, instance);
            runtime.update(insert, new MapSqlParameterSource("t", fresh).addValue("i", instance)
                    .addValue("w", "leave-running").addValue("h", good));
        }
    }

    @Test
    void aRecordClosesWithBothColumnsOrNeitherAndOnlyAWholeInstanceRecordCanRollBack() {
        Live l = live();
        String born = "INSERT INTO sm_data.instance_retirement (tenant_id, retirement_id, instance_id, placement_id,"
                + " requested_by, requested_at, correlation_id, workload_disposition, last_health, completed_at, outcome)"
                + " VALUES (:t, gen_random_uuid(), :i, :p, gen_random_uuid(), now(), gen_random_uuid(), 'leave-running', '[]',"
                + " :c, :o)";
        MapSqlParameterSource base = key(l).addValue("p", l.placement());
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_retirement_closed_together", () -> runtime.update(born,
                new MapSqlParameterSource(base.getValues()).addValue("c", OffsetDateTime.now()).addValue("o", null,
                        java.sql.Types.VARCHAR)));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_retirement_closed_together", () -> runtime.update(born,
                new MapSqlParameterSource(base.getValues()).addValue("c", null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                        .addValue("o", "retired")));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_retirement_placement_outcome", () -> runtime.update(born,
                new MapSqlParameterSource(base.getValues()).addValue("c", OffsetDateTime.now()).addValue("o", "rolled-back")));
        // a placement retirement is one statement: born closed, 'retired', is the shape plan 012 T5 writes
        runtime.update(born, new MapSqlParameterSource(base.getValues()).addValue("c", OffsetDateTime.now())
                .addValue("o", "retired"));
    }

    @Test
    void aRecordNamesAnInstanceAndAPlacementOfThatInstanceByForeignKey() {
        Live l = live();
        Live other = live();
        String insert = "INSERT INTO sm_data.instance_retirement (tenant_id, retirement_id, instance_id, placement_id,"
                + " requested_by, requested_at, correlation_id, workload_disposition, last_health) VALUES (:t, gen_random_uuid(),"
                + " :i, :p, gen_random_uuid(), now(), gen_random_uuid(), 'leave-running', '[]')";
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "instance_retirement_has_instance", () -> runtime.update(
                "INSERT INTO sm_data.instance_retirement (tenant_id, retirement_id, instance_id, requested_by, requested_at,"
                        + " correlation_id, workload_disposition, last_health) VALUES (:t, gen_random_uuid(), :i,"
                        + " gen_random_uuid(), now(), gen_random_uuid(), 'leave-running', '[]')",
                new MapSqlParameterSource("t", l.tenant()).addValue("i", UUID.randomUUID())), "an undeclared instance");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "instance_retirement_has_placement", () -> runtime.update(insert,
                key(l).addValue("p", other.placement())),
                "another instance's placement (and another tenant's): the stamp trigger names the foreign key");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "instance_retirement_has_placement", () -> runtime.update(insert,
                key(l).addValue("p", UUID.randomUUID())), "no such placement");
        // a placement that EXISTS in this tenant, on this target, but belongs to a SIBLING instance: the foreign key is
        // satisfied, so only the stamp trigger's own check refuses it (by the same name)
        UUID sibling = UUID.randomUUID();
        InventoryFixtures.declare(l.tenant(), sibling);
        UUID siblingPlacement = InventoryFixtures.placement(l.tenant(), sibling, l.target());
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "instance_retirement_has_placement", () -> runtime.update(insert,
                key(l).addValue("p", siblingPlacement)), "a sibling instance's placement of the same tenant");
        runtime.update(insert, key(l).addValue("p", l.placement()));          // the control: its own placement
    }

    @Test
    void theRuntimeRoleCannotDeleteARecordAndItsGrantsAreTheNarrowOnes() {
        Live l = live();
        UUID record = openRecord(l);
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "DELETE FROM sm_data.instance_retirement WHERE tenant_id = :t AND retirement_id = :r",
                key(l).addValue("r", record)));
        assertEquals(List.of("completed_at", "outcome"), columns("UPDATE"), "the whole UPDATE surface");
        assertEquals(List.of("completed_at", "correlation_id", "instance_id", "last_health", "outcome", "placement_id",
                        "requested_at", "requested_by", "retirement_id", "tenant_id", "workload_disposition"),
                columns("INSERT"), "everything but the stamp");
        assertEquals(List.of(), superuser.queryForList("SELECT privilege_type FROM information_schema.table_privileges"
                + " WHERE table_schema = 'sm_data' AND table_name = 'instance_retirement' AND grantee = 'sm_dp_runtime'"
                + " AND privilege_type IN ('DELETE', 'TRUNCATE')", Map.of(), String.class));
    }

    @Test
    void theRecordKeysByTenantFirst() {
        assertEquals(List.of("tenant_id", "retirement_id"), superuser.queryForList(
                "SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)"
                        + " WHERE i.indrelid = 'sm_data.instance_retirement'::regclass AND i.indisprimary"
                        + " ORDER BY array_position(i.indkey::int2[], a.attnum)", Map.of(), String.class));
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static List<String> columns(String privilege) {
        return superuser.queryForList("SELECT column_name FROM information_schema.column_privileges"
                        + " WHERE table_schema = 'sm_data' AND table_name = 'instance_retirement' AND grantee = 'sm_dp_runtime'"
                        + " AND privilege_type = :p ORDER BY column_name",
                new MapSqlParameterSource("p", privilege), String.class);
    }

    private static long stamp(Live l, UUID record) {
        return superuser.queryForObject("SELECT state_version FROM sm_data.instance_retirement WHERE tenant_id = :t"
                + " AND retirement_id = :r", key(l).addValue("r", record), Long.class);
    }

    private static UUID declare(UUID tenant, UUID environment, String name) {
        UUID id = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by, created_at)"
                        + " VALUES (:t, :i, :e, :c, 'cache', '2.4', :n, 'pending', 'via-ssh', :u, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", id).addValue("e", environment)
                        .addValue("c", UUID.randomUUID()).addValue("n", name).addValue("u", UUID.randomUUID())
                        .addValue("now", OffsetDateTime.now()));
        return id;
    }

    private static UUID insertProbedPlacement(UUID tenant, UUID instance, UUID target, String container, String directory,
            int healthPort) {
        UUID id = UUID.randomUUID();
        runtime.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " health_kind, health_path, health_port, health_strength, created_at)"
                        + " VALUES (:t, :p, :i, :x, :c, :d, '/app/external/spring/config', 'application.yml',"
                        + " 'ADDITIONAL_LOCATION', 10, 'actuator-health', '/actuator/health', :h, 'liveness', :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", id).addValue("i", instance)
                        .addValue("x", target).addValue("c", container).addValue("d", directory).addValue("h", healthPort)
                        .addValue("now", OffsetDateTime.now()));
        return id;
    }

    private static void insertMetricsEndpoint(UUID tenant, UUID placement, UUID target, int sourcePort, int hostPort) {
        runtime.update("INSERT INTO sm_data.placement_metrics_endpoint (tenant_id, placement_id, target_id, source_port,"
                        + " host_port, created_at) VALUES (:t, :p, :x, :s, :h, :now)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("p", placement).addValue("x", target)
                        .addValue("s", sourcePort).addValue("h", hostPort).addValue("now", OffsetDateTime.now()));
    }
}
