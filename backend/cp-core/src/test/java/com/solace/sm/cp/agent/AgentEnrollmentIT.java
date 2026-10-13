package com.solace.sm.cp.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solace.sm.cp.SmControlPostgres;
import com.solace.sm.cp.api.agent.AgentEnrolment;
import com.solace.sm.cp.api.agent.AgentEnrollmentMaterial;
import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException;
import com.solace.sm.cp.api.agent.AgentEnrollmentRefusedException.Reason;
import com.solace.sm.cp.api.agent.AgentEnrollmentRequest;
import com.solace.sm.cp.api.agent.AgentLeaseRefusedException;
import com.solace.sm.cp.api.agent.AgentLeaseRequest;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.target.TargetService;
import com.solace.sm.cp.config.AgentGatewayCaProperties;
import com.solace.sm.cp.persistence.JdbcAgentAuditContext;
import com.solace.sm.cp.persistence.JdbcAgentRegistry;
import com.solace.sm.cp.persistence.JdbcAuditContext;
import com.solace.sm.cp.persistence.JdbcResourceScopeDirectory;
import com.solace.sm.cp.persistence.JdbcScopeStore;
import com.solace.sm.cp.persistence.JdbcTargetRepository;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 015 T2.2 against a real PostgreSQL 14 migrated to the head, with everything application-side running as
 * {@code sm_cp_runtime} (the role {@code OR-25} fails closed for): token issue, enrolment, replay, the lease, and the audit
 * rows each leaves. The seeding is done as the migrator, which {@code OR-25} does not apply to.
 */
class AgentEnrollmentIT {

    private static final String KEY = "agent-enrollment-it";

    @TempDir
    Path directory;

    private final NamedParameterJdbcTemplate migrator = SmControlPostgres.jdbcAtVersion(KEY, SmControlPostgres.LATEST_VERSION);
    private final NamedParameterJdbcTemplate runtime = SmControlPostgres.runtimeJdbcAtVersion(KEY);
    private final TransactionTemplate tx =
            new TransactionTemplate(new DataSourceTransactionManager(runtime.getJdbcTemplate().getDataSource()));

    private record Tree(UUID organization, UUID unit, UUID environment, UUID principal, UUID solution) { }

    private Tree tree() {
        UUID org = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID env = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        UUID solution = UUID.randomUUID();
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization (organization_id, name) VALUES (?, ?)", org, "Org-" + org);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name)"
                + " VALUES (?, ?, 'u-' || gen_random_uuid()::text)", org, unit);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.environment (organization_id, organization_unit_id,"
                + " environment_id, name) VALUES (?, ?, ?, 'e-' || gen_random_uuid()::text)", org, unit, env);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.principal (id, organization_id, source, username)"
                + " VALUES (?, ?, 'idp', ?)", principal, org, "agent-it-" + principal);
        // Deliberately NO sm_control.scope row for the environment: scope rows are minted lazily (V018), and token issue is where
        // the environment's is minted, because the agent actor cannot (V021). A seed here would hide that.
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.catalog_solution (organization_id, catalog_solution_id,"
                + " solution, created_at) VALUES (?, ?, 'cache', now())", org, solution);
        migrator.getJdbcTemplate().update("INSERT INTO sm_control.solution_package (organization_id, catalog_solution_id,"
                + " package_version, applies_to, digest, signature_status, document, loaded_by, loaded_at)"
                + " VALUES (?, ?, '[1]', '>=1', ?, 'unsigned', '{}'::jsonb, ?, now())", org, solution,
                "sha256:" + "0".repeat(64), principal);
        return new Tree(org, unit, env, principal, solution);
    }

    private final JdbcAgentRegistry registry = new JdbcAgentRegistry(runtime);
    private final JdbcTargetRepository targets = new JdbcTargetRepository(runtime);

    private AgentEnrolment enrolment(Clock clock) {
        return new AgentEnrolmentService(registry, targets, new JdbcResourceScopeDirectory(runtime), new JdbcScopeStore(runtime),
                (principal, permission, resource) -> { },
                new JdbcAuditContext(runtime), tx, clock, new SecureRandom());
    }

    private ControlPlaneAgentGatewayService gateway(Clock clock, AgentGatewayCaProperties ca) {
        return new ControlPlaneAgentGatewayService(registry, new Pkcs12AgentCertificateAuthority(ca), new Pkcs12LeaseNonceSigner(ca), targets,
                new JdbcAgentAuditContext(runtime), tx, clock, "agents.example.test:8443");
    }

    private RequestOrigin issueOrigin;

    private AgentEnrolment.IssuedToken issue(Tree t, String name, Clock clock) {
        issueOrigin = new RequestOrigin("203.0.113.40", UUID.randomUUID());
        return enrolment(clock).issue(new PrincipalRef(t.principal), t.organization, new AgentEnrolment.Request(
                ScopePath.environment(t.organization, t.unit, t.environment), t.solution, "1", name, "/opt/sm/config",
                "sm-", null), issueOrigin);
    }

    private static AgentEnrollmentRequest enrol(String token, byte[] csr, RequestOrigin origin) {
        return new AgentEnrollmentRequest(token, csr, "0.1.0", List.of(1), "linux", "arm64", "docker", origin);
    }

    private static String thumbprint(AgentEnrollmentMaterial material) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(material.certificateChainDer().get(0)));
    }

    private List<Map<String, Object>> audit(String table, UUID correlation) {
        return migrator.queryForList("SELECT audit_operation, operation, actor_principal_id, actor_agent_id, client_address,"
                + " row_key::text AS row_key, new_values::text AS new_values FROM sm_control.change_audit"
                + " WHERE table_name = :t AND correlation_id = :c", Map.of("t", table, "c", correlation));
    }

    private long auditCount() {
        return migrator.getJdbcTemplate().queryForObject("SELECT count(*) FROM sm_control.change_audit", Long.class);
    }

    @Test
    void issueEnrolReplayAndLeaseThroughTheRealSchemaAsTheRuntimeRole() throws Exception {
        Tree t = tree();
        Clock clock = Clock.systemUTC();
        AgentGatewayCaProperties ca = AgentTestPki.properties(directory);
        AgentEnrolment.IssuedToken issued = issue(t, "edge-host-1", clock);

        // Token issue: the principal is the actor of an AGENT_ENROLMENT_ISSUED row and the registration's creator.
        List<Map<String, Object>> issueRows = audit("agent_registration", issueOrigin.correlationId());
        assertThat(issueRows).hasSize(1);
        assertThat(issueRows.get(0).get("audit_operation")).isEqualTo("AGENT_ENROLMENT_ISSUED");
        assertThat(issueRows.get(0).get("actor_principal_id")).isEqualTo(t.principal);
        assertThat(issueRows.get(0).get("actor_agent_id")).isNull();
        // I6: no stored byte of the registration (or its audit row) is the token.
        assertThat(migrator.queryForObject("SELECT t::text FROM sm_control.agent_registration t WHERE agent_id = :a",
                Map.of("a", issued.agentId()), String.class)).doesNotContain(issued.token());
        assertThat(issueRows.get(0).get("new_values").toString()).doesNotContain(issued.token());

        // Enrolment: every CP write at consumption is the AGENT's, with the gateway-minted origin.
        RequestOrigin origin = new RequestOrigin("198.51.100.77", UUID.randomUUID());
        byte[] csr = AgentTestPki.csr();
        AgentEnrollmentMaterial material = gateway(clock, ca).enroll(enrol(issued.token(), csr, origin));

        assertThat(material.agentId()).isEqualTo(issued.agentId());
        assertThat(material.issuingPrincipal()).isEqualTo(t.principal);
        assertThat(material.environmentId()).isEqualTo(t.environment);
        List<Map<String, Object>> registrationRows = audit("agent_registration", origin.correlationId());
        List<Map<String, Object>> targetRows = audit("target", origin.correlationId());
        assertThat(registrationRows).hasSize(1);
        assertThat(targetRows).hasSize(1);
        for (Map<String, Object> row : List.of(registrationRows.get(0), targetRows.get(0))) {
            assertThat(row.get("audit_operation")).isEqualTo("AGENT_ENROLLED");
            assertThat(row.get("actor_agent_id")).isEqualTo(issued.agentId());
            assertThat(row.get("actor_principal_id")).isNull();
            assertThat(row.get("client_address")).isEqualTo("198.51.100.77");
        }
        Map<String, Object> target = migrator.queryForMap("SELECT name, kind, transport, created_by, retired_at"
                + " FROM sm_control.target WHERE target_id = :t", Map.of("t", material.targetId()));
        assertThat(target.get("name")).isEqualTo("edge-host-1");
        assertThat(target.get("kind")).isEqualTo("docker");
        assertThat(target.get("transport")).isEqualTo("agent");
        assertThat(target.get("created_by")).isEqualTo(t.principal);
        Map<String, Object> row = migrator.queryForMap("SELECT target_id, enrollment_used_at, certificate_chain_der IS NOT NULL AS has_chain"
                + " FROM sm_control.agent_registration WHERE agent_id = :a", Map.of("a", issued.agentId()));
        assertThat(row.get("target_id")).isEqualTo(material.targetId());
        assertThat(row.get("enrollment_used_at")).isNotNull();
        assertThat(row.get("has_chain")).isEqualTo(true);

        // Replay: the same token and CSR answer the same certificate and target and write nothing.
        long before = auditCount();
        AgentEnrollmentMaterial replay = gateway(clock, ca).enroll(enrol(issued.token(), csr,
                new RequestOrigin("198.51.100.78", UUID.randomUUID())));
        assertThat(auditCount()).isEqualTo(before);
        assertThat(replay.targetId()).isEqualTo(material.targetId());
        assertThat(replay.certificateChainDer().get(0)).isEqualTo(material.certificateChainDer().get(0));
        assertThat(replay.validTo()).isEqualTo(material.validTo());

        // A different CSR on the consumed token is refused.
        assertThatThrownBy(() -> gateway(clock, ca).enroll(enrol(issued.token(), AgentTestPki.csr(),
                new RequestOrigin("198.51.100.79", UUID.randomUUID()))))
                .isInstanceOfSatisfying(AgentEnrollmentRefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.CONSUMED));

        // The lease writes no row (Step 0 (b)): no change_audit row of any kind, so AGENT_LEASE_ISSUED is not needed.
        long beforeLease = auditCount();
        List<UUID> placements = List.of(UUID.randomUUID());
        var lease = gateway(clock, ca).issueLease(new AgentLeaseRequest(t.organization, issued.agentId(), material.targetId(),
                thumbprint(material), 1, 1, Instant.now(), "lease-1", null, placements));
        assertThat(lease.placementIds()).isEqualTo(placements);
        assertThat(lease.verbCeilings()).containsEntry("REMATERIALIZE", Duration.ofSeconds(60));
        assertThat(auditCount()).isEqualTo(beforeLease);

        // I5: the registration read is bound by organization. The same agent asked for under another organization is unknown.
        assertThatThrownBy(() -> gateway(clock, ca).issueLease(new AgentLeaseRequest(UUID.randomUUID(), issued.agentId(),
                material.targetId(), thumbprint(material), 1, 1, Instant.now(), "lease-x", null, List.of())))
                .isInstanceOfSatisfying(AgentLeaseRefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(AgentLeaseRefusedException.Reason.NOT_ENROLLED));
        // And the lease is bound to the issued certificate.
        assertThatThrownBy(() -> gateway(clock, ca).issueLease(new AgentLeaseRequest(t.organization, issued.agentId(),
                material.targetId(), "0".repeat(64), 1, 1, Instant.now(), "lease-y", null, List.of())))
                .isInstanceOfSatisfying(AgentLeaseRefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(AgentLeaseRefusedException.Reason.CERTIFICATE_MISMATCH));

        // The DP's view: the registration, active, with its target; a revoked one is reported inactive.
        assertThat(gateway(clock, ca).registrations(List.of(issued.agentId())).get(issued.agentId()).targetId())
                .isEqualTo(material.targetId());
    }

    @Test
    void aRevokedRegistrationIsReportedInactiveAndRefusesLeasesAndEnrolment() throws Exception {
        Tree t = tree();
        Clock clock = Clock.systemUTC();
        AgentGatewayCaProperties ca = AgentTestPki.properties(directory);
        AgentEnrolment.IssuedToken issued = issue(t, "edge-revoked", clock);
        AgentEnrollmentMaterial material = gateway(clock, ca).enroll(enrol(issued.token(), AgentTestPki.csr(),
                new RequestOrigin("198.51.100.80", UUID.randomUUID())));
        migrator.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET status = 'revoked', revoked_at = now(),"
                + " agent_revocation_epoch = 2 WHERE agent_id = ?", issued.agentId());

        assertThat(gateway(clock, ca).registrations(List.of(issued.agentId())).get(issued.agentId()).active()).isFalse();
        assertThatThrownBy(() -> gateway(clock, ca).issueLease(new AgentLeaseRequest(t.organization, issued.agentId(),
                material.targetId(), thumbprint(material), 2, 1, Instant.now(), "lease-2", null, List.of())))
                .isInstanceOfSatisfying(AgentLeaseRefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(AgentLeaseRefusedException.Reason.REVOKED));
        assertThatThrownBy(() -> gateway(clock, ca).enroll(enrol(issued.token(), AgentTestPki.csr(),
                new RequestOrigin("198.51.100.81", UUID.randomUUID()))))
                .isInstanceOfSatisfying(AgentEnrollmentRefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.UNKNOWN_TOKEN));
    }

    @Test
    void anExpiredTokenIsRefusedAndWritesNothing() throws Exception {
        Tree t = tree();
        AgentGatewayCaProperties ca = AgentTestPki.properties(directory);
        AgentEnrolment.IssuedToken issued = issue(t, "edge-expired", Clock.systemUTC());
        Clock later = Clock.fixed(issued.expiresAt().plusSeconds(1), java.time.ZoneOffset.UTC);
        long before = auditCount();

        assertThatThrownBy(() -> gateway(later, ca).enroll(enrol(issued.token(), AgentTestPki.csr(),
                new RequestOrigin("198.51.100.82", UUID.randomUUID()))))
                .isInstanceOfSatisfying(AgentEnrollmentRefusedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.EXPIRED));

        assertThat(auditCount()).isEqualTo(before);
        assertThat(migrator.queryForObject("SELECT enrollment_used_at IS NULL FROM sm_control.agent_registration"
                + " WHERE agent_id = :a", Map.of("a", issued.agentId()), Boolean.class)).isTrue();
    }

    @Test
    void withoutAKeystoreNothingIsConsumedAndNoTargetOrAuditRowExists() {
        Tree t = tree();
        AgentEnrolment.IssuedToken issued = issue(t, "edge-no-ca", Clock.systemUTC());
        AgentGatewayCaProperties none = new AgentGatewayCaProperties();
        none.setConnectEndpoint("agents.example.test:8443");
        long before = auditCount();
        RequestOrigin origin = new RequestOrigin("198.51.100.83", UUID.randomUUID());

        assertThatThrownBy(() -> gateway(Clock.systemUTC(), none).enroll(enrol(issued.token(), AgentTestPki.csr(), origin)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditCount()).isEqualTo(before);
        assertThat(migrator.queryForObject("SELECT enrollment_used_at IS NULL AND target_id IS NULL FROM"
                + " sm_control.agent_registration WHERE agent_id = :a", Map.of("a", issued.agentId()), Boolean.class)).isTrue();
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.target WHERE organization_id = :o",
                Map.of("o", t.organization), Long.class)).isZero();
    }

    @Test
    void aLiveTargetOfTheSameNameBlocksTheIssueAndAPendingTokenBlocksASecond() {
        Tree t = tree();
        issue(t, "edge-dupe", Clock.systemUTC());

        assertThatThrownBy(() -> issue(t, "edge-dupe", Clock.systemUTC())).isInstanceOf(IllegalStateException.class);
    }

    /**
     * Plan 015 T3.5 (T2.2 review 7): {@code registerTarget} cannot take a name an unconsumed token holds, or the agent would be
     * stranded at enrolment with the name taken. In the SAME path as the real registration, against the real index.
     */
    @Test
    void registerTargetRefusesANameAPendingEnrolmentHoldsAndAcceptsItOnceTheTokenIsRevoked() {
        Tree t = tree();
        AgentEnrolment.IssuedToken issued = issue(t, "edge-held", Clock.systemUTC());
        TargetService targetService = new TargetService(targets, (principal, permission, resource) -> { },
                new JdbcAuditContext(runtime), tx, Clock.systemUTC());
        TargetRegistration.Request request = new TargetRegistration.Request(null, "edge-held", "docker", "ssh-docker",
                ScopePath.environment(t.organization, t.unit, t.environment));
        long targetsBefore = migrator.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM sm_control.target WHERE organization_id = ?", Long.class, t.organization);

        assertThatThrownBy(() -> targetService.register(new PrincipalRef(t.principal), t.organization, request,
                new RequestOrigin("203.0.113.41", UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pending enrolment");
        assertThat(migrator.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM sm_control.target WHERE organization_id = ?", Long.class, t.organization))
                .as("nothing was written").isEqualTo(targetsBefore);
        assertThat(targets.nameHeldByPendingEnrolment(t.organization, "edge-held")).isTrue();
        assertThat(targets.nameHeldByPendingEnrolment(UUID.randomUUID(), "edge-held")).as("scoped by organization (I5)").isFalse();
        assertThat(targets.nameHeldByPendingEnrolment(t.organization, "another-name")).isFalse();

        // revoking the unused registration releases the name (the index no longer holds it)
        migrator.getJdbcTemplate().update("UPDATE sm_control.agent_registration SET status = 'revoked', revoked_at = now()"
                + " WHERE agent_id = ?", issued.agentId());
        assertThat(targets.nameHeldByPendingEnrolment(t.organization, "edge-held")).isFalse();
        assertThat(targetService.register(new PrincipalRef(t.principal), t.organization, request,
                new RequestOrigin("203.0.113.42", UUID.randomUUID())).name()).isEqualTo("edge-held");
    }

    /**
     * Plan 015 T3.5 fix round: token issue and registerTarget each CHECK one table for the name and WRITE the other, so without a
     * shared lock both can commit and the agent is stranded at enrolment. With the advisory lock exactly one of the two wins,
     * whichever starts first.
     */
    @Test
    void aTokenAndARegistrationRacingForOneNameLeaveExactlyOneHolder() throws Exception {
        Tree t = tree();
        TargetService targetService = new TargetService(targets, (principal, permission, resource) -> { },
                new JdbcAuditContext(runtime), tx, Clock.systemUTC());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 15; round++) {
                String name = "race-" + round;
                java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
                java.util.concurrent.Future<Boolean> issuing = pool.submit(() -> {
                    barrier.await();
                    try {
                        issue(t, name, Clock.systemUTC());
                        return true;
                    } catch (IllegalStateException refused) {
                        return false;
                    }
                });
                java.util.concurrent.Future<Boolean> registering = pool.submit(() -> {
                    barrier.await();
                    try {
                        targetService.register(new PrincipalRef(t.principal), t.organization,
                                new TargetRegistration.Request(null, name, "docker", "ssh-docker",
                                        ScopePath.environment(t.organization, t.unit, t.environment)),
                                new RequestOrigin("203.0.113.50", UUID.randomUUID()));
                        return true;
                    } catch (IllegalStateException refused) {
                        return false;
                    }
                });
                assertThat(issuing.get(30, java.util.concurrent.TimeUnit.SECONDS) ^ registering.get(30, java.util.concurrent.TimeUnit.SECONDS))
                        .as("round " + round + ": exactly one of the token and the registration holds the name").isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The deterministic form of the race test above (which a missing lock passes about as often as not): the lock is really taken. */
    @Test
    void registerTargetWaitsForANameLockAnotherTransactionHolds() throws Exception {
        Tree t = tree();
        TargetService targetService = new TargetService(targets, (principal, permission, resource) -> { },
                new JdbcAuditContext(runtime), tx, Clock.systemUTC());
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                targets.lockName(t.organization, "locked-name");
                locked.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<TargetView> registering = pool.submit(() -> targetService.register(
                    new PrincipalRef(t.principal), t.organization, new TargetRegistration.Request(null, "locked-name", "docker",
                            "ssh-docker", ScopePath.environment(t.organization, t.unit, t.environment)),
                    new RequestOrigin("203.0.113.51", UUID.randomUUID())));
            Thread.sleep(700);
            assertThat(registering.isDone()).as("registration waits for the holder of the name lock").isFalse();
            release.countDown();
            holder.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(registering.get(30, java.util.concurrent.TimeUnit.SECONDS).name()).isEqualTo("locked-name");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aSolutionOrVersionTheOrganizationHasNotLoadedIsRefusedAtIssue() {
        Tree t = tree();
        assertThatThrownBy(() -> enrolment(Clock.systemUTC()).issue(new PrincipalRef(t.principal), t.organization,
                new AgentEnrolment.Request(ScopePath.environment(t.organization, t.unit, t.environment), t.solution, "9",
                        "edge-bad-version", "/opt/sm/config", "sm-", null), new RequestOrigin("203.0.113.40", UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enrolment(Clock.systemUTC()).issue(new PrincipalRef(t.principal), t.organization,
                new AgentEnrolment.Request(ScopePath.environment(t.organization, t.unit, t.environment), UUID.randomUUID(),
                        "1", "edge-bad-solution", "/opt/sm/config", "sm-", null), new RequestOrigin("203.0.113.40", UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enrolmentIntoAFreshEnvironmentWithNoPriorScopeRowMintsItAtIssueAndSucceeds() throws Exception {
        Tree t = tree();
        Clock clock = Clock.systemUTC();
        AgentGatewayCaProperties ca = AgentTestPki.properties(directory);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.scope WHERE environment_id = :e AND kind = 'environment'",
                Map.of("e", t.environment), Long.class)).as("precondition: no scope row").isZero();

        AgentEnrolment.IssuedToken issued = issue(t, "edge-fresh", clock);

        // Minted by the issuing PRINCIPAL, in the audited issue transaction (not by the agent, whom V021 forbids it).
        List<Map<String, Object>> scopeRows = audit("scope", issueOrigin.correlationId());
        assertThat(scopeRows).hasSize(1);
        assertThat(scopeRows.get(0).get("audit_operation")).isEqualTo("AGENT_ENROLMENT_ISSUED");
        assertThat(scopeRows.get(0).get("actor_principal_id")).isEqualTo(t.principal);

        RequestOrigin origin = new RequestOrigin("198.51.100.90", UUID.randomUUID());
        AgentEnrollmentMaterial material = gateway(clock, ca).enroll(enrol(issued.token(), AgentTestPki.csr(), origin));

        assertThat(material.targetId()).isNotNull();
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.scope WHERE environment_id = :e AND kind = 'environment'",
                Map.of("e", t.environment), Long.class)).as("one canonical row, reused by the target").isEqualTo(1L);
        assertThat(audit("target", origin.correlationId())).hasSize(1);
    }

    @Test
    void twoCsrsRacingOnOneTokenLeaveExactlyOneConsumptionAndOneTarget() throws Exception {
        Tree t = tree();
        Clock clock = Clock.systemUTC();
        AgentGatewayCaProperties ca = AgentTestPki.properties(directory);
        AgentEnrolment.IssuedToken issued = issue(t, "edge-race", clock);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<Object>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                byte[] csr = AgentTestPki.csr();
                RequestOrigin origin = new RequestOrigin("198.51.100." + (100 + i), UUID.randomUUID());
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        return gateway(clock, ca).enroll(enrol(issued.token(), csr, origin));
                    } catch (AgentEnrollmentRefusedException refused) {
                        return refused;
                    }
                }));
            }
            go.countDown();
            long enrolled = 0;
            long consumed = 0;
            for (var future : results) {
                Object outcome = future.get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (outcome instanceof AgentEnrollmentMaterial) enrolled++;
                else if (outcome instanceof AgentEnrollmentRefusedException e && e.reason() == Reason.CONSUMED) consumed++;
            }
            assertThat(enrolled).isEqualTo(1);
            assertThat(consumed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.target WHERE organization_id = :o AND name = 'edge-race'",
                Map.of("o", t.organization), Long.class)).isEqualTo(1L);
        assertThat(migrator.queryForObject("SELECT count(*) FROM sm_control.change_audit WHERE table_name = 'agent_registration'"
                + " AND audit_operation = 'AGENT_ENROLLED' AND actor_agent_id = :a", Map.of("a", issued.agentId()), Long.class))
                .as("one consumption").isEqualTo(1L);
    }

    @Test
    void aCsrThatAsksForExtensionsGetsNoneOfThemFromTheRealPipeline() throws Exception {
        Tree t = tree();
        Clock clock = Clock.systemUTC();
        AgentGatewayCaProperties ca = AgentTestPki.properties(directory);
        AgentEnrolment.IssuedToken issued = issue(t, "edge-ext", clock);

        AgentEnrollmentMaterial material = gateway(clock, ca).enroll(enrol(issued.token(), AgentTestPki.csrAskingForExtensions(),
                new RequestOrigin("198.51.100.110", UUID.randomUUID())));

        java.security.cert.X509Certificate leaf = AgentTestPki.parse(material.certificateChainDer().get(0));
        assertThat(leaf.getSubjectAlternativeNames()).isNull();
        assertThat(leaf.getBasicConstraints()).isEqualTo(-1);
    }

    @Test
    void theOperatorReadsAndTheRevokeAreBoundByOrganizationAndRevokeRaisesTheEpochOnceThroughTheRuntimeRole() {
        Tree one = tree();
        Tree two = tree();
        Clock clock = Clock.systemUTC();
        AgentEnrolment.IssuedToken issued = issue(one, "edge-admin-read", clock);
        AgentAdministrationService administration = new AgentAdministrationService(registry,
                new JdbcResourceScopeDirectory(runtime), (principal, permission, resource) -> { }, new JdbcAuditContext(runtime), tx, clock);

        // plan 015 T4.3 (I5): the registry's own reads are bound by organization, before any authorization above them
        assertThat(registry.list(two.organization)).isEmpty();
        assertThat(registry.detail(two.organization, issued.agentId())).isEmpty();
        assertThat(registry.list(one.organization)).hasSize(1);
        AgentRegistry.Detail detail = registry.detail(one.organization, issued.agentId()).orElseThrow();
        assertThat(detail.status()).isEqualTo("active");
        assertThat(detail.enrolledAt()).isNull();
        assertThat(detail.environmentId()).isEqualTo(one.environment);
        assertThat(detail.packageVersion()).isEqualTo("1");
        assertThat(administration.find(one.organization, issued.agentId()).orElseThrow().status())
                .isEqualTo(com.solace.sm.cp.api.agent.AgentAdministration.Status.PENDING);
        assertThat(administration.environmentPath(two.organization, one.environment)).isEmpty();
        assertThat(administration.environmentPath(one.organization, one.environment))
                .contains(ScopePath.environment(one.organization, one.unit, one.environment));

        // another organization's revoke finds nothing and writes nothing
        RequestOrigin origin = new RequestOrigin("203.0.113.41", UUID.randomUUID());
        assertThat(administration.revoke(new PrincipalRef(two.principal), two.organization, issued.agentId(), origin)).isEmpty();
        assertThat(audit("agent_registration", origin.correlationId())).isEmpty();

        long epoch = migrator.queryForObject("SELECT agent_revocation_epoch FROM sm_control.agent_registration WHERE agent_id = :a",
                Map.of("a", issued.agentId()), Long.class);
        var revoked = administration.revoke(new PrincipalRef(one.principal), one.organization, issued.agentId(), origin).orElseThrow();
        assertThat(revoked.status()).isEqualTo(com.solace.sm.cp.api.agent.AgentAdministration.Status.REVOKED);
        assertThat(migrator.queryForObject("SELECT agent_revocation_epoch FROM sm_control.agent_registration WHERE agent_id = :a",
                Map.of("a", issued.agentId()), Long.class)).isEqualTo(epoch + 1);
        List<Map<String, Object>> rows = audit("agent_registration", origin.correlationId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("audit_operation")).isEqualTo("AGENT_REVOKED");
        assertThat(rows.get(0).get("actor_principal_id")).isEqualTo(one.principal);

        // idempotent: a second revoke changes and audits nothing
        RequestOrigin again = new RequestOrigin("203.0.113.41", UUID.randomUUID());
        administration.revoke(new PrincipalRef(one.principal), one.organization, issued.agentId(), again).orElseThrow();
        assertThat(audit("agent_registration", again.correlationId())).isEmpty();
        assertThat(migrator.queryForObject("SELECT agent_revocation_epoch FROM sm_control.agent_registration WHERE agent_id = :a",
                Map.of("a", issued.agentId()), Long.class)).isEqualTo(epoch + 1);
    }
}
