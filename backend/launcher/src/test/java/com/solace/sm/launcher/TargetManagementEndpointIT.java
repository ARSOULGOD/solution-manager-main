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
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.api.inventory.TargetConnectionTestResult;
import com.solace.sm.dp.api.inventory.TargetConnectionTester;
import com.solace.sm.dp.inventory.SshTestKeys;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
 * Plan 013 T1.5: {@code editTargetConnection}, {@code rotateTargetCredential}, {@code testTargetConnection} and
 * {@code retireTarget} are SERVED: the real {@code SolutionManagerApplication} over real HTTP against PostgreSQL, through
 * the real interceptors, the real grants, the real orchestrators, the real {@code InventoryService}, the real secret store,
 * both planes' real triggers and the real audit. Two things are doubled: the host-key scan (scripted, so a host's keys and
 * its refusals are deterministic) and the connection test's SSH probe (scripted, because it needs a daemon the build host
 * does not have; the probe against a real {@code sshd} is dp-core's {@code TargetConnectionTestIT}, and the Docker daemon is
 * live gate L4).
 *
 * <p>Ran green on {@code postgres:14}, 14 of 14, in the combined W3+W4 full build (2026-10-08). It was written while Docker was
 * down and first run on a throwaway local PostgreSQL 16 (plan 013 T1.5 Results).
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TargetManagementEndpointIT.Doubles.class)
class TargetManagementEndpointIT {

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;

    static final TargetRegistrationEndpointIT.ScriptedScan SCAN = new TargetRegistrationEndpointIT.ScriptedScan();
    /** What the doubled probe answers for the next connection test; a throwable is thrown instead. */
    static final AtomicReference<Object> TEST_RESULT = new AtomicReference<>();

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

    @TestConfiguration(proxyBeanMethods = false)
    static class Doubles {
        @Bean
        @Primary
        HostKeyScanService scriptedHostKeyScan() {
            return SCAN;
        }

        @Bean
        @Primary
        TargetConnectionTester scriptedTester() {
            return (organizationId, targetId) -> {
                Object next = TEST_RESULT.get();
                if (next instanceof RuntimeException failure) {
                    throw failure;
                }
                return (TargetConnectionTestResult) next;
            };
        }
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private Client client() {
        return new Client(port);
    }

    private HttpResponse<String> call(String method, String path, Login login, JsonNode body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", DeclareFixtures.SESSION_COOKIE + "=" + login.session())
                .header("Origin", DeclareFixtures.CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                .timeout(java.time.Duration.ofSeconds(30));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return HTTP.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return DeclareFixtures.MAPPER.readTree(response.body());
    }

    private static String fp(char c) {
        return "SHA256:" + String.valueOf(c).repeat(43);
    }

    private static ObjectNode body(String field, Object value) {
        ObjectNode node = DeclareFixtures.MAPPER.createObjectNode();
        if (value instanceof Integer i) {
            node.put(field, i);
        } else {
            node.put(field, String.valueOf(value));
        }
        return node;
    }

    private static SshTestKeys.Key newKey() {
        return SshTestKeys.ed25519OpenSsh(SshTestKeys.ed25519Pair());
    }

    private ObjectNode registerBody(String name, String host, String pin, String privateKey) {
        ObjectNode body = DeclareFixtures.MAPPER.createObjectNode();
        body.put("name", name).put("kind", "docker").put("transport", "ssh-docker");
        body.set("ownershipScope", DeclareFixtures.MAPPER.createObjectNode().put("environmentId", world.envOne().toString()));
        ObjectNode connection = body.putObject("connection");
        connection.put("host", host).put("port", 22).put("sshUser", "sm").put("hostKeyFingerprint", pin)
                .put("allowedConfigRoot", "/srv/sm").put("allowedContainerPrefix", "sm-");
        body.put("privateKey", privateKey);
        return body;
    }

    /** A target registered through the API, as the Administrator, whose host offers {@code keys} (the first is the pin). */
    private UUID registered(String name, SshTestKeys.Key key, char... keys) throws Exception {
        String host = name + ".example.test";
        String[] fingerprints = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            fingerprints[i] = fp(keys[i]);
        }
        SCAN.offer(host, fingerprints);
        Client http = client();
        HttpResponse<String> response = call("POST", "/api/cp/targets", http.login(DeclareFixtures.ADMIN),
                registerBody(name, host, fingerprints[0], key.text()));
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(json(response).get("targetId").asText());
    }

    private long count(String sql, Map<String, Object> parameters) {
        Long n = ownerJdbc.queryForObject(sql, parameters, Long.class);
        return n == null ? 0 : n;
    }

    private long secretRows() {
        return count("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t", Map.of("t", world.organization()));
    }

    private List<Map<String, Object>> events(UUID target) {
        return ownerJdbc.queryForList("SELECT kind, changed_fields::text AS fields, public_key_fingerprint"
                + " FROM sm_data.target_connection_event WHERE tenant_id = :t AND target_id = :g"
                + " ORDER BY recorded_at, event_id", Map.of("t", world.organization(), "g", target));
    }

    private Map<String, Boolean> keyStates(UUID target) {
        Map<String, Boolean> out = new TreeMap<>();
        ownerJdbc.query("SELECT fingerprint, retired_at IS NOT NULL AS retired FROM sm_data.target_host_key"
                + " WHERE tenant_id = :t AND target_id = :g", Map.of("t", world.organization(), "g", target),
                rs -> {
                    out.put(rs.getString("fingerprint"), rs.getBoolean("retired"));
                });
        return out;
    }

    private long auditRows(String verb, UUID target) {
        return count("SELECT count(*) FROM sm_control.audit_log WHERE tenant = :t AND verb = :v AND resource_id = :g",
                Map.of("t", world.organization(), "v", verb, "g", target));
    }

    /** A live placement of a fresh active instance on the target, with an apply request in {@code state} on it. */
    private UUID workOn(UUID target, String state) {
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        ownerJdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, name, lifecycle, acquisition_mode, created_by, created_at)"
                        + " VALUES (:t, :i, :e, :c, 'cache', '2.4', :n, 'active', 'via-ssh', :u, now())",
                Map.of("t", world.organization(), "i", instance, "e", world.envOne(), "c", UUID.randomUUID(),
                        "n", "mgmt-" + instance, "u", UUID.randomUUID()));
        ownerJdbc.update("INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at) VALUES (:t, :p, :i, :g, :c, :d, '/app/external/spring/config', 'application.yml',"
                        + " 'ADDITIONAL_LOCATION', 10, now())",
                Map.of("t", world.organization(), "p", placement, "i", instance, "g", target, "c", "sm-" + placement,
                        "d", "/srv/sm/" + placement));
        ownerJdbc.update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (:t, :i) ON CONFLICT DO NOTHING",
                Map.of("t", world.organization(), "i", instance));
        if (state == null) {
            return placement;
        }
        boolean open = List.of("REQUESTED", "RUNNING", "APPLY_INDETERMINATE").contains(state);
        ownerJdbc.update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id, placement_id, operation,"
                        + " generation, strategy, requested_by, requested_at, state, terminal_at)"
                        + " VALUES (:a, :t, :i, :p, 'apply', 3, 'manual', 'operator-1', now(), :s, :term)",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("a", UUID.randomUUID()).addValue("t", world.organization()).addValue("i", instance)
                        .addValue("p", placement).addValue("s", state)
                        .addValue("term", open ? null : OffsetDateTime.now()));
        return placement;
    }

    private void endWork(UUID target) {
        ownerJdbc.update("UPDATE sm_data.apply_request SET state = 'APPLIED', terminal_at = now() WHERE tenant_id = :t"
                + " AND state IN ('REQUESTED','RUNNING') AND placement_id IN (SELECT placement_id FROM sm_data.placement"
                + " WHERE tenant_id = :t AND target_id = :g)", Map.of("t", world.organization(), "g", target));
    }

    // ---------------------------------------------------------------------------------------- editTargetConnection

    @Test
    void anSshUserEditIsServedWithNoScanNoChargeAndAnEditedEvent() throws Exception {
        UUID target = registered("edit-user", newKey(), 'a');
        Login admin = client().login(DeclareFixtures.ADMIN);
        long scansBefore = auditRows("target.edit", target);

        HttpResponse<String> response = call("PATCH", "/api/dp/targets/" + target + "/connection", admin,
                body("sshUser", "deploy"));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("deploy", json(response).get("sshUser").asText());
        assertEquals("deploy", ownerJdbc.queryForObject("SELECT ssh_user FROM sm_data.target_connection WHERE tenant_id = :t"
                + " AND target_id = :g", Map.of("t", world.organization(), "g", target), String.class));
        assertEquals("edited", events(target).get(1).get("kind"));
        assertEquals("{sshUser}", events(target).get(1).get("fields"));
        assertEquals(scansBefore, auditRows("target.edit", target), "no scan, so no scan audit row");
    }

    @Test
    void aHostEditRescansRetiresAndRevivesKeysRecordsTheEventAndAuditsTheRescan() throws Exception {
        UUID target = registered("edit-host", newKey(), 'b', 'c');
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCAN.offer("edit-host-2.example.test", fp('b'), fp('d'));       // the new machine: still offers the pin, not c, and d

        HttpResponse<String> response = call("PATCH", "/api/dp/targets/" + target + "/connection", admin,
                body("host", "edit-host-2.example.test"));

        assertEquals(200, response.statusCode(), response.body());
        assertEquals("edit-host-2.example.test", json(response).get("host").asText());
        assertEquals(Map.of(fp('b'), false, fp('c'), true, fp('d'), false), keyStates(target));
        assertEquals("edited", events(target).get(1).get("kind"));
        assertEquals(1, auditRows("target.edit", target), "the rescan is audited as target.edit on the target");
    }

    @Test
    void aRepinToAKeyThatWasRetiredBeforeRevivesIt() throws Exception {
        UUID target = registered("edit-repin", newKey(), 'E', 'F');
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCAN.offer("edit-repin.example.test", fp('F'));                 // E is gone from the host
        assertEquals(200, call("PATCH", "/api/dp/targets/" + target + "/connection", admin, body("hostKeyFingerprint", fp('F')))
                .statusCode());
        assertEquals(Map.of(fp('E'), true, fp('F'), false), keyStates(target));

        SCAN.offer("edit-repin.example.test", fp('E'), fp('F'));        // and E is back
        HttpResponse<String> back = call("PATCH", "/api/dp/targets/" + target + "/connection", admin,
                body("hostKeyFingerprint", fp('E')));

        assertEquals(200, back.statusCode(), back.body());
        assertEquals(Map.of(fp('E'), false, fp('F'), false), keyStates(target));
        assertEquals("repinned", events(target).get(events(target).size() - 1).get("kind"));
    }

    @Test
    void workInFlightRefusesAHostEditAndTheSameEditAfterItEndsSucceeds() throws Exception {
        UUID target = registered("edit-busy", newKey(), 'G');
        Login admin = client().login(DeclareFixtures.ADMIN);
        workOn(target, "RUNNING");

        HttpResponse<String> refused = call("PATCH", "/api/dp/targets/" + target + "/connection", admin, body("port", 2222));

        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("CONFLICT", json(refused).get("code").asText());
        assertEquals("work is in flight on a placement of this target; let it finish, then edit the connection",
                json(refused).get("detail").asText());
        assertEquals(22, ownerJdbc.queryForObject("SELECT port FROM sm_data.target_connection WHERE tenant_id = :t"
                + " AND target_id = :g", Map.of("t", world.organization(), "g", target), Integer.class));
        // not refused while work is in flight: an SSH-user-only edit, and a rotation
        assertEquals(200, call("PATCH", "/api/dp/targets/" + target + "/connection", admin, body("sshUser", "deploy"))
                .statusCode());
        assertEquals(200, call("PUT", "/api/dp/targets/" + target + "/credential", admin,
                body("privateKey", newKey().text())).statusCode());

        endWork(target);
        assertEquals(200, call("PATCH", "/api/dp/targets/" + target + "/connection", admin, body("port", 2222)).statusCode());
    }

    @Test
    void theRefusalsOfAnEditAreTheirOwnStatusesAndLeaveTheRecordAlone() throws Exception {
        UUID target = registered("edit-refuse", newKey(), 'H');
        Login admin = client().login(DeclareFixtures.ADMIN);
        String path = "/api/dp/targets/" + target + "/connection";

        SCAN.offer("nowhere.example.test", fp('X'));
        HttpResponse<String> notOffered = call("PATCH", path, admin, body("host", "nowhere.example.test"));
        assertEquals(422, notOffered.statusCode(), notOffered.body());
        assertEquals("HOST_KEY_NOT_OFFERED", json(notOffered).get("code").asText());

        SCAN.fail("refused.example.test", new TargetAddressRefusedException());
        assertEquals("TARGET_ADDRESS_REFUSED", json(call("PATCH", path, admin, body("host", "refused.example.test")))
                .get("code").asText());

        SCAN.fail("down.example.test", new HostKeyScanUnavailableException());
        HttpResponse<String> down = call("PATCH", path, admin, body("host", "down.example.test"));
        assertEquals(503, down.statusCode());

        UUID other = registered("edit-refuse-other", newKey(), 'Z');
        SCAN.offer("heldby.example.test", fp('H'), fp('Z'));            // Z belongs to the other target
        HttpResponse<String> held = call("PATCH", path, admin, body("host", "heldby.example.test"));
        assertEquals(409, held.statusCode(), held.body());
        assertNotNull(other);

        for (String bad : List.of("allowedConfigRoot", "allowedContainerPrefix", "organizationId", "privateKey")) {
            assertEquals(400, call("PATCH", path, admin, body(bad, "/srv/other")).statusCode(), bad);
        }
        assertEquals(400, call("PATCH", path, admin, DeclareFixtures.MAPPER.createObjectNode()).statusCode());
        assertEquals(422, call("PATCH", path, admin, body("sshUser", "not a login")).statusCode());

        assertEquals("edit-refuse.example.test", ownerJdbc.queryForObject("SELECT host FROM sm_data.target_connection"
                + " WHERE tenant_id = :t AND target_id = :g", Map.of("t", world.organization(), "g", target), String.class));
        assertEquals(1, events(target).size(), "every refusal left no event");
    }

    @Test
    void anEditNeedsTargetEditAtTheTargetAndAnotherOrganizationsTargetIsAbsent() throws Exception {
        UUID target = registered("edit-authz", newKey(), 'I');
        Client http = client();
        String path = "/api/dp/targets/" + target + "/connection";

        assertEquals(403, call("PATCH", path, http.login(DeclareFixtures.VIEWER), body("sshUser", "x")).statusCode());
        assertEquals(403, call("PATCH", path, http.login(DeclareFixtures.OPERATOR_ONE), body("sshUser", "x")).statusCode());
        assertEquals(404, call("PATCH", path, http.login(DeclareFixtures.FOREIGN), body("sshUser", "x")).statusCode());
        assertEquals(1, events(target).size());
    }

    @Test
    void theEleventhRescanInAMinuteIs429AndASshUserEditIsNeverCounted() throws Exception {
        UUID target = registered("edit-rate", newKey(), 'J');
        Login admin = client().login(DeclareFixtures.ADMIN);
        SCAN.offer("edit-rate.example.test", fp('J'));
        for (int i = 0; i < 12; i++) {
            assertEquals(200, call("PATCH", "/api/dp/targets/" + target + "/connection", admin,
                    body("sshUser", "user" + i)).statusCode(), "a user-only edit is not a scan: " + i);
        }
        HttpResponse<String> last = null;
        int answered = 0;
        for (int i = 0; i < 12; i++) {
            last = call("PATCH", "/api/dp/targets/" + target + "/connection", admin, body("port", 3000 + i));
            if (last.statusCode() == 429) {
                break;
            }
            answered++;
        }
        assertEquals(429, last.statusCode(), last.body());
        assertEquals("RATE_LIMITED", json(last).get("code").asText());
        assertTrue(last.headers().firstValue("Retry-After").isPresent());
        assertTrue(answered <= 10, "the budget is ten a minute for this session, shared with the scans: " + answered);
    }

    // ------------------------------------------------------------------------------------- rotateTargetCredential

    @Test
    void aRotationIsServedTheSameKeyAgainIsANoOpAndTheNewKeyIsInTheSummary() throws Exception {
        SshTestKeys.Key first = newKey();
        UUID target = registered("rotate-it", first, 'K');
        Login admin = client().login(DeclareFixtures.ADMIN);
        String path = "/api/dp/targets/" + target + "/credential";
        long before = secretRows();
        SshTestKeys.Key next = newKey();

        HttpResponse<String> rotated = call("PUT", path, admin, body("privateKey", next.text()));

        assertEquals(200, rotated.statusCode(), rotated.body());
        assertEquals(next.publicFingerprint(), json(rotated).at("/credential/publicKeyFingerprint").asText());
        assertEquals(before + 1, secretRows());
        assertEquals("credential_rotated", events(target).get(1).get("kind"));
        assertEquals(next.publicFingerprint(), events(target).get(1).get("public_key_fingerprint"));
        assertEquals(next.publicFingerprint(), json(call("GET", "/api/dp/targets/" + target + "/connection", admin, null))
                .at("/credential/publicKeyFingerprint").asText());

        assertEquals(200, call("PUT", path, admin, body("privateKey", next.text().replace("\n", "\r\n"))).statusCode());
        assertEquals(before + 1, secretRows(), "the same key re-exported writes no second secret row (OR-85)");
        assertEquals(2, events(target).size());

        HttpResponse<String> invalid = call("PUT", path, admin, body("privateKey", "this is not a private key"));
        assertEquals(422, invalid.statusCode());
        assertEquals("CREDENTIAL_INVALID", json(invalid).get("code").asText());
        assertEquals(before + 1, secretRows());
    }

    @Test
    void aRotationNeedsTargetEditAndNoAppenderSeesAnyLineOfEitherKeyOrTheCredentialRef() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            LoggerFactory.getLogger("target-management-it").warn("the capture is attached");
            SshTestKeys.Key first = newKey();
            UUID target = registered("rotate-log", first, 'L');
            Client http = client();
            Login admin = http.login(DeclareFixtures.ADMIN);
            String path = "/api/dp/targets/" + target + "/credential";
            SshTestKeys.Key next = newKey();
            assertEquals(403, call("PUT", path, http.login(DeclareFixtures.OPERATOR_ONE), body("privateKey", next.text()))
                    .statusCode());
            assertEquals(200, call("PUT", path, admin, body("privateKey", next.text())).statusCode());       // accepted
            assertEquals(200, call("PUT", path, admin, body("privateKey", next.text())).statusCode());       // same key
            assertEquals(422, call("PUT", path, admin, body("privateKey",
                    next.text().substring(0, next.text().length() / 2))).statusCode());                      // refused
            assertEquals(400, call("PUT", path, admin, DeclareFixtures.MAPPER.createObjectNode()
                    .put("privateKey", next.text()).put("bogus", 1)).statusCode());                          // unreadable

            String credentialRef = ownerJdbc.queryForObject("SELECT credential_ref::text FROM sm_data.target_connection"
                    + " WHERE tenant_id = :t AND target_id = :g", Map.of("t", world.organization(), "g", target),
                    String.class);
            List<String> lines = new ArrayList<>();
            List<ILoggingEvent> events;
            synchronized (appender.list) {
                events = new ArrayList<>(appender.list);
            }
            for (ILoggingEvent event : events) {
                lines.add(event.getFormattedMessage());
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
                for (SshTestKeys.Key key : List.of(first, next)) {
                    for (String keyLine : key.text().split("\n")) {
                        if (keyLine.length() > 16) {
                            assertFalse(line.contains(keyLine), "a log line carries a line of a key: " + line);
                        }
                    }
                }
            }
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    // ------------------------------------------------------------------------------------- testTargetConnection

    @Test
    void aConnectionTestIsServedToAnOperatorAndAuditedAndEveryFailureIsA200() throws Exception {
        UUID target = registered("test-it", newKey(), 'M');
        Client http = client();
        String path = "/api/dp/targets/" + target + "/connection/test";
        TEST_RESULT.set(new TargetConnectionTestResult(true, true, true, "28.5.1", Instant.parse("2026-10-08T02:00:00Z")));

        // target.use: the Operator holds it at the environment the target is owned at; the Viewer does not
        HttpResponse<String> passed = call("POST", path, http.login(DeclareFixtures.OPERATOR_ONE), null);
        assertEquals(200, passed.statusCode(), passed.body());
        assertTrue(json(passed).get("hostKeyPinned").asBoolean());
        assertEquals("28.5.1", json(passed).at("/docker/serverVersion").asText());
        assertEquals(403, call("POST", path, http.login(DeclareFixtures.VIEWER), null).statusCode());
        assertEquals(1, auditRows("target.use", target));

        TEST_RESULT.set(new TargetConnectionTestResult(true, true, false, null, Instant.parse("2026-10-08T02:00:00Z")));
        HttpResponse<String> noDocker = call("POST", path, http.login(DeclareFixtures.ADMIN), null);
        assertEquals(200, noDocker.statusCode());
        assertFalse(json(noDocker).at("/docker/available").asBoolean());
        assertTrue(json(noDocker).at("/docker").path("serverVersion").isMissingNode());
        assertEquals(2, auditRows("target.use", target));
        assertEquals("docker_unavailable", ownerJdbc.queryForObject("SELECT detail->>'new_status' FROM sm_control.audit_log"
                + " WHERE tenant = :t AND verb = 'target.use' AND resource_id = :g ORDER BY at DESC LIMIT 1",
                Map.of("t", world.organization(), "g", target), String.class));

        TEST_RESULT.set(new NoSuchElementException("no live connection record"));
        assertEquals(404, call("POST", path, http.login(DeclareFixtures.ADMIN), null).statusCode());
        assertEquals(404, call("POST", path, http.login(DeclareFixtures.FOREIGN), null).statusCode());
    }

    // ----------------------------------------------------------------------------------------------- retireTarget

    @Test
    void aTargetIsRetiredDataPlaneFirstItsNameIsFreeAndTheRetryIs200() throws Exception {
        SshTestKeys.Key key = newKey();
        UUID target = registered("retire-it", key, 'N', 'O');
        Client http = client();
        Login admin = http.login(DeclareFixtures.ADMIN);
        String path = "/api/cp/targets/" + target + "/retire";
        long secrets = secretRows();

        HttpResponse<String> retired = call("POST", path, admin, null);

        assertEquals(200, retired.statusCode(), retired.body());
        assertNotNull(json(retired).get("retiredAt"), retired.body());
        assertEquals("retire-it", json(retired).get("name").asText());
        assertNotNull(ownerJdbc.queryForObject("SELECT retired_at FROM sm_data.target_connection WHERE tenant_id = :t AND"
                + " target_id = :g", Map.of("t", world.organization(), "g", target), java.sql.Timestamp.class));
        assertNotNull(ownerJdbc.queryForObject("SELECT retired_at FROM sm_control.target WHERE organization_id = :t AND"
                + " target_id = :g", Map.of("t", world.organization(), "g", target), java.sql.Timestamp.class));
        assertEquals(Map.of(fp('N'), true, fp('O'), true), keyStates(target));
        assertEquals("retired", events(target).get(1).get("kind"));
        assertEquals(secrets, secretRows(), "the secret row is kept (OR-85)");
        assertEquals(1, count("SELECT count(*) FROM sm_control.change_audit WHERE audit_operation = 'TARGET_RETIRED'"
                + " AND row_key::text LIKE :k", Map.of("k", "%" + target + "%")), "one audited retirement");
        assertFalse(http.get("/api/cp/targets", admin).body().contains("retire-it"), "a retired target is not listed");

        HttpResponse<String> again = call("POST", path, admin, null);
        assertEquals(200, again.statusCode());
        assertEquals(json(retired).get("retiredAt").asText(), json(again).get("retiredAt").asText());
        assertEquals(2, events(target).size());

        // the name and the machine are free again
        SCAN.offer("retire-it.example.test", fp('N'), fp('O'));
        HttpResponse<String> reregistered = call("POST", "/api/cp/targets", admin,
                registerBody("retire-it", "retire-it.example.test", fp('N'), key.text()));
        assertEquals(201, reregistered.statusCode(), reregistered.body());
        assertFalse(json(reregistered).get("targetId").asText().equals(target.toString()));
        // and a retired target is gone from the management routes
        assertEquals(404, call("GET", "/api/dp/targets/" + target + "/connection", admin, null).statusCode());
        assertEquals(404, call("PUT", "/api/dp/targets/" + target + "/credential", admin,
                body("privateKey", newKey().text())).statusCode());
    }

    @Test
    void aLivePlacementRefusesTheRetirementAndNeitherPlaneChanges() throws Exception {
        UUID target = registered("retire-busy", newKey(), 'P');
        Login admin = client().login(DeclareFixtures.ADMIN);
        workOn(target, null);

        HttpResponse<String> refused = call("POST", "/api/cp/targets/" + target + "/retire", admin, null);

        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("CONFLICT", json(refused).get("code").asText());
        assertNull(ownerJdbc.queryForObject("SELECT retired_at FROM sm_control.target WHERE organization_id = :t AND"
                + " target_id = :g", Map.of("t", world.organization(), "g", target), java.sql.Timestamp.class));
        assertNull(ownerJdbc.queryForObject("SELECT retired_at FROM sm_data.target_connection WHERE tenant_id = :t AND"
                + " target_id = :g", Map.of("t", world.organization(), "g", target), java.sql.Timestamp.class));
    }

    @Test
    void aControlPlaneRowTheDataPlaneNeverHeardOfRetiresOnItsOwn() throws Exception {
        UUID orphan = UUID.randomUUID();
        UUID scope = ownerJdbc.queryForObject("SELECT ownership_scope_id FROM sm_control.target WHERE target_id = :g",
                Map.of("g", world.targetOne()), UUID.class);
        UUID creator = ownerJdbc.queryForObject("SELECT id FROM sm_control.principal WHERE username = :u",
                Map.of("u", DeclareFixtures.ADMIN), UUID.class);
        ownerJdbc.update("INSERT INTO sm_control.target (target_id, organization_id, ownership_scope_id, name, kind,"
                        + " transport, created_by, created_at) VALUES (:id, :o, :s, 'orphan-vm', 'docker', 'ssh-docker',"
                        + " :c, now())", Map.of("id", orphan, "o", world.organization(), "s", scope, "c", creator));
        Login admin = client().login(DeclareFixtures.ADMIN);

        HttpResponse<String> response = call("POST", "/api/cp/targets/" + orphan + "/retire", admin, null);

        assertEquals(200, response.statusCode(), response.body());
        assertNotNull(json(response).get("retiredAt"));
    }

    @Test
    void retiringNeedsTargetEditAndAnotherOrganizationsTargetIsAbsent() throws Exception {
        UUID target = registered("retire-authz", newKey(), 'Q');
        Client http = client();
        String path = "/api/cp/targets/" + target + "/retire";

        assertEquals(403, call("POST", path, http.login(DeclareFixtures.VIEWER), null).statusCode());
        assertEquals(403, call("POST", path, http.login(DeclareFixtures.OPERATOR_ONE), null).statusCode());
        assertEquals(404, call("POST", path, http.login(DeclareFixtures.FOREIGN), null).statusCode());
        assertEquals(404, call("POST", "/api/cp/targets/" + UUID.randomUUID() + "/retire",
                http.login(DeclareFixtures.ADMIN), null).statusCode());
        assertEquals(1, events(target).size());
    }
}
