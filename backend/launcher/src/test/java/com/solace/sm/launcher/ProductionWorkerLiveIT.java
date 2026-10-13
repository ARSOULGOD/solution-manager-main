package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.apply.engine.AdmissionResult;
import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.model.ApplyCommand;
import com.solace.sm.dp.apply.model.ApplyId;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.Operation;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.RequestState;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.Strategy;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.persistence.ApplyRequestRepository;
import com.solace.sm.dp.inventory.LoopbackTargetAddressConfiguration;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T6.2, the live proof: {@code SolutionManagerApplication} composed exactly as production composes it, one
 * real PostgreSQL, NO test executor. A request is admitted through the production executor and landed by the worker's
 * own poll loop, through the table-backed placement catalog, the config-of-record renderer and the SSH Docker adapter.
 *
 * <p>The SSH host is a loopback port nothing listens on, so the chain is driven to its first remote act and the outcome
 * is the classified, provable-no-effect one. That is the strongest end that needs no host (a real host is T6.6 and
 * W9): it proves the worker no longer lands every request on an in-memory fake, that the placement named by the tables
 * is the one connected to, and that the render happens before the host is touched.
 */
// plan 013 T1.2: the target is a loopback stub, which the strict address policy refuses; the test wires the
// permissive one (a test-sources bean, never reachable in production)
@Import(LoopbackTargetAddressConfiguration.class)
@SpringBootTest(classes = SolutionManagerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductionWorkerLiveIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";

    private static TwoSchemaTestDatabase db;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        db = TwoSchemaTestDatabase.provision(registry);
        registry.add("sm.dp.apply.worker.poll-interval", () -> "200ms");
    }

    @Autowired
    private ApplyExecutor executor;

    @Autowired
    private ApplyRequestRepository requests;

    @Autowired
    private ConfigRecordService configRecords;

    @Autowired
    private SecretStore secrets;

    /** A declared instance with one live placement on a target whose SSH port nothing listens on. */
    private final class Seed {
        final UUID tenant = UUID.randomUUID();
        final UUID instance = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        final UUID placement = UUID.randomUUID();

        Seed() throws IOException {
            DeclaredInstances.declare(db, tenant, instance);
            int closedPort;
            try (ServerSocket probe = new ServerSocket(0)) {
                closedPort = probe.getLocalPort();
            }
            UUID key = secrets.put(tenant, SecretPurpose.SSH_PRIVATE_KEY, "not-a-real-key".toCharArray(), ACTOR);
            db.ownerJdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port,"
                            + " ssh_user, host_key_fingerprint, credential_ref, allowed_config_root,"
                            + " allowed_container_prefix, created_by, created_at) VALUES (:t, :x, 'ssh-docker',"
                            + " '127.0.0.1', :port, 'ec2-user', :fp, :cred, '/srv/sm', 'sm-', :u, :now)",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("x", target)
                            .addValue("port", closedPort)
                            .addValue("fp", "SHA256:" + "A".repeat(43)).addValue("cred", key)
                            .addValue("u", ACTOR).addValue("now", OffsetDateTime.now()));
            insertPlacement(placement);
        }

        /** Registers a placement of this instance on the Seed's target (V024: admission names only registered ones). */
        void insertPlacement(UUID placementId) {
            db.ownerJdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id,"
                            + " container_name, remote_config_directory, container_config_path, file_name, mechanism,"
                            + " retained_generations, created_at) VALUES (:t, :p, :i, :x, :c, :d,"
                            + " '/app/external/spring/config', 'application.yml', 'ADDITIONAL_LOCATION', 10, :now)",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("p", placementId)
                            .addValue("i", instance).addValue("x", target).addValue("c", "sm-" + placementId)
                            .addValue("d", "/srv/sm/" + placementId).addValue("now", OffsetDateTime.now()));
        }

        /**
         * A placement the registry holds but has retired: it passes the V024 referential guard (existence, not
         * liveness) and is refused by the catalog at execution as {@code placement.unresolvable}.
         */
        UUID retiredPlacement() {
            UUID retired = UUID.randomUUID();
            insertPlacement(retired);
            db.ownerJdbc.update("UPDATE sm_data.placement SET retired_at = :now"
                            + " WHERE tenant_id = :t AND placement_id = :p",
                    new MapSqlParameterSource().addValue("now", OffsetDateTime.now()).addValue("t", tenant)
                            .addValue("p", retired));
            return retired;
        }

        long stage(UUID secretRef) {
            long base = db.ownerJdbc.queryForObject("SELECT state_version FROM sm_data.instance_state"
                            + " WHERE tenant_id = :t AND instance_id = :i",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance), Long.class);
            return configRecords.stage(tenant, instance, new StageCommand(base, "cache", "2.4", SELECTION,
                    "{\"cache-1\":{\"n\":1}}",
                    List.of(RenderedEntryValue.plain("cache-1", "cache-engine.mode", "\"READ\""),
                            RenderedEntryValue.secret("cache-1", "cache-engine.solace.password", secretRef)),
                    GenerationOrigin.EDIT, null, ACTOR)).generation().generation();
        }

        UUID configSecret() {
            return secrets.put(tenant, SecretPurpose.CONFIG_FIELD, "broker-password".toCharArray(), ACTOR);
        }

        /** Admits a manual apply of {@code generation} at {@code placementId} and waits for the worker to land it. */
        Landed apply(UUID placementId, long generation) throws InterruptedException {
            ScopeKey scope = new ScopeKey(new TenantId(tenant), new InstanceId(instance), new PlacementId(placementId));
            AdmissionResult result = executor.admit(
                    new ApplyCommand(scope, Operation.APPLY, generation, Strategy.MANUAL, null, "operator-1"));
            ApplyId id = ((AdmissionResult.Admitted) result).id();
            assertEquals(RequestState.REQUESTED, requests.find(new TenantId(tenant), id).orElseThrow().state());
            RequestState state = awaitTerminal(id, Duration.ofSeconds(20));
            List<String> classes = db.ownerJdbc.queryForList("SELECT error_class FROM sm_data.apply_attempt"
                            + " WHERE tenant_id = :t AND apply_id = :a AND error_class IS NOT NULL",
                    new MapSqlParameterSource().addValue("t", tenant).addValue("a", id.value()), String.class);
            return new Landed(state, classes);
        }

        private RequestState awaitTerminal(ApplyId id, Duration timeout) throws InterruptedException {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                RequestState state = requests.find(new TenantId(tenant), id).orElseThrow().state();
                if (state.isTerminal()) {
                    return state;
                }
                Thread.sleep(100);
            }
            return fail("request " + id.value() + " did not reach a terminal state within " + timeout);
        }
    }

    private record Landed(RequestState state, List<String> errorClasses) {
    }

    @Test
    void theWorkerLandsARequestThroughTheCatalogRendererAndSshAdapterAtAHostNothingListensOn() throws Exception {
        Seed s = new Seed();
        long generation = s.stage(s.configSecret());

        Landed landed = s.apply(s.placement, generation);

        assertEquals(RequestState.FAILED_NO_EFFECT, landed.state(), "nothing was written, and the engine can prove it");
        assertEquals(List.of("ssh.connection.failed"), landed.errorClasses(),
                "the render succeeded and the adapter reached the connect: the SSH transport, not a fake, answered");
    }

    @Test
    void aGenerationNamingAMissingSecretLandsRenderFailedBeforeTheHostIsTouched() throws Exception {
        Seed s = new Seed();
        long generation = s.stage(UUID.randomUUID());

        Landed landed = s.apply(s.placement, generation);

        assertEquals(RequestState.FAILED_NO_EFFECT, landed.state());
        assertEquals(List.of("render.failed"), landed.errorClasses(),
                "the engine classifies the render failure itself; no message of the renderer is read");
    }

    @Test
    void aPlacementTheCatalogDoesNotResolveIsRefusedBeforeAnyConnection() throws Exception {
        // After V024 (BE-D48) an UNKNOWN placement is refused at admission by the FK, so the catalog's own refusal
        // is exercised with a RETIRED one: it passes the FK (existence only) and is refused at execution.
        Seed s = new Seed();
        long generation = s.stage(s.configSecret());

        Landed landed = s.apply(s.retiredPlacement(), generation);

        assertEquals(RequestState.FAILED_NO_EFFECT, landed.state());
        assertTrue(landed.errorClasses().equals(List.of("placement.unresolvable")), landed.errorClasses().toString());
    }
}
