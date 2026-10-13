package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanService.OfferedHostKey;
import com.solace.sm.dp.api.inventory.HostKeyType;
import com.solace.sm.dp.api.inventory.RegistrationOutcome;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionEdit;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.apply.events.ApplyEventEmitter;
import com.solace.sm.dp.config.LadderRecorder;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.SshTestKeys;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * OR-125 (2), {@code sm_data} V027, against a real PostgreSQL 14 as {@code sm_dp_runtime}: the pinned host key's type is
 * stored beside the pin at registration, rewritten WITH the pin at a re-pin, back-filled by a replay and by a connector's
 * first success, never overwritten once recorded, and held by a CHECK to the types the data plane pins by.
 */
@Testcontainers(disabledWithoutDocker = true)
class HostKeyTypePersistenceIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final TargetChangeOrigin ORIGIN = new TargetChangeOrigin(UUID.randomUUID(), "198.51.100.7");

    private static NamedParameterJdbcTemplate superuser;
    private static JdbcTargetConnectionRepository connections;
    private static InventoryService service;
    private static volatile List<OfferedHostKey> offered = List.of();

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        superuser = SmDataPostgres.superuserJdbc();
        NamedParameterJdbcTemplate jdbc = SmDataPostgres.runtimeJdbc();
        var secrets = SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 33), null));
        connections = new JdbcTargetConnectionRepository(jdbc);
        HostKeyScanService scanner = (host, port) -> offered;
        service = new InventoryService(
                new TransactionTemplate(new DataSourceTransactionManager(SmDataPostgres.runtimeDataSource())),
                new JdbcInstanceStateRepository(jdbc), new JdbcPlacementRepository(jdbc), connections, secrets, secrets,
                Clock.systemUTC(), new JdbcMetricDefinitionRepository(jdbc), LadderRecorder.none(),
                ApplyEventEmitter.silent(), scanner);
    }

    private static String fingerprint() {
        return "SHA256:" + UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    }

    private static TargetConnectionSpec spec(String pin, HostKeyType type) {
        return new TargetConnectionSpec("vm-1.corp.example", 22, "ec2-user", pin, "/srv/sm", "sm-", Set.of(), type);
    }

    private static UUID register(UUID tenant, String pin, HostKeyType type) {
        UUID target = UUID.randomUUID();
        service.registerConnection(tenant, target, spec(pin, type),
                SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair()).chars(), ACTOR, ORIGIN);
        return target;
    }

    /** The stored column itself, as the database spells it. */
    private static String column(UUID tenant, UUID target) {
        return superuser.queryForObject("SELECT host_key_algorithm FROM sm_data.target_connection WHERE tenant_id = :t"
                + " AND target_id = :x", new MapSqlParameterSource("t", tenant).addValue("x", target), String.class);
    }

    @Test
    void aRegistrationStoresTheTypeOfThePinAsTheWireSpellsItAndNoTypeIsNull() {
        UUID tenant = SmDataPostgres.freshTenant();

        UUID typed = register(tenant, fingerprint(), HostKeyType.RSA);
        UUID untyped = register(tenant, fingerprint(), null);

        assertEquals("ssh-rsa", column(tenant, typed));
        assertNull(column(tenant, untyped), "NULL means the type is not known; the connector discovers it");
        assertEquals(HostKeyType.RSA, service.connectionOf(tenant, typed).orElseThrow().hostKeyType());
        assertNull(service.connectionOf(tenant, untyped).orElseThrow().hostKeyType());
    }

    @Test
    void everyTypeTheDataPlanePinsByRoundTripsThroughTheColumn() {
        UUID tenant = SmDataPostgres.freshTenant();
        for (HostKeyType type : HostKeyType.values()) {
            UUID target = register(tenant, fingerprint(), type);

            assertEquals(type.wire(), column(tenant, target));
            assertEquals(type, service.connectionOf(tenant, target).orElseThrow().hostKeyType());
        }
    }

    @Test
    void aRepinRewritesThePinAndItsTypeInOneStatement() {
        UUID tenant = SmDataPostgres.freshTenant();
        String first = fingerprint();
        String second = fingerprint();
        UUID target = register(tenant, first, HostKeyType.ED25519);
        offered = List.of(new OfferedHostKey(second, "ecdsa-sha2-nistp384"));

        service.editConnection(tenant, target, new TargetConnectionEdit(null, null, null, second), ACTOR, ORIGIN);

        assertEquals("ecdsa-sha2-nistp384", column(tenant, target));
        assertEquals(second, service.connectionOf(tenant, target).orElseThrow().hostKeyFingerprint());
    }

    @Test
    void aRepinToATypeTheDataPlaneDoesNotPinByLeavesNoStaleTypeBehind() {
        UUID tenant = SmDataPostgres.freshTenant();
        String second = fingerprint();
        UUID target = register(tenant, fingerprint(), HostKeyType.ED25519);
        offered = List.of(new OfferedHostKey(second, "unknown"));

        service.editConnection(tenant, target, new TargetConnectionEdit(null, null, null, second), ACTOR, ORIGIN);

        assertNull(column(tenant, target));
    }

    @Test
    void anSshUserOnlyEditKeepsTheRecordedType() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = register(tenant, fingerprint(), HostKeyType.RSA);

        service.editConnection(tenant, target, new TargetConnectionEdit(null, null, "deploy", null), ACTOR, ORIGIN);

        assertEquals("ssh-rsa", column(tenant, target));
    }

    @Test
    void aReplayBackFillsTheTypeOfARowRegisteredWithoutOneAndNeverOverwritesIt() {
        UUID tenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = UUID.randomUUID();
        var key = SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
        service.registerConnection(tenant, target, spec(pin, null), key.chars(), ACTOR, ORIGIN);
        assertNull(column(tenant, target));

        assertEquals(RegistrationOutcome.REPLAYED,
                service.registerConnection(tenant, target, spec(pin, HostKeyType.ED25519), key.chars(), ACTOR, ORIGIN));
        assertEquals("ssh-ed25519", column(tenant, target));

        service.registerConnection(tenant, target, spec(pin, HostKeyType.RSA), key.chars(), ACTOR, ORIGIN);
        assertEquals("ssh-ed25519", column(tenant, target), "a recorded type is never replaced by a later claim");
    }

    @Test
    void aConnectorsFirstSuccessRecordsTheTypeOnlyWhileNoneIsRecordedAndOnlyForThatPinInThatTenant() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID otherTenant = SmDataPostgres.freshTenant();
        String pin = fingerprint();
        UUID target = register(tenant, pin, null);
        UUID sameKeyElsewhere = register(otherTenant, pin, null);

        assertEquals(1, connections.recordHostKeyType(tenant, pin, HostKeyType.ECDSA_NISTP256));

        assertEquals("ecdsa-sha2-nistp256", column(tenant, target));
        assertNull(column(otherTenant, sameKeyElsewhere), "I5: another tenant's row for the same key is untouched");
        assertEquals(0, connections.recordHostKeyType(tenant, pin, HostKeyType.RSA), "never overwritten by a guess");
        assertEquals("ecdsa-sha2-nistp256", column(tenant, target));
        assertEquals(0, connections.recordHostKeyType(tenant, fingerprint(), HostKeyType.RSA), "another pin: no row");
    }

    @Test
    void aRetiredTargetsTypeIsNotRecordedAndARePinnedRowIsNotTouchedByTheOldPin() {
        UUID tenant = SmDataPostgres.freshTenant();
        String first = fingerprint();
        String second = fingerprint();
        UUID target = register(tenant, first, null);
        offered = List.of(new OfferedHostKey(second, "ssh-rsa"));
        service.editConnection(tenant, target, new TargetConnectionEdit(null, null, null, second), ACTOR, ORIGIN);
        assertEquals("ssh-rsa", column(tenant, target));

        // a connector that connected with the OLD pin just before the re-pin reports it late
        assertEquals(0, connections.recordHostKeyType(tenant, first, HostKeyType.ED25519));

        assertEquals("ssh-rsa", column(tenant, target));
    }

    @Test
    void theColumnHoldsOnlyTheTypesTheDataPlanePinsByAndTheRuntimeMayUpdateIt() {
        UUID tenant = SmDataPostgres.freshTenant();
        UUID target = register(tenant, fingerprint(), null);

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_host_key_algorithm", () -> superuser.update(
                "UPDATE sm_data.target_connection SET host_key_algorithm = 'ssh-dss' WHERE tenant_id = :t AND target_id = :x",
                new MapSqlParameterSource("t", tenant).addValue("x", target)));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "target_connection_host_key_algorithm", () -> superuser.update(
                "UPDATE sm_data.target_connection SET host_key_algorithm = 'unknown' WHERE tenant_id = :t AND target_id = :x",
                new MapSqlParameterSource("t", tenant).addValue("x", target)));
        assertEquals(1, connections.recordHostKeyType(tenant, service.connectionOf(tenant, target).orElseThrow()
                .hostKeyFingerprint(), HostKeyType.ED25519));
    }
}
