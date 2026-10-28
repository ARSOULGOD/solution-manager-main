package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.ssh.SshDockerPlacement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T2.1 — {@code sm_data} V007: the one {@code stateVersion} sequence and its stamp (OR-35),
 * {@code instance_state} with its per-instance acquisition mode (OR-71), {@code target_connection}
 * (OR-28) and the immutable, per-target-unique {@code placement} registry (OR-29).
 *
 * <p>Every statement runs as {@code sm_dp_runtime}, the credential the application holds — a privilege
 * or trigger proof made as the superuser proves nothing about the runtime. The exceptions are named: a
 * trigger that must hold for EVERY writer is also exercised as the superuser, which bypasses grants, so
 * only the trigger can refuse it.
 *
 * <p>Every refusal is asserted by SQLSTATE and by constraint, index or trigger wording (§3.3's harness
 * rule): "some error" would pass on a schema where the table could not be written at all.
 */
@Testcontainers(disabledWithoutDocker = true)
class InventorySchemaIT {

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    // ---------------------------------------------------------------------------------------------
    // the sequence and the stamp
    // ---------------------------------------------------------------------------------------------

    @Test
    void theSequenceIsOneUncachedNonCyclingBigintSequence() {
        Map<String, Object> s = superuser.queryForMap(
                "SELECT data_type::text AS data_type, min_value, cache_size, cycle"
                        + " FROM pg_sequences WHERE schemaname = 'sm_data' AND sequencename = 'state_version_seq'",
                Map.of());
        assertEquals("bigint", s.get("data_type"));
        assertEquals(1L, ((Number) s.get("min_value")).longValue());
        assertEquals(1L, ((Number) s.get("cache_size")).longValue(),
                "a cached sequence preallocates a block per SESSION, so a later committer could carry a lower"
                        + " stamp than an earlier one — OR-35's commit-order rule needs CACHE 1");
        assertEquals(Boolean.FALSE, s.get("cycle"));
        assertEquals(1, superuser.queryForObject(
                "SELECT count(*)::int FROM pg_sequences WHERE schemaname = 'sm_data'", Map.of(), Integer.class),
                "ONE sequence — not one per owning table");
    }

    @Test
    void everyInsertAndUpdateOfAnOwningRowTakesTheNextSequenceValue() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID a = declareViaSsh(tenant, "alpha");
        long afterInsertA = stamp(tenant, a);
        assertEquals(lastValue(), afterInsertA, "an INSERT takes the sequence's value, not a copy of it");

        setLifecycle(tenant, a, "active");
        long afterUpdateA = stamp(tenant, a);
        assertEquals(afterInsertA + 1, afterUpdateA, "an UPDATE re-stamps with the NEXT value");
        assertEquals(lastValue(), afterUpdateA);

        UUID b = declareViaSsh(tenant, "beta");
        assertEquals(afterUpdateA + 1, stamp(tenant, b),
                "the sequence is ONE for every instance, not one per row, and a plain write draws no gap");
    }

    @Test
    void theRuntimeRoleCannotSetStateVersionItself() {
        UUID tenant = SmDataPostgres.freshTenant();
        // M5, measured: the runtime role holds NO column privilege on state_version, so an explicit value is
        // REFUSED 42501 (INSERT and UPDATE) rather than silently overwritten.
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by,"
                        + " created_at, state_version) VALUES (:t, :i, :e, :c, 'cache', '2.4', 'x', 'pending',"
                        + " 'via-ssh', :u, :now, 999999)", instanceParams(tenant, UUID.randomUUID(), "x")));
        UUID i = declareViaSsh(tenant, "settable");
        long before = stamp(tenant, i);
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.instance_state SET state_version = 1 WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource("t", tenant).addValue("i", i)));
        assertEquals(before, stamp(tenant, i));
        // …and a writer that DOES hold the column (the superuser) still cannot choose the value: the trigger wins.
        superuser.update("UPDATE sm_data.instance_state SET state_version = 1, lifecycle = 'active'"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource("t", tenant).addValue("i", i));
        assertTrue(stamp(tenant, i) > before, "the trigger overwrites an explicit value from any writer");
        assertEquals(List.of(), runtimeInsertableColumnsNamed("instance_state", "state_version"),
                "state_version is in no runtime INSERT grant");
    }

    /**
     * Review Focus 1. Two sessions alternate writing ONE instance: the second blocks on the first's row
     * lock, the first commits, the second then draws its value and commits. Commit order must equal stamp
     * order across all 20 interleavings. The two connections persist for the whole test, so a per-session
     * sequence cache (the defect this guards) would hand each its own block and the stamps would run
     * backwards on every second round.
     */
    @Test
    void stampsWithinOneInstanceAreMonotonicInCommitOrder() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID instance = declareViaSsh(tenant, "contended");
        List<Long> committed = new ArrayList<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection a = SmDataPostgres.runtimeDataSource().getConnection();
                Connection b = SmDataPostgres.runtimeDataSource().getConnection()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            int pidA = backendPid(a);
            int pidB = backendPid(b);
            a.commit();
            b.commit();
            for (int round = 0; round < 20; round++) {
                boolean aFirst = round % 2 == 0;
                Connection first = aFirst ? a : b;
                Connection second = aFirst ? b : a;
                int secondPid = aFirst ? pidB : pidA;
                // the lifecycle only moves forward now, so each round re-writes the current value: an UPDATE of the
                // row is what stamps and what contends for the lock, whether or not a column changes
                String value = "pending";

                long s1 = stampOf(first, tenant, instance, value);           // first now holds the row lock
                Future<Long> pending = pool.submit(() -> stampOf(second, tenant, instance, value));
                awaitBlockedOnALock(secondPid);                               // second is queued behind it
                assertFalse(pending.isDone(), "round " + round + ": the second writer must WAIT for the first");
                first.commit();
                committed.add(s1);
                long s2 = pending.get(15, TimeUnit.SECONDS);
                second.commit();
                committed.add(s2);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(40, committed.size());
        for (int k = 1; k < committed.size(); k++) {
            assertTrue(committed.get(k) > committed.get(k - 1),
                    "commit order and stamp order disagree at commit #" + k + ": " + committed);
        }
    }

    @Test
    void stampsOnDifferentInstancesDoNotSerialize() throws Exception {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID x = declareViaSsh(tenant, "held");
        UUID y = declareViaSsh(tenant, "free");
        try (Connection holder = SmDataPostgres.runtimeDataSource().getConnection();
                Connection other = SmDataPostgres.runtimeDataSource().getConnection()) {
            holder.setAutoCommit(false);
            other.setAutoCommit(false);
            long heldStamp = stampOf(holder, tenant, x, "active");           // x's row lock stays held
            try (Statement s = other.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '3s'");                  // a global lock would fail here
            }
            long freeStamp = stampOf(other, tenant, y, "active");
            other.commit();
            assertTrue(freeStamp > heldStamp, "y drew its value while x's writer was still open");
            holder.rollback();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // instance_state: acquisition (OR-71)
    // ---------------------------------------------------------------------------------------------

    @Test
    void anAcquisitionModeIsOneOfTheTwoClosedValues() {
        UUID tenant = SmDataPostgres.freshTenant();
        insertInstance(tenant, UUID.randomUUID(), "direct", "direct-http");
        insertInstance(tenant, UUID.randomUUID(), "ssh", "via-ssh");

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_acquisition_mode",
                () -> insertInstance(tenant, UUID.randomUUID(), "bad-mode", "ssh"));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_acquisition_mode",
                () -> insertInstance(tenant, UUID.randomUUID(), "empty-mode", ""));
        Refusals.assertRefused(Refusals.NOT_NULL_VIOLATION, null,
                () -> insertInstance(tenant, UUID.randomUUID(), "no-mode", null));
    }

    @Test
    void noInstanceColumnOrConstraintCarriesAnAcquisitionAddress() {
        // OR-82: the DP polls the TARGET's registered address (target_connection.host); nothing about the address
        // comes from the browser, so instance_state keeps the MODE and no host-shaped column or constraint.
        assertEquals(List.of("acquisition_mode"), superuser.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'sm_data'"
                        + " AND table_name = 'instance_state' AND column_name LIKE 'acquisition%'"
                        + " ORDER BY column_name", Map.of(), String.class),
                "acquisition_mode is the only acquisition column on instance_state");
        assertEquals(List.of(), superuser.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'sm_data'"
                        + " AND table_name = 'instance_state'"
                        + " AND (column_name ~ 'host|addr|endpoint|url|direct')", Map.of(), String.class),
                "no host-, address-, endpoint- or url-shaped column on instance_state");
        assertEquals(List.of("instance_acquisition_mode"), superuser.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'sm_data.instance_state'::regclass"
                        + " AND conname LIKE '%acquisition%' ORDER BY conname", Map.of(), String.class),
                "the only acquisition constraint is the mode's closed vocabulary");
    }

    @Test
    void theRuntimeRoleCannotChangeAnInstancesAcquisition() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = UUID.randomUUID();
        insertInstance(tenant, i, "frozen", "direct-http");
        for (String change : List.of("acquisition_mode = 'via-ssh'",
                "declared_package_version = '9.9'", "name = 'renamed'",
                "tenant_id = gen_random_uuid()", "environment_id = gen_random_uuid()")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                    "UPDATE sm_data.instance_state SET " + change + " WHERE tenant_id = :t AND instance_id = :i",
                    new MapSqlParameterSource("t", tenant).addValue("i", i)), change);
        }
        // V010 (plan 010 T5.2) adds the two head columns the apply and config paths advance; identity, the declared
        // package version and the acquisition mode stay unwritable (the loop above proves each one).
        assertEquals(List.of("last_generation", "lifecycle", "reached_rung"), runtimeUpdatableColumns("instance_state"),
                "the whole runtime UPDATE surface on instance_state is lifecycle and the two head columns");
        assertEquals("direct-http", superuser.queryForObject(
                "SELECT acquisition_mode FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource("t", tenant).addValue("i", i), String.class));
    }

    @Test
    void anInstancesAcquisitionSurvivesAReTargetBecauseItLivesOnTheInstanceNotThePlacement() {
        assertEquals(0, superuser.queryForObject(
                "SELECT count(*)::int FROM information_schema.columns WHERE table_schema = 'sm_data'"
                        + " AND table_name = 'placement' AND column_name LIKE 'acquisition%'",
                Map.of(), Integer.class), "OR-71: no per-placement acquisition column exists");

        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = UUID.randomUUID();
        insertInstance(tenant, i, "moves", "direct-http");
        UUID t1 = insertTarget(targetRow(tenant));
        UUID t2 = insertTarget(targetRow(tenant));
        UUID p1 = insertPlacement(placementRow(tenant, i, t1));
        retire(tenant, p1);
        insertPlacement(placementRow(tenant, i, t2));                           // the re-target: a NEW row

        Map<String, Object> inst = superuser.queryForMap(
                "SELECT acquisition_mode FROM sm_data.instance_state"
                        + " WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource("t", tenant).addValue("i", i));
        assertEquals("direct-http", inst.get("acquisition_mode"));
    }

    @Test
    void lifecycleAndNameAreChecked() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "ok");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_lifecycle", () -> runtime.update(
                "UPDATE sm_data.instance_state SET lifecycle = 'deleted' WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource("t", tenant).addValue("i", i)));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_name",
                () -> insertInstance(tenant, UUID.randomUUID(), "", "via-ssh"));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "instance_name",
                () -> insertInstance(tenant, UUID.randomUUID(), "n".repeat(129), "via-ssh"));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "instance_name_once",
                () -> insertInstance(tenant, UUID.randomUUID(), "ok", "via-ssh"),
                "the same name in the same environment");
    }

    /** OR-88: names are unique only among pending and active instances; an abandoned declare frees its name. */
    @Test
    void anAbandonedInstanceFreesItsNameButTwoLiveInstancesStillCollide() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID first = declareViaSsh(tenant, "retry");
        setLifecycle(tenant, first, "abandoned");

        UUID second = declareViaSsh(tenant, "retry");                       // the retried declare, same name, same environment
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "instance_name_once",
                () -> insertInstance(tenant, UUID.randomUUID(), "retry", "via-ssh"),
                "a pending instance holds the name");

        setLifecycle(tenant, second, "active");
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "instance_name_once",
                () -> insertInstance(tenant, UUID.randomUUID(), "retry", "via-ssh"),
                "an active instance holds the name");

        // abandoned rows are never deleted, and any number of them may share one name
        UUID third = declareViaSsh(tenant, "other");
        setLifecycle(tenant, third, "abandoned");
        UUID fourth = declareViaSsh(tenant, "other");
        setLifecycle(tenant, fourth, "abandoned");
        declareViaSsh(tenant, "other");
        assertEquals(3, superuser.queryForObject(
                "SELECT count(*)::int FROM sm_data.instance_state WHERE tenant_id = :t AND name = 'other'",
                new MapSqlParameterSource("t", tenant), Integer.class));
    }

    /** S2 review: the order is enforced by the database for every writer, not only by the service's conditional UPDATE. */
    @Test
    void anInstancesLifecycleOnlyMovesForwardFromPending() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID abandoned = declareViaSsh(tenant, "was-abandoned");
        setLifecycle(tenant, abandoned, "abandoned");
        UUID active = declareViaSsh(tenant, "is-active");
        setLifecycle(tenant, active, "active");

        for (String to : new String[]{"active", "pending"}) {
            Refusals.assertRaised("lifecycle", () -> setLifecycle(tenant, abandoned, to));
        }
        for (String to : new String[]{"pending", "abandoned"}) {
            Refusals.assertRaised("lifecycle", () -> setLifecycle(tenant, active, to));
        }
        // the same value is not a transition, and the as-owner path is held to the same order
        assertDoesNotThrow(() -> setLifecycle(tenant, active, "active"));
        Refusals.assertRaised("lifecycle", () -> superuser.update(
                "UPDATE sm_data.instance_state SET lifecycle = 'active' WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource("t", tenant).addValue("i", abandoned)));
    }

    // ---------------------------------------------------------------------------------------------
    // placement: immutable, unique per target, inside the envelope
    // ---------------------------------------------------------------------------------------------

    @Test
    void thePlacementRowIsImmutableExceptRetirement() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "immutable");
        UUID target = insertTarget(targetRow(tenant));
        UUID p = insertPlacement(placementRow(tenant, i, target));

        // the runtime role: only retired_at and retired_by (V026) are updatable at all (the grant) …
        assertEquals(List.of("retired_at", "retired_by"), runtimeUpdatableColumns("placement"));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.placement SET container_name = 'sm-b' WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource("t", tenant).addValue("p", p)));
        // … and the trigger holds against a writer the grant does not reach (the superuser bypasses grants).
        for (String change : List.of("container_name = 'sm-b'", "target_id = gen_random_uuid()",
                "health_port = 9999", "file_name = 'other.yml'", "retained_generations = 3",
                "retired_at = NULL, container_name = 'sm-b'")) {
            Refusals.assertRaised("a placement is immutable", () -> superuser.update(
                    "UPDATE sm_data.placement SET " + change + " WHERE tenant_id = :t AND placement_id = :p",
                    new MapSqlParameterSource("t", tenant).addValue("p", p)));
        }
        Refusals.assertRaised("a placement is immutable", () -> superuser.update(
                "UPDATE sm_data.placement SET retired_at = now(), container_name = 'sm-b'"
                        + " WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource("t", tenant).addValue("p", p)));

        retire(tenant, p);                                                       // the one legal change …
        Refusals.assertRaised("a placement is immutable", () -> retire(tenant, p));          // … once
        Refusals.assertRaised("a placement is immutable", () -> superuser.update(
                "UPDATE sm_data.placement SET retired_at = NULL WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource("t", tenant).addValue("p", p)));
    }

    /** Review Focus 2 — three cases, each refused by its own named index. */
    @Test
    void aSecondPlacementOnOneTargetMayNotReuseContainerNameConfigDirOrHealthPort() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i1 = declareViaSsh(tenant, "one");
        UUID i2 = declareViaSsh(tenant, "two");
        UUID target = insertTarget(targetRow(tenant));
        Map<String, Object> first = placementRow(tenant, i1, target);
        insertPlacement(first);

        Map<String, Object> sameContainer = placementRow(tenant, i2, target);
        sameContainer.put("container_name", first.get("container_name"));
        sameContainer.put("remote_config_directory", "/srv/sm/other");
        sameContainer.put("health_port", 18082);
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "placement_container_once",
                () -> insertPlacement(sameContainer));

        Map<String, Object> sameDir = placementRow(tenant, i2, target);
        sameDir.put("container_name", "sm-other");
        sameDir.put("remote_config_directory", first.get("remote_config_directory"));
        sameDir.put("health_port", 18083);
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "placement_config_dir_once",
                () -> insertPlacement(sameDir));

        Map<String, Object> samePort = placementRow(tenant, i2, target);
        samePort.put("container_name", "sm-other");
        samePort.put("remote_config_directory", "/srv/sm/other");
        samePort.put("health_port", first.get("health_port"));
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "placement_health_port_once",
                () -> insertPlacement(samePort));

        // the uniqueness is PER TARGET: a different host may reuse all three
        UUID otherTarget = insertTarget(targetRow(tenant));
        Map<String, Object> elsewhere = placementRow(tenant, i2, otherTarget);
        elsewhere.put("container_name", first.get("container_name"));
        elsewhere.put("remote_config_directory", first.get("remote_config_directory"));
        elsewhere.put("health_port", first.get("health_port"));
        insertPlacement(elsewhere);
    }

    @Test
    void aRetiredPlacementFreesItsContainerNameForANewOne() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "retargeted");
        UUID target = insertTarget(targetRow(tenant));
        Map<String, Object> row = placementRow(tenant, i, target);
        UUID old = insertPlacement(row);

        Map<String, Object> clash = placementRow(tenant, i, target);
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "placement_container_once", () -> insertPlacement(clash));

        retire(tenant, old);
        assertDoesNotThrow(() -> insertPlacement(placementRow(tenant, i, target)),
                "container name, config directory and health port are all free again once the holder retires");
    }

    @Test
    void aSecondLiveTargetForTheSameHostKeyIsRefused() {
        UUID tenant = SmDataPostgres.freshTenant();
        String fp = uniqueFingerprint();
        Map<String, Object> first = targetRow(tenant);
        first.put("host_key_fingerprint", fp);
        UUID target = insertTarget(first);

        // an alias for the same VM, and a second sshd port on it: the host KEY identifies the host
        Map<String, Object> alias = targetRow(tenant);
        alias.put("host_key_fingerprint", fp);
        alias.put("host", "alias.example");
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "target_connection_host_once",
                () -> insertTarget(alias));
        Map<String, Object> otherPort = targetRow(tenant);
        otherPort.put("host_key_fingerprint", fp);
        otherPort.put("port", 2222);
        Refusals.assertRefused(Refusals.UNIQUE_VIOLATION, "target_connection_host_once",
                () -> insertTarget(otherPort));
        // OR-89: the rule is PER ORGANIZATION. Another organization may register the same machine; the one
        // consequence (one host driven by two organizations' configurations) is the owner's accepted ruling.
        Map<String, Object> otherTenant = targetRow(SmDataPostgres.freshTenant());
        otherTenant.put("host_key_fingerprint", fp);
        assertDoesNotThrow(() -> insertTarget(otherTenant),
                "OR-89: two organizations may each register the same host key");

        runtime.update("UPDATE sm_data.target_connection SET retired_at = :now"
                        + " WHERE tenant_id = :t AND target_id = :x",
                new MapSqlParameterSource("now", OffsetDateTime.now()).addValue("t", tenant).addValue("x", target));
        assertDoesNotThrow(() -> insertTarget(alias), "a retired target frees its host key");
    }

    /** S2 review: the service filters retired targets; the database refuses them too, for every writer. */
    @Test
    void aPlacementCannotBePlacedOnARetiredTargetAndATargetCannotRetireUnderLivePlacements() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "retired-target");
        UUID target = insertTarget(targetRow(tenant));
        UUID p = insertPlacement(placementRow(tenant, i, target));

        PSQLException live = Refusals.assertRaised("live placement", () -> runtime.update(
                "UPDATE sm_data.target_connection SET retired_at = now() WHERE tenant_id = :t AND target_id = :x",
                new MapSqlParameterSource("t", tenant).addValue("x", target)));
        assertFalse(live.getMessage().contains(p.toString()), "names no row: " + live.getMessage());

        retire(tenant, p);
        runtime.update("UPDATE sm_data.target_connection SET retired_at = now() WHERE tenant_id = :t AND target_id = :x",
                new MapSqlParameterSource("t", tenant).addValue("x", target));

        PSQLException pg = Refusals.assertRefused(Refusals.CHECK_VIOLATION, null,
                () -> insertPlacement(placementRow(tenant, i, target)));
        assertTrue(pg.getMessage().contains("target_id") || pg.getMessage().contains("retired"), pg.getMessage());
    }

    @Test
    void aPlacementOutsideTheTargetsConfigRootIsRefused() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "envelope");
        UUID target = insertTarget(targetRow(tenant));                           // root /srv/sm, prefix sm-
        Map<String, Object> outside = placementRow(tenant, i, target);
        outside.put("remote_config_directory", "/home/ec2-user/.ssh");
        PSQLException pg = Refusals.assertRefused(Refusals.CHECK_VIOLATION, null, () -> insertPlacement(outside));
        assertTrue(pg.getMessage().contains("remote_config_directory"), "names the FIELD: " + pg.getMessage());
        assertFalse(pg.getMessage().contains(".ssh"), "and never the value: " + pg.getMessage());
    }

    @Test
    void aSiblingPathSharingTheRootsPrefixIsRefused() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "sibling");
        UUID target = insertTarget(targetRow(tenant));                           // root /srv/sm
        Map<String, Object> sibling = placementRow(tenant, i, target);
        sibling.put("remote_config_directory", "/srv/sm-evil");
        PSQLException pg = Refusals.assertRefused(Refusals.CHECK_VIOLATION, null, () -> insertPlacement(sibling));
        assertTrue(pg.getMessage().contains("remote_config_directory"), pg.getMessage());

        Map<String, Object> parent = placementRow(tenant, i, target);
        parent.put("remote_config_directory", "/srv");                            // above the root
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, null, () -> insertPlacement(parent));

        // the root itself and anything beneath it are inside
        Map<String, Object> atRoot = placementRow(tenant, i, target);
        atRoot.put("remote_config_directory", "/srv/sm");
        atRoot.put("container_name", "sm-at-root");
        atRoot.put("health_port", 18090);
        insertPlacement(atRoot);
        Map<String, Object> deep = placementRow(tenant, i, target);
        deep.put("remote_config_directory", "/srv/sm/a/b/c");
        deep.put("container_name", "sm-deep");
        deep.put("health_port", 18091);
        insertPlacement(deep);
    }

    @Test
    void aContainerOutsideTheTargetsPrefixIsRefused() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "prefix");
        UUID target = insertTarget(targetRow(tenant));                           // prefix sm-
        Map<String, Object> outside = placementRow(tenant, i, target);
        outside.put("container_name", "postgres-prod");
        PSQLException pg = Refusals.assertRefused(Refusals.CHECK_VIOLATION, null, () -> insertPlacement(outside));
        assertTrue(pg.getMessage().contains("container_name"), "names the FIELD: " + pg.getMessage());
        assertFalse(pg.getMessage().contains("postgres-prod"), "never the value: " + pg.getMessage());

        // '_' is a LIKE wildcard: a prefix built on it must still match literally, not by pattern
        Map<String, Object> underscoreRow = targetRow(tenant);
        underscoreRow.put("allowed_container_prefix", "a_c");
        UUID underscoreTarget = insertTarget(underscoreRow);
        Map<String, Object> wildcard = placementRow(tenant, i, underscoreTarget);
        wildcard.put("container_name", "abc-container");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, null, () -> insertPlacement(wildcard));
    }

    @Test
    void theRuntimeRoleCannotWidenTheEnvelope() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = insertTarget(targetRow(tenant));
        for (String change : List.of("allowed_config_root = '/'", "allowed_config_root = '/home'",
                "allowed_container_prefix = 'a'", "created_by = gen_random_uuid()", "transport = 'ssh-docker'")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                    "UPDATE sm_data.target_connection SET " + change + " WHERE tenant_id = :t AND target_id = :x",
                    new MapSqlParameterSource("t", tenant).addValue("x", target)), change);
        }
        assertEquals(List.of("credential_public_fingerprint", "credential_ref", "host", "host_key_algorithm", "host_key_fingerprint", "port", "retired_at", "ssh_user"),
                runtimeUpdatableColumns("target_connection"),
                "the mutable connection details (OR-28), the stored key's public fingerprint (V022), the pinned key's type (V027) and retirement — never the envelope");
    }

    @Test
    void aProbeIsAllOrNothing() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "probe");
        UUID target = insertTarget(targetRow(tenant));

        Map<String, Object> none = placementRow(tenant, i, target);
        for (String c : List.of("health_kind", "health_path", "health_port", "health_strength")) {
            none.put(c, null);
        }
        none.put("container_name", "sm-noprobe");
        none.put("remote_config_directory", "/srv/sm/noprobe");
        insertPlacement(none);                                                   // no probe at all is legal

        // each single missing member, and each single lone member, is refused by the one CHECK
        for (String missing : List.of("health_kind", "health_path", "health_port", "health_strength")) {
            Map<String, Object> partial = placementRow(tenant, i, target);
            partial.put(missing, null);
            partial.put("container_name", "sm-p-" + missing.substring(7));
            partial.put("remote_config_directory", "/srv/sm/p-" + missing.substring(7));
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "health_probe_complete",
                    () -> insertPlacement(partial), "probe missing " + missing);
        }
        for (String lone : List.of("health_kind", "health_path", "health_port", "health_strength")) {
            Map<String, Object> only = placementRow(tenant, i, target);
            for (String c : List.of("health_kind", "health_path", "health_port", "health_strength")) {
                if (!c.equals(lone)) {
                    only.put(c, null);
                }
            }
            only.put("container_name", "sm-l-" + lone.substring(7));
            only.put("remote_config_directory", "/srv/sm/l-" + lone.substring(7));
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "health_probe_complete",
                    () -> insertPlacement(only), "only " + lone);
        }
    }

    @Test
    void theProbeVocabularyAndMechanismAreClosed() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "vocab");
        UUID target = insertTarget(targetRow(tenant));
        for (String[] bad : new String[][] {
                {"health_kind", "broker-heartbeat", "placement_health_kind"},
                {"health_strength", "deep", "placement_health_strength"},
                {"mechanism", "additional-location", "placement_mechanism"},
                {"health_path", "actuator/health", "placement_health_path"}}) {
            Map<String, Object> row = placementRow(tenant, i, target);
            row.put(bad[0], bad[1]);
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, bad[2], () -> insertPlacement(row), bad[0]);
        }
        Map<String, Object> badPort = placementRow(tenant, i, target);
        badPort.put("health_port", 0);
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "placement_health_port", () -> insertPlacement(badPort));
        Map<String, Object> badRetained = placementRow(tenant, i, target);
        badRetained.put("retained_generations", 0);
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "placement_retained_generations",
                () -> insertPlacement(badRetained));
        Map<String, Object> overwrite = placementRow(tenant, i, target);
        overwrite.put("mechanism", "OVERWRITE_IN_PLACE");
        insertPlacement(overwrite);
    }

    @Test
    void theSentinelIsNotAStorablePlacementId() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "sentinel");
        UUID target = insertTarget(targetRow(tenant));
        Map<String, Object> row = placementRow(tenant, i, target);
        row.put("placement_id", UUID.fromString("00000000-0000-0000-0000-000000000000"));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "placement_not_sentinel", () -> insertPlacement(row));
    }

    @Test
    void aPlacementCannotReferenceAnUnknownInstanceOrTargetOrAnotherTenantsOne() {
        UUID tenantA = SmDataPostgres.freshTenant();
        UUID tenantB = SmDataPostgres.freshTenant();
        UUID instanceA = declareViaSsh(tenantA, "a-inst");
        UUID targetA = insertTarget(targetRow(tenantA));
        UUID instanceB = declareViaSsh(tenantB, "b-inst");
        UUID targetB = insertTarget(targetRow(tenantB));

        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "placement_tenant_id_instance_id_fkey",
                () -> insertPlacement(placementRow(tenantA, UUID.randomUUID(), targetA)), "unknown instance");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "placement_tenant_id_target_id_fkey",
                () -> insertPlacement(placementRow(tenantA, instanceA, UUID.randomUUID())), "unknown target");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "placement_tenant_id_instance_id_fkey",
                () -> insertPlacement(placementRow(tenantB, instanceA, targetB)), "tenant B names A's instance");
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "placement_tenant_id_target_id_fkey",
                () -> insertPlacement(placementRow(tenantB, instanceB, targetA)), "tenant B names A's target");
    }

    // ---------------------------------------------------------------------------------------------
    // grammar parity with SshDockerPlacement — two enforcers, the codebase's standing pattern
    // ---------------------------------------------------------------------------------------------

    /** {@code table, field, bad value, the CHECK that must refuse it}. */
    static Stream<Arguments> badValues() {
        List<Arguments> a = new ArrayList<>();
        for (String v : List.of("c;id", "c d", "c$(id)", "c/x", "c\nx", "c" + "x".repeat(128))) {
            a.add(Arguments.of("placement", "container_name", v, "placement_container_name"));
        }
        for (String v : List.of("/srv/../x", "/srv/a/", "/srv//a", "/srv/a b", "/srv/a\\b", "/srv/a;b", "/srv/a..b")) {
            a.add(Arguments.of("placement", "remote_config_directory", v, "placement_config_directory"));
        }
        for (String v : List.of("relative", "/a/../b", "/a/", "/a b", "//a", "/", "/a\\b", "/a;b")) {
            a.add(Arguments.of("placement", "container_config_path", v, "placement_container_config_path"));
        }
        for (String v : List.of("a/b", "a b", ".hidden", "a;b", "")) {
            a.add(Arguments.of("placement", "file_name", v, "placement_file_name"));
        }
        for (String v : List.of("bad host", "-a", "a_b", "a;b", "a/b", "")) {
            a.add(Arguments.of("target_connection", "host", v, "target_connection_host"));
        }
        for (String v : List.of("1abc", "a b", "a;id", "a".repeat(33), "")) {
            a.add(Arguments.of("target_connection", "ssh_user", v, "target_connection_user"));
        }
        for (String v : List.of("SHA256:short", "MD5:abcdefghijklmnopqrstuvwxABCDEFGH12345678",
                "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678===", "SHA256:abcdefghijklmnopqrstuvwx ABCDEFGH",
                "")) {
            a.add(Arguments.of("target_connection", "host_key_fingerprint", v, "target_connection_fp"));
        }
        for (int v : new int[] {0, 65536, -1}) {
            a.add(Arguments.of("target_connection", "port", v, "target_connection_port"));
        }
        for (String v : List.of("/", "relative", "/srv/", "/srv/../x", "/srv//x", "/srv/a b", "/srv/a;b", "")) {
            a.add(Arguments.of("target_connection", "allowed_config_root", v, "target_connection_config_root"));
        }
        return a.stream();
    }

    @ParameterizedTest(name = "{0}.{1} refuses {2}")
    @MethodSource("badValues")
    void everyGrammarCheckRefusesWhatSshDockerPlacementRefuses(String table, String field, Object bad,
            String check) {
        // 1. the Java enforcer refuses it — except the two allowed_* fields, which only SQL carries but whose
        //    grammar IS the path grammar (allowed_config_root) and the token grammar (allowed_container_prefix)
        assertThrows(IllegalArgumentException.class, () -> javaPlacement(field, bad),
                "SshDockerPlacement must refuse this value too, or the two enforcers disagree");

        // 2. the SQL enforcer refuses it, by the named CHECK
        UUID tenant = SmDataPostgres.freshTenant();
        if (table.equals("placement")) {
            UUID i = declareViaSsh(tenant, "grammar");
            Map<String, Object> targetRow = targetRow(tenant);
            targetRow.put("allowed_container_prefix", "c");
            targetRow.put("allowed_config_root", "/srv");
            UUID target = insertTarget(targetRow);
            Map<String, Object> row = placementRow(tenant, i, target);
            row.put("container_name", "c-ok");
            row.put("remote_config_directory", "/srv/ok");
            row.put(field, bad);
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, check, () -> insertPlacement(row), field);
        } else {
            Map<String, Object> row = targetRow(tenant);
            row.put(field, bad);
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, check, () -> insertTarget(row), field);
        }
    }

    @Test
    void theContainerPrefixGrammarIsTheTokenGrammarOfOneToSixtyFourCharacters() {
        for (String ok : List.of("c", "c.x_y-z", "C0", "c" + "x".repeat(63))) {
            Map<String, Object> row = targetRow(SmDataPostgres.freshTenant());
            row.put("allowed_container_prefix", ok);
            insertTarget(row);
        }
        for (String bad : List.of(";x", "a b", ".a", "-a", "", "c" + "x".repeat(64))) {
            Map<String, Object> row = targetRow(SmDataPostgres.freshTenant());
            row.put("allowed_container_prefix", bad);
            Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_container_prefix",
                    () -> insertTarget(row), "prefix of length " + bad.length());
        }
    }

    @Test
    void goodValuesAreAcceptedByBothEnforcers() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "good");
        UUID target = insertTarget(targetRow(tenant));
        Map<String, Object> row = placementRow(tenant, i, target);
        row.put("container_name", "sm-cache.v2_x");
        row.put("remote_config_directory", "/srv/sm/a.b/c-d_e");
        row.put("container_config_path", "/app/external/spring/config");
        row.put("file_name", "application-prod.yml");
        insertPlacement(row);
        assertDoesNotThrow(() -> new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "vm-1.corp.example", 22, "ec2-user", java.util.UUID.randomUUID(), uniqueFingerprint(), "sm-cache.v2_x", "/srv/sm/a.b/c-d_e", "/srv/sm/a.b/c-d_e", "/app/external/spring/config", "application-prod.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10));
    }

    // ---------------------------------------------------------------------------------------------
    // deletes and tenant scope
    // ---------------------------------------------------------------------------------------------

    @Test
    void theRuntimeRoleCannotDeleteAnyInventoryRow() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID i = declareViaSsh(tenant, "undeletable");
        UUID target = insertTarget(targetRow(tenant));
        insertPlacement(placementRow(tenant, i, target));
        for (String table : List.of("instance_state", "placement", "target_connection")) {
            Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                    "DELETE FROM sm_data." + table + " WHERE tenant_id = :t",
                    new MapSqlParameterSource("t", tenant)), table);
        }
        // config_generation (V010, plan 010 T5.2) is the second prunable table: DELETE exists for retention only, and
        // a trigger refuses the instance's latest generation and every generation an apply request names.
        assertEquals(List.of("apply_attempt", "config_generation"), superuser.queryForList(
                "SELECT table_name FROM information_schema.table_privileges WHERE table_schema = 'sm_data'"
                        + " AND grantee = 'sm_dp_runtime' AND privilege_type = 'DELETE' ORDER BY table_name",
                Map.of(), String.class), "the inventory is in no DELETE grant at all");
    }

    @Test
    void everyQueryShapeIsTenantScoped() {
        UUID tenantA = SmDataPostgres.freshTenant();
        UUID tenantB = SmDataPostgres.freshTenant();
        UUID instance = declareViaSsh(tenantA, "mine");
        UUID target = insertTarget(targetRow(tenantA));
        UUID placement = insertPlacement(placementRow(tenantA, instance, target));

        MapSqlParameterSource asB = new MapSqlParameterSource("t", tenantB).addValue("i", instance)
                .addValue("x", target).addValue("p", placement);
        assertEquals(0, count("SELECT count(*)::int FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", asB), "tenant B cannot read A's instance by id");
        assertEquals(0, count("SELECT count(*)::int FROM sm_data.target_connection WHERE tenant_id = :t"
                + " AND target_id = :x", asB));
        assertEquals(0, count("SELECT count(*)::int FROM sm_data.placement WHERE tenant_id = :t"
                + " AND placement_id = :p", asB));
        // the same ids are usable under B: every key leads with the tenant, so ids never collide across tenants
        Map<String, Object> bInstance = instanceParamsMap(tenantB, instance, "mine");
        insertInstanceRow(bInstance);
        Map<String, Object> bTarget = targetRow(tenantB);
        bTarget.put("target_id", target);
        insertTarget(bTarget);
        Map<String, Object> bPlacement = placementRow(tenantB, instance, target);
        bPlacement.put("placement_id", placement);
        insertPlacement(bPlacement);
    }

    // ---------------------------------------------------------------------------------------------
    // helpers — every statement binds its parameters (I5); column names come from this file only
    // ---------------------------------------------------------------------------------------------

    private static final String[] TARGET_COLUMNS = {"tenant_id", "target_id", "transport", "host", "port",
            "ssh_user", "host_key_fingerprint", "credential_ref", "allowed_config_root",
            "allowed_container_prefix", "created_by", "created_at"};
    private static final String[] PLACEMENT_COLUMNS = {"tenant_id", "placement_id", "instance_id", "target_id",
            "container_name", "remote_config_directory", "container_config_path", "file_name", "mechanism",
            "retained_generations", "health_kind", "health_path", "health_port", "health_strength", "created_at"};

    /** A legal target row: root {@code /srv/sm}, container prefix {@code sm-}, a fresh unique host key. */
    private static Map<String, Object> targetRow(UUID tenant) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", tenant);
        m.put("target_id", UUID.randomUUID());
        m.put("transport", "ssh-docker");
        m.put("host", "vm-1.corp.example");
        m.put("port", 22);
        m.put("ssh_user", "ec2-user");
        m.put("host_key_fingerprint", uniqueFingerprint());
        m.put("credential_ref", UUID.randomUUID());
        m.put("allowed_config_root", "/srv/sm");
        m.put("allowed_container_prefix", "sm-");
        m.put("created_by", UUID.randomUUID());
        m.put("created_at", OffsetDateTime.now());
        return m;
    }

    /** A legal placement row inside {@link #targetRow}'s envelope, with a complete actuator probe. */
    private static Map<String, Object> placementRow(UUID tenant, UUID instance, UUID target) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant_id", tenant);
        m.put("placement_id", UUID.randomUUID());
        m.put("instance_id", instance);
        m.put("target_id", target);
        m.put("container_name", "sm-cache");
        m.put("remote_config_directory", "/srv/sm/cache");
        m.put("container_config_path", "/app/external/spring/config");
        m.put("file_name", "application.yml");
        m.put("mechanism", "ADDITIONAL_LOCATION");
        m.put("retained_generations", 10);
        m.put("health_kind", "actuator-health");
        m.put("health_path", "/actuator/health");
        m.put("health_port", 18080);
        m.put("health_strength", "liveness");
        m.put("created_at", OffsetDateTime.now());
        return m;
    }

    private static UUID insertTarget(Map<String, Object> row) {
        insertRow("target_connection", TARGET_COLUMNS, row);
        return (UUID) row.get("target_id");
    }

    private static UUID insertPlacement(Map<String, Object> row) {
        insertRow("placement", PLACEMENT_COLUMNS, row);
        return (UUID) row.get("placement_id");
    }

    private static void insertRow(String table, String[] columns, Map<String, Object> row) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (String c : columns) {
            cols.append(cols.length() == 0 ? "" : ", ").append(c);
            vals.append(vals.length() == 0 ? "" : ", ").append(':').append(c);
            p.addValue(c, row.get(c));
        }
        runtime.update("INSERT INTO sm_data." + table + " (" + cols + ") VALUES (" + vals + ")", p);
    }

    private static Map<String, Object> instanceParamsMap(UUID tenant, UUID instance, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", tenant);
        m.put("i", instance);
        m.put("e", UUID.nameUUIDFromBytes(("env-" + tenant).getBytes()));
        m.put("c", UUID.randomUUID());
        m.put("u", UUID.randomUUID());
        m.put("n", name);
        m.put("mode", "via-ssh");
        m.put("now", OffsetDateTime.now());
        return m;
    }

    private static MapSqlParameterSource instanceParams(UUID tenant, UUID instance, String name) {
        return new MapSqlParameterSource(instanceParamsMap(tenant, instance, name));
    }

    private static void insertInstanceRow(Map<String, Object> m) {
        runtime.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id,"
                + " catalog_solution_id, solution, declared_package_version, name, lifecycle, acquisition_mode,"
                + " created_by, created_at) VALUES (:t, :i, :e, :c, 'cache', '2.4', :n,"
                + " 'pending', :mode, :u, :now)", new MapSqlParameterSource(m));
    }

    private static void insertInstance(UUID tenant, UUID instance, String name, String mode) {
        Map<String, Object> m = instanceParamsMap(tenant, instance, name);
        m.put("mode", mode);
        insertInstanceRow(m);
    }

    private static UUID declareViaSsh(UUID tenant, String name) {
        UUID i = UUID.randomUUID();
        insertInstance(tenant, i, name, "via-ssh");
        return i;
    }

    private static void setLifecycle(UUID tenant, UUID instance, String lifecycle) {
        runtime.update("UPDATE sm_data.instance_state SET lifecycle = :l WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource("l", lifecycle).addValue("t", tenant).addValue("i", instance));
    }

    private static void retire(UUID tenant, UUID placement) {
        runtime.update("UPDATE sm_data.placement SET retired_at = :now WHERE tenant_id = :t AND placement_id = :p",
                new MapSqlParameterSource("now", OffsetDateTime.now()).addValue("t", tenant).addValue("p", placement));
    }

    private static long stamp(UUID tenant, UUID instance) {
        return superuser.queryForObject(
                "SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t AND instance_id = :i",
                new MapSqlParameterSource("t", tenant).addValue("i", instance), Long.class);
    }

    private static long lastValue() {
        return superuser.queryForObject("SELECT last_value FROM sm_data.state_version_seq", Map.of(), Long.class);
    }

    private static int count(String sql, MapSqlParameterSource p) {
        return runtime.queryForObject(sql, p, Integer.class);
    }

    private static String uniqueFingerprint() {
        return "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private static List<String> runtimeUpdatableColumns(String table) {
        return columnsWithPrivilege(table, "UPDATE");
    }

    private static List<String> runtimeInsertableColumnsNamed(String table, String column) {
        return columnsWithPrivilege(table, "INSERT").stream().filter(column::equals).toList();
    }

    private static List<String> columnsWithPrivilege(String table, String privilege) {
        return superuser.queryForList(
                "SELECT column_name FROM information_schema.column_privileges"
                        + " WHERE table_schema = 'sm_data' AND table_name = :tbl"
                        + "   AND grantee = 'sm_dp_runtime' AND privilege_type = :priv ORDER BY column_name",
                new MapSqlParameterSource("tbl", table).addValue("priv", privilege), String.class);
    }

    /** One UPDATE on a connection the CALLER controls; returns the stamp the row took. */
    private static long stampOf(Connection c, UUID tenant, UUID instance, String lifecycle) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE sm_data.instance_state SET lifecycle = ?"
                + " WHERE tenant_id = ? AND instance_id = ? RETURNING state_version")) {
            ps.setString(1, lifecycle);
            ps.setObject(2, tenant);
            ps.setObject(3, instance);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private static int backendPid(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Polls until the backend is waiting on a heavyweight lock — i.e. it is queued behind the first writer. */
    private static void awaitBlockedOnALock(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = superuser.queryForObject(
                    "SELECT count(*)::int FROM pg_stat_activity WHERE pid = :pid AND wait_event_type = 'Lock'",
                    new MapSqlParameterSource("pid", pid), Integer.class);
            if (waiting != null && waiting == 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("backend " + pid + " never queued behind the first writer's row lock");
    }

    /** A Java placement identical to the legal defaults except for the one field under test. */
    private static SshDockerPlacement javaPlacement(String field, Object bad) {
        String host = "vm-1.corp.example";
        int port = 22;
        String user = "ec2-user";
        String fingerprint = "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=";
        String container = "sm-cache";
        String dir = "/srv/sm/cache";
        String cpath = "/app/external/spring/config";
        String file = "application.yml";
        switch (field) {
            case "host" -> host = (String) bad;
            case "port" -> port = (Integer) bad;
            case "ssh_user" -> user = (String) bad;
            case "host_key_fingerprint" -> fingerprint = (String) bad;
            case "container_name" -> container = (String) bad;
            case "remote_config_directory", "allowed_config_root" -> dir = (String) bad;
            case "container_config_path" -> cpath = (String) bad;
            case "file_name" -> file = (String) bad;
            default -> throw new IllegalArgumentException("no Java counterpart for " + field);
        }
        return new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), host, port, user, java.util.UUID.randomUUID(), fingerprint, container, dir, dir, cpath, file, ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
    }
}
