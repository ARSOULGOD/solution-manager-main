package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.api.inventory.HostKeyScanService;
import com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException;
import com.solace.sm.dp.api.inventory.RegistrationOutcome;
import com.solace.sm.dp.api.inventory.RegistrationPrecheck;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionAdministration;
import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.inventory.InventoryService;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 013 T1.4: {@code scanHostKeys}, {@code registerTarget} and {@code getTargetConnection} are SERVED: the real
 * {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through the real interceptors, the real
 * {@code GrantReachAuthorization}, the real orchestrator, the real {@code InventoryService}, the real secret store, both
 * planes' real triggers and the real audit. Two things are doubled: the host-key scan (a scripted
 * {@code HostKeyScanService}, so a host's keys, its refusals and its silence are deterministic) and, for one test, a fault
 * injected into the data-plane write. The scan is doubled because registration through the real sshd fixture end to end is
 * OWED with T1.5's S3 (OR-124 approved the fixture image; OQ-15 was ruled (a), OR-121). No such IT exists yet:
 * {@code SshdFixtureIT} proves only that the scanner sees the fixture's three host keys.
 *
 * <p>What this proves that {@code TargetRegistrationControllerTest} cannot: that every refusal leaves NOTHING in either
 * plane or the secret store (counted in the tables, as the owner), the S5 sequence and a concurrent double-submit against
 * the real unique indexes, and (MF-R3) that no byte of a key, and no {@code credential_ref}, reaches any appender or any
 * response.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TargetRegistrationEndpointIT.Doubles.class)
class TargetRegistrationEndpointIT {

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;

    /** The scripted host-key scan: a host's keys, or what it throws. Static, because the doubles are beans. */
    static final ScriptedScan SCAN = new ScriptedScan();
    static final AtomicBoolean FAIL_NEXT_DATA_PLANE_WRITE = new AtomicBoolean();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        world = DeclareFixtures.seed(ownerJdbc);
    }

    // ------------------------------------------------------------------------------------------- the doubles

    static final class ScriptedScan implements HostKeyScanService {
        private final Map<String, List<OfferedHostKey>> offered = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<String, RuntimeException> failures = new java.util.concurrent.ConcurrentHashMap<>();

        void offer(String host, String... fingerprints) {
            failures.remove(host);
            List<OfferedHostKey> keys = new ArrayList<>();
            for (int i = 0; i < fingerprints.length; i++) {
                keys.add(new OfferedHostKey(fingerprints[i], i == 0 ? "ssh-ed25519" : "ssh-rsa"));
            }
            offered.put(host, keys);
        }

        void fail(String host, RuntimeException failure) {
            failures.put(host, failure);
        }

        @Override
        public List<OfferedHostKey> scan(String host, int port) {
            RuntimeException failure = failures.get(host);
            if (failure != null) {
                throw failure;
            }
            List<OfferedHostKey> keys = offered.get(host);
            if (keys == null) {
                throw new HostKeyScanUnavailableException();
            }
            return keys;
        }
    }

    /** The real service, with one switch: the next {@code registerConnection} fails BEFORE it writes (a crash after the CP commit). */
    static final class FaultyAdministration implements TargetConnectionAdministration {
        private final TargetConnectionAdministration real;

        FaultyAdministration(TargetConnectionAdministration real) {
            this.real = real;
        }

        @Override
        public RegistrationPrecheck precheckRegistration(UUID organizationId, UUID replayingTargetId,
                TargetConnectionSpec spec, char[] privateKey) {
            return real.precheckRegistration(organizationId, replayingTargetId, spec, privateKey);
        }

        @Override
        public RegistrationOutcome registerConnection(UUID organizationId, UUID targetId, TargetConnectionSpec spec,
                char[] privateKey, UUID actor, TargetChangeOrigin origin) {
            if (FAIL_NEXT_DATA_PLANE_WRITE.compareAndSet(true, false)) {
                throw new UnsupportedOperationException("simulated crash: neither a refusal nor a conflict, so a 500");
            }
            return real.registerConnection(organizationId, targetId, spec, privateKey, actor, origin);
        }

        @Override
        public Optional<TargetConnectionView> connection(UUID organizationId, UUID targetId) {
            return real.connection(organizationId, targetId);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean
        @Primary
        HostKeyScanService scriptedHostKeyScan() {
            return SCAN;
        }

        @Bean
        @Primary
        TargetConnectionAdministration faultyAdministration(InventoryService real) {
            return new FaultyAdministration(real);
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private Client client() {
        return new Client(port);
    }

    private static String fp(char c) {
        return "SHA256:" + String.valueOf(c).repeat(43);
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return DeclareFixtures.MAPPER.readTree(response.body());
    }

    private ObjectNode registerBody(String name, String host, String pin, ObjectNode scope, String privateKey) {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("name", name).put("kind", "docker").put("transport", "ssh-docker");
        body.set("ownershipScope", scope);
        ObjectNode connection = body.putObject("connection");
        connection.put("host", host).put("port", 22).put("sshUser", "sm").put("hostKeyFingerprint", pin)
                .put("allowedConfigRoot", "/srv/sm").put("allowedContainerPrefix", "sm-");
        body.put("privateKey", privateKey);
        return body;
    }

    private ObjectNode organizationScope() {
        return DeclareFixtures.MAPPER.createObjectNode();
    }

    private ObjectNode environmentScope() {
        return DeclareFixtures.MAPPER.createObjectNode().put("environmentId", world.envOne().toString());
    }

    private long count(String sql, Map<String, Object> parameters) {
        Long n = ownerJdbc.queryForObject(sql, parameters, Long.class);
        return n == null ? 0 : n;
    }

    private long controlPlaneRows(String name) {
        return count("SELECT count(*) FROM sm_control.target WHERE organization_id = :o AND name = :n",
                Map.of("o", world.organization(), "n", name));
    }

    private long connectionRows() {
        return count("SELECT count(*) FROM sm_data.target_connection WHERE tenant_id = :t",
                Map.of("t", world.organization()));
    }

    private long secretRows() {
        return count("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t", Map.of("t", world.organization()));
    }

    private long eventRows() {
        return count("SELECT count(*) FROM sm_data.target_connection_event WHERE tenant_id = :t",
                Map.of("t", world.organization()));
    }

    /** What the three stores hold for this organization right now, so a refusal can be shown to have moved none. */
    private record Counts(long connections, long secrets, long events, long targets) {
    }

    private Counts counts() {
        return new Counts(connectionRows(), secretRows(), eventRows(), count(
                "SELECT count(*) FROM sm_control.target WHERE organization_id = :o", Map.of("o", world.organization())));
    }

    private static SshTestKeys.Key newKey() {
        return SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
    }

    // ------------------------------------------------------------------------------------------------- S1

    @Test
    void aTargetIsRegisteredAndReadBackWithNoKeyAnywhereInEitherResponse() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        SCAN.offer("s1-host.example.test", fp('K'), fp('L'));
        SshTestKeys.Key key = newKey();
        Counts before = counts();

        HttpResponse<String> response = http.post("/api/cp/targets", admin,
                registerBody("s1-vm", "s1-host.example.test", fp('K'), environmentScope(), key.text()));

        assertEquals(201, response.statusCode(), response.body());
        JsonNode body = json(response);
        UUID target = UUID.fromString(body.get("targetId").asText());
        assertEquals("s1-vm", body.get("name").asText());
        assertEquals(world.unit().toString(), body.at("/ownershipScope/subOrganizationId").asText(),
                "the sub-organization is derived from the tree, never trusted from the body");
        assertEquals(world.envOne().toString(), body.at("/ownershipScope/environmentId").asText());
        assertEquals(key.publicFingerprint(), body.at("/connection/credential/publicKeyFingerprint").asText());
        assertEquals(new Counts(before.connections() + 1, before.secrets() + 1, before.events() + 1,
                before.targets() + 1), counts());
        // the connection event carries the actor's correlation id and no key
        Map<String, Object> event = ownerJdbc.queryForMap("SELECT kind, correlation_id, public_key_fingerprint"
                + " FROM sm_data.target_connection_event WHERE tenant_id = :t AND target_id = :g",
                Map.of("t", world.organization(), "g", target));
        assertEquals("registered", event.get("kind"));
        assertEquals(response.headers().firstValue("X-Correlation-Id").orElseThrow(),
                event.get("correlation_id").toString());
        assertEquals(key.publicFingerprint(), event.get("public_key_fingerprint"));

        // the management read, and the listing
        HttpResponse<String> read = http.get("/api/dp/targets/" + target + "/connection", admin);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals("s1-host.example.test", json(read).get("host").asText());
        assertEquals(2, json(read).get("offeredHostKeys").size());
        String credentialRef = ownerJdbc.queryForObject("SELECT credential_ref::text FROM sm_data.target_connection"
                + " WHERE tenant_id = :t AND target_id = :g", Map.of("t", world.organization(), "g", target),
                String.class);
        for (String text : List.of(response.body(), read.body())) {
            assertFalse(text.contains(credentialRef), "the credential_ref is in no response");
            for (String line : key.text().split("\n")) {
                if (line.length() > 16) {
                    assertFalse(text.contains(line), "a line of the private key is in a response");
                }
            }
        }
        HttpResponse<String> list = http.get("/api/cp/targets", admin);
        assertTrue(list.body().contains("s1-vm"), list.body());
    }

    // --------------------------------------------------------------------------- every refusal leaves nothing

    private void refusalLeavesNothing(String name, String host, ObjectNode body, int status, String code)
            throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        Counts before = counts();

        HttpResponse<String> refused = http.post("/api/cp/targets", admin, body);

        assertEquals(status, refused.statusCode(), refused.body());
        assertEquals(code, json(refused).get("code").asText());
        assertEquals(before, counts(), "no control-plane row, no connection record, no secret, no event");
        assertEquals(0, controlPlaneRows(name));
    }

    @Test
    void anUnusableKeyIsRefusedAndAFixedKeyThenSucceeds() throws Exception {
        SCAN.offer("refuse-key.example.test", fp('M'));
        ObjectNode bad = registerBody("refuse-key", "refuse-key.example.test", fp('M'), organizationScope(),
                "this is not a private key");
        refusalLeavesNothing("refuse-key", "refuse-key.example.test", bad, 422, "CREDENTIAL_INVALID");

        Client http = client();
        assertEquals(201, http.post("/api/cp/targets", http.login(DeclareFixtures.ADMIN),
                registerBody("refuse-key", "refuse-key.example.test", fp('M'), organizationScope(), newKey().text()))
                .statusCode());
    }

    @Test
    void aPinTheHostDoesNotOfferIsRefusedAndTheCorrectedPinThenSucceeds() throws Exception {
        SCAN.offer("refuse-pin.example.test", fp('N'));
        SshTestKeys.Key key = newKey();
        refusalLeavesNothing("refuse-pin", "refuse-pin.example.test",
                registerBody("refuse-pin", "refuse-pin.example.test", fp('O'), organizationScope(), key.text()),
                422, "HOST_KEY_NOT_OFFERED");

        Client http = client();
        assertEquals(201, http.post("/api/cp/targets", http.login(DeclareFixtures.ADMIN),
                registerBody("refuse-pin", "refuse-pin.example.test", fp('N'), organizationScope(), key.text()))
                .statusCode());
    }

    /** Review W3C-2. OWED (Docker was down when this was written): never run. */
    @Test
    void aRegistrationsScanIsAuditedLikeAScanAndSharesTheSessionsScanBudget() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        SCAN.offer("budget.example.test", fp('N'));
        ObjectNode refused = registerBody("budget-vm", "budget.example.test", fp('O'), organizationScope(),
                newKey().text());
        String audited = "SELECT count(*) FROM sm_control.audit_log WHERE tenant = :t AND verb = 'target.create'";
        long before = count(audited, Map.of("t", world.organization()));

        assertEquals(422, http.post("/api/cp/targets", admin, refused).statusCode());

        assertEquals(before + 1, count(audited, Map.of("t", world.organization())),
                "the pre-check's scan leaves the audit row a scanHostKeys would");
        for (int i = 0; i < 9; i++) {
            assertEquals(422, http.post("/api/cp/targets", admin, refused).statusCode(), "attempt " + (i + 2));
        }
        HttpResponse<String> limited = http.post("/api/cp/targets", admin, refused);
        assertEquals(429, limited.statusCode(), limited.body());
        assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        assertEquals(0, controlPlaneRows("budget-vm"), "a refused registration still leaves nothing");
    }

    @Test
    void anAddressTheDataPlaneMayNotConnectToIsRefusedAndNothingIsWritten() throws Exception {
        SCAN.fail("refuse-addr.example.test", new TargetAddressRefusedException());
        refusalLeavesNothing("refuse-addr", "refuse-addr.example.test",
                registerBody("refuse-addr", "refuse-addr.example.test", fp('P'), organizationScope(), newKey().text()),
                422, "TARGET_ADDRESS_REFUSED");

        SCAN.offer("refuse-addr.example.test", fp('P'));
        Client http = client();
        assertEquals(201, http.post("/api/cp/targets", http.login(DeclareFixtures.ADMIN),
                registerBody("refuse-addr", "refuse-addr.example.test", fp('P'), organizationScope(), newKey().text()))
                .statusCode());
    }

    @Test
    void aHostThatCannotBeScannedIs503AndNothingIsWritten() throws Exception {
        SCAN.fail("refuse-down.example.test", new HostKeyScanUnavailableException());
        refusalLeavesNothing("refuse-down", "refuse-down.example.test",
                registerBody("refuse-down", "refuse-down.example.test", fp('Q'), organizationScope(), newKey().text()),
                503, "SERVICE_UNAVAILABLE");
    }

    @Test
    void aHostKeyHeldByAnotherLiveTargetIs409AndNothingIsWrittenForTheSecond() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        SCAN.offer("held-a.example.test", fp('R'), fp('S'));
        assertEquals(201, http.post("/api/cp/targets", admin, registerBody("held-a", "held-a.example.test", fp('R'),
                organizationScope(), newKey().text())).statusCode());
        // a second machine that presents one of the first's keys: the same machine registered twice, by another pin
        SCAN.offer("held-b.example.test", fp('T'), fp('S'));

        refusalLeavesNothing("held-b", "held-b.example.test", registerBody("held-b", "held-b.example.test", fp('T'),
                organizationScope(), newKey().text()), 409, "CONFLICT");
    }

    // ----------------------------------------------------------------------------------------- the S5 sequence

    @Test
    void aFailureAfterTheControlPlaneCommitIsCompletedByTheSamePostThenReplayedThenRefusedForADifferentKey()
            throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        SCAN.offer("s5-host.example.test", fp('U'));
        SshTestKeys.Key pair = newKey();
        // an RSA key, because the inspector opens it in both PEM encodings (T1.1's MF-R1): PKCS#8 registers, PKCS#1 re-exports
        java.security.KeyPair keys = SshTestKeys.rsaPair();
        SshTestKeys.Key key = SshTestKeys.rsaPkcs8(keys);
        SshTestKeys.Key sameAsPem = SshTestKeys.rsaPkcs1(keys);
        assertEquals(key.publicFingerprint(), sameAsPem.publicFingerprint());
        Counts before = counts();
        ObjectNode request = registerBody("s5-vm", "s5-host.example.test", fp('U'), organizationScope(), key.text());

        // 1. the data-plane write fails after the control-plane commit
        FAIL_NEXT_DATA_PLANE_WRITE.set(true);
        assertEquals(500, http.post("/api/cp/targets", admin, request).statusCode());
        assertEquals(1, controlPlaneRows("s5-vm"), "the control-plane row stays");
        assertEquals(before.connections(), connectionRows());
        // ... and listTargets shows it DEGRADED between the two writes, never hidden
        JsonNode listing = json(http.get("/api/cp/targets", admin));
        assertEquals("degraded", listing.at("/availability/state").asText(), listing.toString());
        assertTrue(listing.toString().contains("s5-vm"));

        // 2. the same POST completes it
        HttpResponse<String> completed = http.post("/api/cp/targets", admin, request);
        assertEquals(201, completed.statusCode(), completed.body());
        assertEquals(1, controlPlaneRows("s5-vm"));
        assertEquals(before.connections() + 1, connectionRows());
        assertEquals(before.secrets() + 1, secretRows());

        // 3. a replay
        assertEquals(200, http.post("/api/cp/targets", admin, request).statusCode());
        assertEquals(before.secrets() + 1, secretRows(), "a replay opens no second secret row");

        // 4. a different key
        HttpResponse<String> different = http.post("/api/cp/targets", admin,
                registerBody("s5-vm", "s5-host.example.test", fp('U'), organizationScope(), pair.text()));
        assertEquals(409, different.statusCode(), different.body());
        assertTrue(json(different).get("detail").asText().contains("rotate"), different.body());

        // 5. the same key re-exported as PKCS#1 PEM (another encoding of one key)
        assertEquals(200, http.post("/api/cp/targets", admin, registerBody("s5-vm", "s5-host.example.test", fp('U'),
                organizationScope(), sameAsPem.text())).statusCode());
        assertEquals(before.secrets() + 1, secretRows());
        assertEquals(before.events() + 1, eventRows(), "one registered event, whatever the replays");
    }

    // ---------------------------------------------------------------------------------- concurrent double submit

    @Test
    void twoIdenticalPostsAtOnceBothAnswerAndWriteOneRowInEachStore() throws Exception {
        SCAN.offer("race-host.example.test", fp('V'));
        SshTestKeys.Key key = newKey();
        Counts before = counts();
        ObjectNode request = registerBody("race-vm", "race-host.example.test", fp('V'), organizationScope(), key.text());
        Client http = client();
        Login first = http.login(DeclareFixtures.ADMIN);
        Login second = http.login(DeclareFixtures.ADMIN);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> both = new ArrayList<>();
            for (Login login : List.of(first, second)) {
                both.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return http.post("/api/cp/targets", login, request);
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<HttpResponse<String>> f : both) {
                HttpResponse<String> r = f.get(60, TimeUnit.SECONDS);
                statuses.add(r.statusCode());
                assertTrue(r.statusCode() == 200 || r.statusCode() == 201, r.statusCode() + " " + r.body());
            }
            assertTrue(statuses.contains(201), "one of them wrote it: " + statuses);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, controlPlaneRows("race-vm"));
        assertEquals(new Counts(before.connections() + 1, before.secrets() + 1, before.events() + 1,
                before.targets() + 1), counts());
    }

    // ------------------------------------------------------------------------------- authorization at the scope

    @Test
    void aViewerAndAnOperatorAreRefused403AndAnotherOrganizationSeesNothingAs404() throws Exception {
        Client http = client();
        SCAN.offer("authz-host.example.test", fp('W'));
        Counts before = counts();
        ObjectNode request = registerBody("authz-vm", "authz-host.example.test", fp('W'), environmentScope(),
                newKey().text());

        assertEquals(403, http.post("/api/cp/targets", http.login(DeclareFixtures.VIEWER), request).statusCode());
        assertEquals(403, http.post("/api/cp/targets", http.login(DeclareFixtures.OPERATOR_ONE), request).statusCode(),
                "an Operator holds target.use, not target.create");
        assertEquals(404, http.post("/api/cp/targets", http.login(DeclareFixtures.FOREIGN), request).statusCode(),
                "another organization's caller, a node of this one");
        assertEquals(404, http.post("/api/cp/targets", http.login(DeclareFixtures.ADMIN),
                registerBody("authz-vm", "authz-host.example.test", fp('W'), DeclareFixtures.MAPPER.createObjectNode()
                        .put("environmentId", UUID.randomUUID().toString()), newKey().text())).statusCode());
        assertEquals(before, counts());
    }

    // ---------------------------------------------------------------------------------------- the connection read

    @Test
    void theConnectionReadIsTargetEditAtTheTargetAndALegacyKeyHasNoFingerprintYet() throws Exception {
        Client http = client();

        // seeded targetOne: a connection record written by the fixture, with no stored key and no fingerprint
        HttpResponse<String> admin = http.get("/api/dp/targets/" + world.targetOne() + "/connection",
                http.login(DeclareFixtures.ADMIN));
        assertEquals(200, admin.statusCode(), admin.body());
        assertTrue(json(admin).at("/credential/present").asBoolean());
        assertNull(json(admin).at("/credential").get("publicKeyFingerprint"));

        assertEquals(403, http.get("/api/dp/targets/" + world.targetOne() + "/connection",
                http.login(DeclareFixtures.OPERATOR_ONE)).statusCode(), "target.use and target.view are not target.edit");
        assertEquals(403, http.get("/api/dp/targets/" + world.targetOne() + "/connection",
                http.login(DeclareFixtures.VIEWER)).statusCode());
        assertEquals(404, http.get("/api/dp/targets/" + world.targetOne() + "/connection",
                http.login(DeclareFixtures.FOREIGN)).statusCode());
        assertEquals(404, http.get("/api/dp/targets/" + UUID.randomUUID() + "/connection",
                http.login(DeclareFixtures.ADMIN)).statusCode());
    }

    // ----------------------------------------------------------------------------------------------- the scan

    @Test
    void aScanReturnsTheOfferedKeysIsAuditedWithoutThemAndIsRateLimitedPerSession() throws Exception {
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        SCAN.offer("scan-host.example.test", fp('X'), fp('Y'));
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode().put("host", "scan-host.example.test");
        body.set("ownershipScope", environmentScope());
        long auditBefore = count("SELECT count(*) FROM sm_control.audit_log WHERE tenant = :t AND verb = 'target.create'",
                Map.of("t", world.organization()));

        HttpResponse<String> scan = http.post("/api/dp/host-key-scans", admin, body);

        assertEquals(200, scan.statusCode(), scan.body());
        assertEquals(2, json(scan).get("offered").size());
        assertEquals(auditBefore + 1, count("SELECT count(*) FROM sm_control.audit_log WHERE tenant = :t"
                + " AND verb = 'target.create'", Map.of("t", world.organization())));
        Map<String, Object> row = ownerJdbc.queryForMap("SELECT resource_kind, resource_id, outcome, detail::text AS d"
                + " FROM sm_control.audit_log WHERE tenant = :t AND verb = 'target.create' ORDER BY audit_id DESC"
                + " LIMIT 1", Map.of("t", world.organization()));
        assertEquals("environment", row.get("resource_kind"));
        assertEquals(world.envOne(), row.get("resource_id"));
        assertFalse(row.get("d").toString().contains(fp('X')), "the audit row never holds the keys");
        assertFalse(row.get("d").toString().contains("scan-host"), "or the host");

        // the budget is per session: nine more are admitted, the eleventh in all is 429 with Retry-After
        for (int i = 0; i < 9; i++) {
            assertEquals(200, http.post("/api/dp/host-key-scans", admin, body).statusCode(), "scan " + (i + 2));
        }
        HttpResponse<String> limited = http.post("/api/dp/host-key-scans", admin, body);
        assertEquals(429, limited.statusCode(), limited.body());
        assertTrue(limited.headers().firstValue("Retry-After").isPresent());
        assertEquals(200, http.post("/api/dp/host-key-scans", http.login(DeclareFixtures.ADMIN), body).statusCode(),
                "another session has its own budget");
    }

    @Test
    void aScanIsRefusedToAViewerAndToAnotherOrganization() throws Exception {
        Client http = client();
        SCAN.offer("scan-authz.example.test", fp('Z'));
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode().put("host", "scan-authz.example.test");
        body.set("ownershipScope", organizationScope());

        assertEquals(403, http.post("/api/dp/host-key-scans", http.login(DeclareFixtures.VIEWER), body).statusCode());
        ObjectNode foreign = DeclareFixtures.MAPPER.createObjectNode().put("host", "scan-authz.example.test");
        foreign.set("ownershipScope", environmentScope());
        assertEquals(404, http.post("/api/dp/host-key-scans", http.login(DeclareFixtures.FOREIGN), foreign).statusCode());
    }

    // ---------------------------------------------------------------------------------------------- MF-R3

    /**
     * MF-R3: a key is registered through HTTP by every path (accepted, replayed, refused, unreadable) with an appender on
     * the ROOT logger at the levels the application is configured for, and not one line of the key, and no
     * {@code credential_ref}, appears in any message, argument, MDC value or stack trace.
     */
    @Test
    void noAppenderSeesAnyLineOfTheKeyOrTheCredentialRef() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            Client http = client();
            Login admin = http.login(DeclareFixtures.ADMIN);
            SCAN.offer("log-host.example.test", fp('a'));
            SshTestKeys.Key key = newKey();
            ObjectNode request = registerBody("log-vm", "log-host.example.test", fp('a'), organizationScope(), key.text());
            assertEquals(201, http.post("/api/cp/targets", admin, request).statusCode());          // accepted
            assertEquals(200, http.post("/api/cp/targets", admin, request).statusCode());          // replayed
            // half of the key: malformed, but still made of the key's own lines, which is what must not be logged
            ObjectNode refused = registerBody("log-vm-2", "log-host.example.test", fp('a'), organizationScope(),
                    key.text().substring(0, key.text().length() / 2));
            assertEquals(422, http.post("/api/cp/targets", admin, refused).statusCode());          // refused
            assertEquals(400, http.post("/api/cp/targets", admin, registerBody("log-vm-3", "h", fp('a'),
                    organizationScope(), key.text()).put("bogus", 1)).statusCode());               // unreadable
            FAIL_NEXT_DATA_PLANE_WRITE.set(true);
            SCAN.offer("log-host-2.example.test", fp('b'));
            assertEquals(500, http.post("/api/cp/targets", admin, registerBody("log-vm-4", "log-host-2.example.test",
                    fp('b'), organizationScope(), key.text())).statusCode());                      // a failure

            String credentialRef = ownerJdbc.queryForObject("SELECT credential_ref::text FROM sm_data.target_connection"
                    + " c JOIN sm_control.target t ON t.target_id = c.target_id WHERE t.name = 'log-vm'",
                    Map.of(), String.class);
            assertNotNull(credentialRef);
            List<String> lines = new ArrayList<>();
            List<ILoggingEvent> events;
            synchronized (appender.list) {
                events = new ArrayList<>(appender.list);
            }
            for (ILoggingEvent event : events) {
                lines.add(event.getFormattedMessage());
                lines.add(String.valueOf(event.getMessage()));
                lines.add(String.valueOf(event.getMDCPropertyMap()));
                if (event.getArgumentArray() != null) {
                    for (Object argument : event.getArgumentArray()) {
                        lines.add(String.valueOf(argument));
                    }
                }
                for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                    lines.add(String.valueOf(t.getMessage()));
                }
            }
            assertFalse(events.isEmpty(), "the capture saw the application log");
            for (String line : lines) {
                assertFalse(line.contains(credentialRef), "a log line carries the credential_ref: " + line);
                for (String keyLine : key.text().split("\n")) {
                    if (keyLine.length() > 16) {
                        assertFalse(line.contains(keyLine), "a log line carries a line of the key: " + line);
                    }
                }
            }
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    // --------------------------------------------------------------------------------- the transport, plain HTTP

    @Test
    void aCredentialOverPlainHttpFromANonLoopbackPeerIsRefusedBeforeItsBodyIsRead() throws Exception {
        java.net.InetAddress external = null;
        for (java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
                nics != null && nics.hasMoreElements();) {
            java.net.NetworkInterface nic = nics.nextElement();
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (java.util.Enumeration<java.net.InetAddress> addresses = nic.getInetAddresses();
                    addresses.hasMoreElements();) {
                java.net.InetAddress candidate = addresses.nextElement();
                if (candidate instanceof java.net.Inet4Address && !candidate.isLoopbackAddress()
                        && !candidate.isLinkLocalAddress()) {
                    external = candidate;
                }
            }
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(external != null, "this machine has no non-loopback IPv4 address");
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCAN.offer("plain-host.example.test", fp('c'));
        String body = registerBody("plain-vm", "plain-host.example.test", fp('c'), organizationScope(), newKey().text())
                .toString();

        // connecting to the machine's own non-loopback address makes the socket peer that address, not 127.0.0.1
        HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://" + external.getHostAddress() + ":"
                        + port + "/api/cp/targets")).header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + admin.session())
                        .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", admin.csrf())
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        assertEquals(403, response.statusCode(), response.body());
        assertEquals("INSECURE_TRANSPORT", json(response).get("code").asText());
        assertEquals(0, controlPlaneRows("plain-vm"));
    }
}
