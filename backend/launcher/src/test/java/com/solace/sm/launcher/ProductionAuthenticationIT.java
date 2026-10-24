package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.identity.LocalLoginService;
import com.solace.sm.cp.persistence.SmControlLockoutRecoveryMain;
import com.solace.sm.cp.persistence.SmControlMigrationMain;
import com.solace.sm.dp.apply.persistence.SmDataMigrationMain;
import com.solace.sm.gateway.http.ConsoleOperation;
import com.solace.sm.gateway.http.ResourceResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the production launcher — the real {@code SolutionManagerApplication}, composed only
 * through {@link SolutionManagerConfiguration}'s imports, with real Tomcat, a real {@code
 * sm_control} + {@code sm_data} PostgreSQL 14 (one container, both schemas, provisioned through
 * the real phase-0 SQL and the real migration mains' public API — the same precedent cp-core's
 * and dp-core's own harnesses set) — end to end over real HTTP.
 *
 * <p>Letters a–k below are the coordinator's original task list, plus the follow-up wave's items
 * 1–7 folded in at the point in the narrative where each belongs (owner rulings {@code OR-19}
 * through {@code OR-22}: the trigger-written {@code change_audit} trail, the per-address login
 * throttle and its operator recovery, the {@code __Host-} cookie prefix, and the frozen migration
 * history). Each is a self-contained block so a failure names which contractual property broke.
 * The scenario is one long, ordered narrative (login → probe → rotate → probe → logout) because
 * the properties under test are properties of STATE transitions (a session surviving its own
 * rotation, a sibling session being revoked by someone else's rotation, a revoked session
 * answering 401) — splitting it into independent {@code @Test} methods would either duplicate the
 * whole setup dance per method or hide the ordering dependency the task is actually asking to
 * prove. {@link Order} pins execution order for readability and because the per-address lockout
 * test (3) deliberately locks the loopback address every other test in this class shares, so it
 * must run last; {@code (k)}'s context-shape checks do not depend on anything the flow mutates.
 */
@SpringBootTest(
        // HealthFixtureController/HealthResourceResolver are listed explicitly rather than relied
        // on via component-scan pickup: measured, Spring Boot Test installs a TypeExcludeFilter
        // that strips test-package classes out of @SpringBootApplication's own classpath scan
        // specifically so test fixtures never leak into it — so a nested class in this file is
        // invisible to that scan. Passing the classes here instead makes Boot's
        // BeanDefinitionLoader register them directly (any @Component-meta-annotated source class
        // bypasses scanning and its exclude filters), exactly the "import test-only fixtures" the
        // task calls for.
        classes = {SolutionManagerApplication.class, ProductionAuthenticationIT.HealthFixtureController.class,
                ProductionAuthenticationIT.HealthResourceResolver.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionAuthenticationIT {

    private static final String CONSOLE_ORIGIN = "https://console.test";
    private static final String FOREIGN_ORIGIN = "https://evil.test";
    private static final String BOOTSTRAP_USERNAME = "bootstrap-admin";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String CSRF_HEADER = "X-SM-CSRF";
    private static final String NEW_CREDENTIAL = "rotated-credential-0001";

    // ---- OR-21: the __Host- cookie prefix ------------------------------------------------------
    private static final String SESSION_COOKIE = "__Host-SM_SESSION";
    private static final String CSRF_COOKIE = "__Host-SM_CSRF";
    // __Host- requires Secure, Path=/ and no Domain on both cookies — there is no longer a
    // separate "/api"-scoped session-cookie path distinct from the CSRF cookie's "/".
    private static final String COOKIE_PATH = "/";

    /** A caller-supplied address that must never be trusted — sm.gateway.trusted-proxies is empty (OR-20). */
    private static final String SPOOFED_ADDRESS = "203.0.113.77";

    /**
     * The address-dimension threshold ({@code OR-20}) this suite runs with. Raised from the
     * original 6 to accommodate {@link #usernameLockAloneNeverBlocksACorrectCredential} (five more
     * same-address failures, deliberately kept under this threshold — that is the "use a high
     * address threshold for this part" OR-23 requires, since a single shared context cannot bind
     * two different address thresholds) while still low enough for test (4)'s own ramp to trip it
     * without an unreasonable number of requests. Budget across this class's shared loopback
     * address: the lifecycle test's own three incidental failures (unknown-user, wrong-password,
     * wrong-credential-after-rotation) plus the username-lock test's five leaves 8 consumed before
     * test (4) starts its own ramp — comfortably under this threshold.
     */
    private static final int ADDRESS_TEST_THRESHOLD = 15;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Provisioned once, by provision() below, and read by every @Test method. Held only by this
    // test: the application process never receives the migrator credential, the superuser
    // credential, or this owner-level JDBC template — see (k).
    private static NamedParameterJdbcTemplate ownerJdbc;
    private static String bootstrapCredential;
    private static String bootstrapCredentialHash;
    private static UUID bootstrapPrincipalId;
    private static UUID bootstrapOrganizationId;

    // The sm_cp_runtime credential — the ONLY one SmControlLockoutRecoveryMain (test (3)) may use,
    // per its own javadoc. Captured here rather than re-derived, since it is registered as a
    // Spring property under exactly this value.
    private static String runtimeUrl;
    private static String runtimeUsername;
    private static String runtimePassword;

    /**
     * The client address every request in this suite is actually seen from — captured from the
     * FIRST successful login's own audit row (test (1)) rather than assumed, so this suite never
     * hardcodes "127.0.0.1" vs "::1"/"0:0:0:0:0:0:0:1", whichever this JVM resolves "localhost" to.
     */
    private static String loopbackAddress;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /**
     * Runs BEFORE context creation: starts one PostgreSQL 14 container, runs both planes' real
     * phase-0 SQL against it, assigns test passwords to the four login roles, generates the
     * bootstrap Argon2id hash, runs both real migration mains, and registers only the two RUNTIME
     * credentials as the properties the application binds. Neither migrator credential, and
     * neither login role's password beyond the two runtime ones, is ever registered as a Spring
     * property — see (k)'s assertion that the Environment holds no migrator credential.
     */
    @DynamicPropertySource
    static void provisionDatabasesAndRegisterRuntimeCredentials(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:14");
        postgres.start();
        String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + postgres.getDatabaseName();

        DataSource superuser = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        JdbcTemplate superuserJdbc = new JdbcTemplate(superuser);
        runPhase0(superuser, "db/bootstrap/sm_control_phase0.sql");
        runPhase0(superuser, "db/bootstrap/sm_data_phase0.sql");

        String cpMigratorPassword = "cp-migrator-" + UUID.randomUUID();
        String cpRuntimePassword = "cp-runtime-" + UUID.randomUUID();
        String dpMigratorPassword = "dp-migrator-" + UUID.randomUUID();
        String dpRuntimePassword = "dp-runtime-" + UUID.randomUUID();
        superuserJdbc.execute("ALTER ROLE sm_cp_migrator PASSWORD '" + cpMigratorPassword + "'");
        superuserJdbc.execute("ALTER ROLE sm_cp_runtime PASSWORD '" + cpRuntimePassword + "'");
        superuserJdbc.execute("ALTER ROLE sm_dp_migrator PASSWORD '" + dpMigratorPassword + "'");
        superuserJdbc.execute("ALTER ROLE sm_dp_runtime PASSWORD '" + dpRuntimePassword + "'");

        char[] credential = randomCredential();
        bootstrapCredential = new String(credential);
        bootstrapCredentialHash = new CredentialHasher().hash(credential.clone());

        SmControlMigrationMain.run(url, "sm_cp_migrator", cpMigratorPassword, bootstrapCredentialHash);
        SmDataMigrationMain.run(url, "sm_dp_migrator", dpMigratorPassword);

        ownerJdbc = new NamedParameterJdbcTemplate(superuser);
        Map<String, Object> principal = ownerJdbc.queryForMap(
                "SELECT id, organization_id FROM sm_control.principal WHERE username = :username",
                Map.of("username", BOOTSTRAP_USERNAME));
        bootstrapPrincipalId = (UUID) principal.get("id");
        bootstrapOrganizationId = (UUID) principal.get("organization_id");

        runtimeUrl = url;
        runtimeUsername = "sm_cp_runtime";
        runtimePassword = cpRuntimePassword;

        TwoSchemaTestDatabase.registerTestMasterKey(registry);   // plan 010 T2.6: the secret store fails closed
        registry.add("sm.control.runtime.url", () -> url);
        registry.add("sm.control.runtime.username", () -> "sm_cp_runtime");
        registry.add("sm.control.runtime.password", () -> cpRuntimePassword);
        registry.add("sm.data.runtime.url", () -> url);
        registry.add("sm.data.runtime.username", () -> "sm_dp_runtime");
        registry.add("sm.data.runtime.password", () -> dpRuntimePassword);
        registry.add("sm.gateway.trusted-origins[0]", () -> CONSOLE_ORIGIN);
        // sm.gateway.trusted-proxies is deliberately left UNSET (default empty) so test (1)'s
        // X-Forwarded-For probe exercises the true default — OR-20 requires that default to be
        // "trust nothing". B1's startup gate then requires the OTHER half of that explicit choice:
        // this process really is directly exposed in this test (a raw HttpClient talks straight to
        // it, with no proxy in front), so direct-client-connections=true is the truthful answer,
        // not a workaround.
        registry.add("sm.gateway.direct-client-connections", () -> true);
        //
        // OR-20's per-address dimension, deliberately low so test (3) can trip it. Window and
        // lockout are set generously (10 minutes) so real wall-clock time elapsing during this
        // suite's run never causes the window to roll over or the lockout to expire mid-assertion.
        registry.add("sm.control.login-address.failure-threshold", () -> ADDRESS_TEST_THRESHOLD);
        registry.add("sm.control.login-address.window", () -> "10m");
        registry.add("sm.control.login-address.lockout", () -> "10m");
        //
        // OR-24's pre-authentication rate gate is a SEPARATE, earlier-in-the-pipeline dimension
        // from the OR-20/OR-23 throttles this class actually exercises. This suite's shared
        // loopback address makes dozens of login attempts across its four tests; the gate's
        // defaults (capacity 10) would otherwise starve mid-flow and turn an expected
        // 401/200 into a spurious 429. Deliberately generous — OR-24's own gate is proven
        // separately, end to end, by ProductionRateGateAndForwardingIT.
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
    }

    private static void runPhase0(DataSource superuser, String classpathResource) {
        try (Connection connection = superuser.getConnection()) {
            new ResourceDatabasePopulator(new ClassPathResource(classpathResource)).populate(connection);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("phase 0 (" + classpathResource + ") failed", e);
        }
    }

    private static char[] randomCredential() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
    }

    @Test
    @Order(1)
    void productionAuthenticationLifecycle(CapturedOutput output) throws Exception {
        // ---- (a) login: 200, mustRotate=true, cookie shapes, durable session row -------------
        HttpResponse<String> login1 = send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, bootstrapCredential));
        assertEquals(200, login1.statusCode(), login1.body());
        JsonNode login1Body = MAPPER.readTree(login1.body());
        assertTrue(login1Body.get("mustRotate").asBoolean(), "bootstrap admin must start in must-rotate");
        assertCorrelationIdPresent(login1);

        Map<String, ParsedCookie> login1Cookies = parseCookies(login1);
        ParsedCookie sessionCookie = login1Cookies.get(SESSION_COOKIE);
        ParsedCookie csrfCookie = login1Cookies.get(CSRF_COOKIE);
        assertNotNull(sessionCookie, SESSION_COOKIE + " must be set on login");
        assertNotNull(csrfCookie, CSRF_COOKIE + " must be set on login");
        assertTrue(sessionCookie.httpOnly(), SESSION_COOKIE + " must be HttpOnly");
        assertEquals("Strict", sessionCookie.sameSite());
        assertEquals("/", sessionCookie.path(), "OR-21's __Host- prefix requires Path=/");
        assertTrue(sessionCookie.secure(), SESSION_COOKIE + " must be Secure");
        assertNull(sessionCookie.domain(), "OR-21's __Host- prefix forbids a Domain attribute");
        assertFalse(csrfCookie.httpOnly(), CSRF_COOKIE + " must be script-readable");
        assertEquals("Strict", csrfCookie.sameSite());
        assertEquals("/", csrfCookie.path(), "OR-21's __Host- prefix requires Path=/");
        assertTrue(csrfCookie.secure(), CSRF_COOKIE + " must be Secure");
        assertNull(csrfCookie.domain(), "OR-21's __Host- prefix forbids a Domain attribute");
        assertEquals(csrfCookie.value(), login1Body.get("csrfToken").asText(),
                "the " + CSRF_COOKIE + " cookie and the body's csrfToken must be the same value");

        String session1Token = sessionCookie.value();
        String session1Csrf = csrfCookie.value();
        Map<String, String> session1 = Map.of(SESSION_COOKIE, session1Token, CSRF_COOKIE, session1Csrf);

        Map<String, Object> session1Row = sessionRowByToken(session1Token);
        assertNotNull(session1Row.get("session_id"), "a durable sm_control.session row must exist after login");
        assertNull(session1Row.get("revoked_at"), "a fresh session must not be revoked");

        // ---- OR-19: the successful login is audited, joined by correlation id, actor and address.
        // This is also where loopbackAddress is discovered — whatever this JVM resolves the test
        // HTTP client's loopback connection to (127.0.0.1 or an IPv6 form), read back from the row
        // the trigger actually wrote, never assumed.
        UUID login1Cid = correlationIdOf(login1);
        List<String> login1Addresses = ownerJdbc.queryForList(
                "SELECT DISTINCT client_address FROM sm_control.change_audit"
                        + " WHERE audit_operation = :op AND correlation_id = :cid",
                Map.of("op", AuditOperations.LOGIN_SUCCEEDED, "cid", login1Cid), String.class);
        assertEquals(1, login1Addresses.size(),
                () -> "expected exactly one distinct client_address for login1's audit rows, saw " + login1Addresses);
        loopbackAddress = login1Addresses.get(0);
        assertNotNull(loopbackAddress);
        assertTrue(countWhere("audit_operation = :op AND correlation_id = :cid AND actor_principal_id = :actor",
                Map.of("op", AuditOperations.LOGIN_SUCCEEDED, "cid", login1Cid, "actor", bootstrapPrincipalId)) >= 1,
                "the successful login must be audited as LOGIN_SUCCEEDED, attributed to the bootstrap principal");

        // ---- OR-20: X-Forwarded-For is never trusted by default (sm.gateway.trusted-proxies=[]) --
        HttpResponse<String> xffLogin = send(HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", SPOOFED_ADDRESS)
                .POST(HttpRequest.BodyPublishers.ofString(loginBody(BOOTSTRAP_USERNAME, bootstrapCredential)))
                .build());
        assertEquals(200, xffLogin.statusCode(), xffLogin.body());
        String xffLoginSessionToken = parseCookies(xffLogin).get(SESSION_COOKIE).value();
        UUID xffCid = correlationIdOf(xffLogin);
        List<String> xffAddresses = ownerJdbc.queryForList(
                "SELECT DISTINCT client_address FROM sm_control.change_audit"
                        + " WHERE audit_operation = :op AND correlation_id = :cid",
                Map.of("op", AuditOperations.LOGIN_SUCCEEDED, "cid", xffCid), String.class);
        assertEquals(List.of(loopbackAddress), xffAddresses,
                "a spoofed X-Forwarded-For must be ignored with no trusted proxies configured: the audited "
                        + "client_address must be the loopback peer, never " + SPOOFED_ADDRESS);

        // ---- (b) origin / unknown-user / wrong-password ---------------------------------------
        HttpResponse<String> foreignOrigin = send(loginRequest(FOREIGN_ORIGIN, BOOTSTRAP_USERNAME, bootstrapCredential));
        assertProblem(foreignOrigin, 403, "ORIGIN_REJECTED");

        HttpResponse<String> unknownUser = send(loginRequest(CONSOLE_ORIGIN, "no-such-user", "whatever-credential"));
        JsonNode unknownUserBody = assertProblem(unknownUser, 401, "AUTHENTICATION_FAILED");

        HttpResponse<String> wrongPassword =
                send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, "totally-wrong-credential"));
        JsonNode wrongPasswordBody = assertProblem(wrongPassword, 401, "AUTHENTICATION_FAILED");

        assertEquals(withoutCorrelationId(unknownUserBody), withoutCorrelationId(wrongPasswordBody),
                "unknown-user and wrong-password bodies must be byte-identical except correlationId");

        // ---- OR-19: a refused login is audited too, with NULL actor (never reveal who exists) --
        assertTrue(countWhere("audit_operation = :op AND correlation_id = :cid AND actor_principal_id IS NULL",
                Map.of("op", AuditOperations.LOGIN_REFUSED, "cid", correlationIdOf(unknownUser))) >= 1,
                "a refused login for an unknown user must be audited as LOGIN_REFUSED with a NULL actor");

        // ---- (c) GET identity, no CSRF needed, still true while must-rotate -------------------
        HttpResponse<String> identityBeforeRotate = send(identityRequest(session1));
        assertEquals(200, identityBeforeRotate.statusCode());
        assertCorrelationIdPresent(identityBeforeRotate);
        assertTrue(MAPPER.readTree(identityBeforeRotate.body()).get("mustRotate").asBoolean());

        // ---- (d) must-rotate gates a CATALOG operation, pre-rotation --------------------------
        assertEquals(0, HealthFixtureController.INVOCATIONS.get());
        HttpResponse<String> healthBeforeRotate = send(fixtureHealthRequest(session1));
        assertProblem(healthBeforeRotate, 428, "MUST_ROTATE");
        assertEquals(0, HealthFixtureController.INVOCATIONS.get(),
                "the fixture handler body must NOT run when the must-rotate gate refuses the request — "
                        + "authorization/gating happens in AuthenticationInterceptor, before the handler");

        // ---- (e) credential rotation: CSRF ordering, then the atomic rotation -----------------
        String rotateBody = rotateBody(bootstrapCredential, NEW_CREDENTIAL);

        HttpResponse<String> rotateNoCsrf = send(HttpRequest.newBuilder(uri("/api/cp/auth/credential"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .header("Cookie", cookieHeader(session1))
                .POST(HttpRequest.BodyPublishers.ofString(rotateBody))
                .build());
        assertProblem(rotateNoCsrf, 403, "CSRF_REJECTED");

        HttpResponse<String> login2 = send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, bootstrapCredential));
        assertEquals(200, login2.statusCode(), login2.body());
        Map<String, ParsedCookie> login2Cookies = parseCookies(login2);
        String session2Token = login2Cookies.get(SESSION_COOKIE).value();
        String session2Csrf = login2Cookies.get(CSRF_COOKIE).value();
        Map<String, String> session2 = Map.of(SESSION_COOKIE, session2Token, CSRF_COOKIE, session2Csrf);

        // ---- mutation L3: the OLD (pre-OR-21) bare "SM_SESSION" cookie name is never honoured --
        // session2 is still genuinely live at this point, so a 401 here can only be explained by
        // the interceptor looking for the exact cookie name __Host-SM_SESSION and finding nothing.
        HttpResponse<String> oldCookieName = send(HttpRequest.newBuilder(uri("/api/cp/auth/identity"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Cookie", "SM_SESSION=" + session2Token)
                .GET().build());
        assertProblem(oldCookieName, 401, "UNAUTHENTICATED");

        HttpResponse<String> rotateWrongSessionCsrf = send(HttpRequest.newBuilder(uri("/api/cp/auth/credential"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .header("Cookie", cookieHeader(session1))
                .header(CSRF_HEADER, session2Csrf)
                .POST(HttpRequest.BodyPublishers.ofString(rotateBody))
                .build());
        assertProblem(rotateWrongSessionCsrf, 403, "CSRF_REJECTED");

        // ---- OR-19: a wrong-current-credential rotation attempt is refused and audited --------
        HttpResponse<String> wrongCurrentCredentialRotation = send(HttpRequest.newBuilder(uri("/api/cp/auth/credential"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .header("Cookie", cookieHeader(session1))
                .header(CSRF_HEADER, session1Csrf)
                .POST(HttpRequest.BodyPublishers.ofString(rotateBody("not-the-real-current-credential", NEW_CREDENTIAL)))
                .build());
        assertProblem(wrongCurrentCredentialRotation, 400, "CREDENTIAL_ROTATION_REFUSED");
        assertTrue(countWhere("audit_operation = :op AND correlation_id = :cid",
                Map.of("op", AuditOperations.CREDENTIAL_ROTATION_REFUSED,
                        "cid", correlationIdOf(wrongCurrentCredentialRotation))) >= 1,
                "a wrong-current-credential rotation must be audited as CREDENTIAL_ROTATION_REFUSED");

        HttpResponse<String> rotateOk = send(HttpRequest.newBuilder(uri("/api/cp/auth/credential"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .header("Cookie", cookieHeader(session1))
                .header(CSRF_HEADER, session1Csrf)
                .POST(HttpRequest.BodyPublishers.ofString(rotateBody))
                .build());
        assertEquals(204, rotateOk.statusCode(), rotateOk.body());
        assertCorrelationIdPresent(rotateOk);

        // ---- OR-19: the successful rotation is audited as a principal UPDATE, credential_hash
        // named in secret_columns_changed (never its value — see the DB-wide secret check below).
        assertEquals(1, countWhere(
                "table_name = 'principal' AND operation = 'UPDATE' AND audit_operation = :op"
                        + " AND 'credential_hash' = ANY(secret_columns_changed) AND correlation_id = :cid",
                Map.of("op", AuditOperations.CREDENTIAL_ROTATED, "cid", correlationIdOf(rotateOk))),
                "the successful rotation must be audited as exactly one principal UPDATE naming "
                        + "credential_hash in secret_columns_changed");

        HttpResponse<String> session2AfterRotate = send(identityRequest(session2));
        assertProblem(session2AfterRotate, 401, "UNAUTHENTICATED");

        HttpResponse<String> session1AfterRotate = send(identityRequest(session1));
        assertEquals(200, session1AfterRotate.statusCode());
        assertFalse(MAPPER.readTree(session1AfterRotate.body()).get("mustRotate").asBoolean(),
                "the rotating session's own identity must show mustRotate=false immediately");

        HttpResponse<String> oldCredentialLogin =
                send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, bootstrapCredential));
        assertProblem(oldCredentialLogin, 401, "AUTHENTICATION_FAILED");

        HttpResponse<String> newCredentialLogin =
                send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, NEW_CREDENTIAL));
        assertEquals(200, newCredentialLogin.statusCode(), newCredentialLogin.body());
        assertFalse(MAPPER.readTree(newCredentialLogin.body()).get("mustRotate").asBoolean());
        String newCredentialLoginSessionToken = parseCookies(newCredentialLogin).get(SESSION_COOKIE).value();

        Map<String, Object> principalAfterRotate = ownerJdbc.queryForMap(
                "SELECT credential_hash, must_rotate FROM sm_control.principal WHERE id = :id",
                Map.of("id", bootstrapPrincipalId));
        String rotatedCredentialHash = (String) principalAfterRotate.get("credential_hash");
        assertFalse((Boolean) principalAfterRotate.get("must_rotate"), "must_rotate must be cleared in the DB");
        assertNotEquals(bootstrapCredentialHash, rotatedCredentialHash,
                "the stored credential_hash must change on rotation");

        // ---- (d) continued: post-rotation, the same session is now ALLOWED --------------------
        HttpResponse<String> healthAfterRotate = send(fixtureHealthRequest(session1));
        assertEquals(200, healthAfterRotate.statusCode(), healthAfterRotate.body());
        assertCorrelationIdPresent(healthAfterRotate);
        assertEquals(1, HealthFixtureController.INVOCATIONS.get(),
                "the fixture handler body must run exactly once, only once authorized post-rotation");

        // ---- (f) logout: cookies expired, session unusable, revoked_at set --------------------
        HttpResponse<String> logout = send(HttpRequest.newBuilder(uri("/api/cp/auth/logout"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Cookie", cookieHeader(session1))
                .header(CSRF_HEADER, session1Csrf)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertEquals(204, logout.statusCode());
        Map<String, ParsedCookie> logoutCookies = parseCookies(logout);
        assertEquals(0L, logoutCookies.get(SESSION_COOKIE).maxAge(), SESSION_COOKIE + " must be expired on logout");
        assertEquals(0L, logoutCookies.get(CSRF_COOKIE).maxAge(), CSRF_COOKIE + " must be expired on logout");
        // The expiry cookie must be re-set at the SAME Path/attributes the browser holds the live
        // cookie under, or the browser keeps the original alongside an unrelated expired one.
        assertEquals("/", logoutCookies.get(SESSION_COOKIE).path(), SESSION_COOKIE + " path must remain / on logout");
        assertEquals("/", logoutCookies.get(CSRF_COOKIE).path(), CSRF_COOKIE + " path must remain / on logout");
        assertNull(logoutCookies.get(SESSION_COOKIE).domain(), "the expired " + SESSION_COOKIE + " must carry no Domain");
        assertNull(logoutCookies.get(CSRF_COOKIE).domain(), "the expired " + CSRF_COOKIE + " must carry no Domain");

        HttpResponse<String> identityAfterLogout = send(identityRequest(session1));
        assertProblem(identityAfterLogout, 401, "UNAUTHENTICATED");

        Map<String, Object> session1RowAfterLogout = sessionRowByToken(session1Token);
        assertNotNull(session1RowAfterLogout.get("revoked_at"), "logout must set revoked_at in the DB");

        // ---- OR-19: the logout is audited as a session UPDATE, attributed to its own principal -
        assertTrue(countWhere("table_name = 'session' AND operation = 'UPDATE' AND audit_operation = :op"
                        + " AND correlation_id = :cid AND actor_principal_id = :actor",
                Map.of("op", AuditOperations.LOGOUT, "cid", correlationIdOf(logout), "actor", bootstrapPrincipalId)) >= 1,
                "logout must be audited as a LOGOUT session row, attributed to the session's own principal");

        // ---- (g) unauthenticated logout, no CSRF: 401, proving session precedes CSRF ----------
        HttpResponse<String> unauthenticatedLogout = send(HttpRequest.newBuilder(uri("/api/cp/auth/logout"))
                .header("Origin", CONSOLE_ORIGIN)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertProblem(unauthenticatedLogout, 401, "UNAUTHENTICATED");

        // ---- (h) unknown route: 404 NOT_FOUND under the real MVC setup, no null keys -----------
        HttpResponse<String> noRoute = send(HttpRequest.newBuilder(uri("/api/nope"))
                .header("Origin", CONSOLE_ORIGIN)
                .GET().build());
        assertProblem(noRoute, 404, "NOT_FOUND");

        // ---- (i) a caller-supplied X-Correlation-Id is never echoed ----------------------------
        HttpResponse<String> callerSuppliedCorrelation = send(HttpRequest.newBuilder(uri("/api/nope"))
                .header("Origin", CONSOLE_ORIGIN)
                .header(CORRELATION_HEADER, "caller-value")
                .GET().build());
        String serverCorrelationId = assertCorrelationIdPresent(callerSuppliedCorrelation);
        assertNotEquals("caller-value", serverCorrelationId);

        // ---- OR-19: no identity GET ever wrote a session-table audit row (the idle touch is
        // excluded by the trigger itself — see V011). Checked by correlation id rather than by a
        // hand-derived total row count, since several unrelated writes legitimately share other
        // correlation ids in this flow.
        List<UUID> identityGetCorrelationIds = List.of(correlationIdOf(identityBeforeRotate),
                correlationIdOf(session2AfterRotate), correlationIdOf(session1AfterRotate),
                correlationIdOf(identityAfterLogout));
        assertEquals(0, countWhere("table_name = 'session' AND correlation_id IN (:cids)",
                Map.of("cids", identityGetCorrelationIds)),
                "an identity GET must never produce a session-table audit row (the idle touch is excluded)");

        // ---- (j) nothing sensitive was ever printed, across the whole flow ---------------------
        String printed = output.getAll();
        assertFalse(printed.contains(bootstrapCredential), "the bootstrap credential must never be logged");
        assertFalse(printed.contains(NEW_CREDENTIAL), "the new credential must never be logged");
        assertFalse(printed.contains(session1Token), SESSION_COOKIE + " (session1) must never be logged");
        assertFalse(printed.contains(session2Token), SESSION_COOKIE + " (session2) must never be logged");
        assertFalse(printed.contains(session1Csrf), CSRF_COOKIE + " (session1) must never be logged");
        assertFalse(printed.contains(session2Csrf), CSRF_COOKIE + " (session2) must never be logged");
        assertFalse(printed.contains(bootstrapCredentialHash), "the original credential_hash must never be logged");
        assertFalse(printed.contains(rotatedCredentialHash), "the rotated credential_hash must never be logged");

        // ---- M4: the DB stores only sha256Hex(token) as token_hash, never the raw session token —
        // so the needle list above catches a leaked RAW token but would miss a leaked token_hash
        // entirely. Every session this flow actually issued gets its hash added as its own needle
        // too. Each is asserted non-blank and different from its raw token first, so this list is
        // provably checking real hash values, not accidentally checking nothing.
        List<String> issuedSessionTokens = List.of(session1Token, xffLoginSessionToken, session2Token,
                newCredentialLoginSessionToken);
        List<String> tokenHashNeedles = new ArrayList<>();
        for (String rawToken : issuedSessionTokens) {
            String hash = sha256Hex(rawToken);
            assertFalse(hash.isBlank(), "a session token hash needle must not be blank");
            assertNotEquals(rawToken, hash, "a session token hash needle must differ from its raw token");
            tokenHashNeedles.add(hash);
        }

        // ---- OR-19: the same secrets never appear anywhere in change_audit's own text ----------
        // (old_values/new_values already drop the credential_hash/token_hash KEYS entirely — this
        // checks the actual secret STRING VALUES never appear as a substring anywhere else either:
        // row_key, client_address, or the column-name arrays.)
        List<String> needles = new ArrayList<>(List.of(bootstrapCredentialHash, rotatedCredentialHash, session1Token,
                session2Token, session1Csrf, session2Csrf));
        needles.addAll(tokenHashNeedles);
        for (String needle : needles) {
            assertEquals(0, countNeedleInAudit(needle),
                    () -> "a secret value must never appear anywhere in sm_control.change_audit's text columns");
        }
    }

    /**
     * {@code OR-23}: "a correct credential is never locked out by other people's failures" also
     * means the credential holder's OWN prior failures never lock them out of a correct guess,
     * because the USERNAME dimension is an alarm, never a gate — only the ADDRESS dimension is
     * read at login. Locks {@code BOOTSTRAP_USERNAME} itself via five wrong-credential attempts
     * (reaching {@code LoginRateLimitPolicy.LOCAL_DEFAULT}'s threshold of 5) from the shared
     * loopback address, confirms the throttle row is actually locked, then proves the CORRECT
     * (rotated) credential still succeeds — from that same, still-unlocked-at-the-ADDRESS-level
     * address — and clears the username counter as a side effect of that success.
     *
     * <p>Runs after the lifecycle test (needs its rotated {@code NEW_CREDENTIAL} and
     * {@code loopbackAddress}) and before the address-lockout test (which budgets its own ramp
     * against {@link #ADDRESS_TEST_THRESHOLD} assuming this test's five address-dimension
     * failures already happened).
     */
    @Test
    @Order(2)
    void usernameLockAloneNeverBlocksACorrectCredential() throws Exception {
        assertNotNull(loopbackAddress, "loopbackAddress must already have been captured by the lifecycle test");
        String usernameSubjectHash = LocalLoginService.principalSubjectHash(bootstrapPrincipalId);

        for (int i = 0; i < 5; i++) {
            HttpResponse<String> wrong =
                    send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, "wrong-guess-" + i + "-" + UUID.randomUUID()));
            assertProblem(wrong, 401, "AUTHENTICATION_FAILED");
        }

        Map<String, Object> lockedRow = ownerJdbc.queryForMap("""
                SELECT failure_count, locked_until FROM sm_control.login_throttle WHERE subject_hash = :hash
                """, Map.of("hash", usernameSubjectHash));
        assertEquals(5, ((Number) lockedRow.get("failure_count")).intValue(),
                "five wrong-credential attempts must have reached the username threshold");
        assertNotNull(lockedRow.get("locked_until"), "the username dimension must now be locked");

        // The username IS locked — but OR-23 never reads that dimension at login, so the correct
        // (rotated) credential from this same, still-unlocked-at-the-ADDRESS-level address succeeds.
        HttpResponse<String> correct = send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, NEW_CREDENTIAL));
        assertEquals(200, correct.statusCode(), correct.body());

        Map<String, Object> clearedRow = ownerJdbc.queryForMap("""
                SELECT failure_count, locked_until FROM sm_control.login_throttle WHERE subject_hash = :hash
                """, Map.of("hash", usernameSubjectHash));
        assertEquals(0, ((Number) clearedRow.get("failure_count")).intValue(),
                "a successful login must clear the username counter (OR-23)");
        assertNull(clearedRow.get("locked_until"));
    }

    /**
     * (k) The running context carries no demo composition, no Flyway bean, no migrator credential
     * in the Environment, and exposes exactly the four auth routes plus this test's own fixture.
     * Independent of the lifecycle flow above — safe to run before or after it.
     */
    @Test
    @Order(3)
    void productionContextCarriesNoDemoNoMigratorAndNoExtraRoute() throws ClassNotFoundException {
        for (String beanName : context.getBeanDefinitionNames()) {
            Class<?> type = context.getType(beanName);
            if (type != null) {
                assertFalse(type.getName().startsWith("com.solace.sm.demo."),
                        () -> "bean '" + beanName + "' of type " + type + " belongs to the demo composition");
            }
        }

        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.solace.sm.demo.DemoApplication"),
                "the demo-launcher's package must not be reachable from the production launcher's classpath");

        assertEquals(0, context.getBeanNamesForType(org.flywaydb.core.Flyway.class).length,
                "no Flyway bean may exist in the production context");

        // GatewayErrorAdvice is a @RestControllerAdvice the launcher's component scan picks up;
        // GatewayWebConfiguration must NOT also declare it as a @Bean, or two advice instances
        // compete to render the same exception.
        assertEquals(1, context.getBeanNamesForType(com.solace.sm.gateway.http.GatewayErrorAdvice.class).length,
                "exactly one GatewayErrorAdvice bean must exist");

        // S1 review: the unit tests wire DesignerConfiguration alone, and nothing in the launcher
        // consumes the designer ports yet, so a production boot would not notice one missing or
        // duplicated. Count them in the booted context (the reach port is also proven by every
        // HierarchyEndpointsIT request, which fails without it).
        assertEquals(1, context.getBeanNamesForType(com.solace.sm.cp.api.designer.DesignerProjector.class).length,
                "exactly one DesignerProjector bean must exist");
        assertEquals(1, context.getBeanNamesForType(com.solace.sm.cp.api.designer.DesignerStageValidator.class).length,
                "exactly one DesignerStageValidator bean must exist");
        assertEquals(1, context.getBeanNamesForType(com.solace.sm.cp.api.authz.ReachAuthorization.class).length,
                "exactly one ReachAuthorization bean must exist");
        // plan 010 T10.4: accept-runtime's data-plane half, over the production adapter, exactly once
        assertEquals(1, context.getBeanNamesForType(com.solace.sm.dp.api.readback.RuntimeReadBackService.class).length,
                "exactly one RuntimeReadBackService bean must exist");
        assertEquals(1, context.getBeanNamesForType(com.solace.sm.dp.api.secret.SecretCompare.class).length,
                "exactly one compare-only secret port must exist");

        assertNull(environment.getProperty("SM_CONTROL_MIGRATOR_URL"));
        assertNull(environment.getProperty("SM_CONTROL_MIGRATOR_USERNAME"));
        assertNull(environment.getProperty("SM_CONTROL_MIGRATOR_PASSWORD"));
        assertNull(environment.getProperty("SM_CONTROL_BOOTSTRAP_CREDENTIAL_HASH"));
        assertNull(environment.getProperty("SM_DATA_MIGRATOR_URL"));
        assertNull(environment.getProperty("SM_DATA_MIGRATOR_USERNAME"));
        assertNull(environment.getProperty("SM_DATA_MIGRATOR_PASSWORD"));

        Set<String> operations = handlerMapping.getHandlerMethods().values().stream()
                .map(handlerMethod -> AnnotatedElementUtils
                        .findMergedAnnotation(handlerMethod.getMethod(), ConsoleOperation.class))
                .map(ConsoleOperation::value)
                .collect(Collectors.toSet());
        assertEquals(Set.of("login", "logout", "getIdentity", "rotateOwnCredential", "getHealth",
                "listOrganizations", "listSubOrganizations", "listEnvironments",
                "listSolutions", "loadPackage", "listTargets", "declareInstance", "listInstances", "getInstance",
                // plan 008 S3 (BE-D47, BE-D53): the two placement operations
                "addPlacement", "retirePlacement",
                // plan 012 T5: undeclare (U-C; the host is never touched)
                "undeclareInstance",
                // plan 013 T1.4: the registration API's three target operations
                "scanHostKeys", "registerTarget", "editTarget", "getTargetConnection",
                // plan 013 T1.5: the four operations on an existing target
                "editTargetConnection", "rotateTargetCredential", "testTargetConnection", "retireTarget",
                // plan 015 T4.3 (D10 rows 48-51 since OR-147): the five agent operations
                "issueAgentEnrolment", "listAgents", "getAgent", "revokeAgent", "drainAgent",
                // UAT (OR-147 union): plan 013/014's six Connections operations (D10 rows 44-47) and D-4's package fetch
                "listConnections", "getConnectionTypes", "createConnection", "updateConnection", "deactivateConnection",
                "deleteConnection", "getSolutionPackage",
                "getDesigner", "stageConfig", "getConfigHistory", "getConfigLadder", "setConfigSecret",
                "submitApply", "observeApply", "stopInstance", "readLogs", "exportLogs", "getLogLevel", "setLogLevel",
                // plan 017 task 6: an agent placement's allow-listed config view (config.view, no new verb)
                "viewConfig",
                // plan 012 T7 (OPS-Q9 Sch-2): the schedule list and cancel (creating one is submitApply/rollbackApply)
                "listApplySchedules", "cancelApplySchedule",
                // plan 013 T2.3: the five hierarchy writes and the two entitlement operations
                "createSubOrganization", "createEnvironment", "renameOrganization", "renameSubOrganization",
                "renameEnvironment", "listEntitlements", "setEntitlement",
                // plan 013 T3.2: the local users and the role assignments (eight; one verb each)
                "listUsers", "createUser", "deactivateUser", "resetUserCredential", "listRoles", "listAssignments",
                "grantAssignment", "revokeAssignment",
                "acceptRuntimeConfig", "rollbackApply", "restoreRuntimeConfig", "getHomeSummary", "getHomeAttention",
                "submitFleetApply", "observeFleetApply",
                // plan 011 P11-7.2: the fourteen /api/console/v1 monitoring operations (rows 53-66)
                "getCapabilities", "listEntityTypes", "listMetricInstances", "getMetricsCatalog", "getLabelValues",
                "queryRange", "queryInstant", "exportRange", "declareMetricType", "getDashboard", "saveWidget",
                "setDashboardLayout", "setWidgetHidden", "resetDashboard"), operations);
    }

    /**
     * (4) OR-20's per-address dimension end to end: enough failing logins with DIFFERENT unknown
     * usernames from the SAME (loopback) address trips the address lockout — never the per-username
     * one, which stays at failure_count=1 for each distinct probe username, nowhere near its own
     * (untouched, default) threshold. Runs LAST and locks the suite's own shared loopback address;
     * every earlier test in this class already ran by the time this executes.
     *
     * <p>The lifecycle test leaves the address dimension at 3 failures (unknown-user, wrong-password,
     * wrong-credential-after-rotation) and {@link #usernameLockAloneNeverBlocksACorrectCredential}
     * adds 5 more (its five same-address wrong-credential attempts) — 8 total, below
     * {@link #ADDRESS_TEST_THRESHOLD} (15), which is why those earlier steps never trip this
     * lockout themselves. This test queries the actual current failure count rather than assuming
     * that number, so it stays correct if the earlier flows ever change.
     *
     * <p>{@code OR-24}: once locked, a refusal against this SAME address is audited at most once
     * per lockout, not once per request — proven by sending three more refused requests after the
     * first post-lock refusal and asserting only that first one's correlation id produced a row.
     */
    @Test
    @Order(4)
    void perAddressLockoutLocksAndRecovers() throws Exception {
        assertNotNull(loopbackAddress, "loopbackAddress must already have been captured by the lifecycle test");
        String addressSubjectHash = LocalLoginService.addressSubjectHash(loopbackAddress);

        Integer existingFailures = ownerJdbc.queryForObject("""
                SELECT COALESCE((SELECT failure_count FROM sm_control.login_throttle WHERE subject_hash = :hash), 0)
                """, Map.of("hash", addressSubjectHash), Integer.class);
        assertNotNull(existingFailures);
        assertTrue(existingFailures < ADDRESS_TEST_THRESHOLD,
                () -> "the lifecycle flow already consumed the whole address-lockout budget (" + existingFailures
                        + "/" + ADDRESS_TEST_THRESHOLD + ") — this test can no longer trip it itself");

        for (int i = existingFailures; i < ADDRESS_TEST_THRESHOLD; i++) {
            HttpResponse<String> probe = send(loginRequest(CONSOLE_ORIGIN,
                    "lockout-probe-" + UUID.randomUUID() + "@example.test", "whatever-credential"));
            assertProblem(probe, 401, "AUTHENTICATION_FAILED");
        }

        // The address is now locked: even the rotated administrator's CORRECT credential is refused.
        HttpResponse<String> lockedOut = send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, NEW_CREDENTIAL));
        assertProblem(lockedOut, 401, "AUTHENTICATION_FAILED");
        UUID firstPostLockCorrelationId = correlationIdOf(lockedOut);

        // OR-24: N further refusals against the SAME still-locked address must leave the audit
        // trail untouched — the trigger only ever fires for the FIRST refusal per lockout.
        List<UUID> laterPostLockCorrelationIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> stillLocked = send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, NEW_CREDENTIAL));
            assertProblem(stillLocked, 401, "AUTHENTICATION_FAILED");
            laterPostLockCorrelationIds.add(correlationIdOf(stillLocked));
        }

        assertEquals(1, countWhere("table_name = 'login_throttle' AND audit_operation = :op AND correlation_id = :cid",
                Map.of("op", AuditOperations.LOGIN_REFUSED, "cid", firstPostLockCorrelationId)),
                "the FIRST refusal against a newly-locked address must be audited exactly once");
        for (UUID laterCid : laterPostLockCorrelationIds) {
            assertEquals(0, countWhere("table_name = 'login_throttle' AND audit_operation = :op AND correlation_id = :cid",
                    Map.of("op", AuditOperations.LOGIN_REFUSED, "cid", laterCid)),
                    "a LATER refusal against the SAME still-active lockout must add no further row (OR-24)");
        }

        boolean cleared = SmControlLockoutRecoveryMain.run(runtimeUrl, runtimeUsername, runtimePassword, addressSubjectHash);
        assertTrue(cleared, "the address lockout must have existed and been cleared by the recovery command");

        HttpResponse<String> recovered = send(loginRequest(CONSOLE_ORIGIN, BOOTSTRAP_USERNAME, NEW_CREDENTIAL));
        assertEquals(200, recovered.statusCode(), recovered.body());

        assertEquals(1, countWhere(
                "audit_operation = :op AND actor_principal_id IS NULL AND client_address = 'operator-cli'"
                        + " AND row_key ->> 'subject_hash' = :hash",
                Map.of("op", AuditOperations.LOCKOUT_CLEARED, "hash", addressSubjectHash)),
                "the recovery command's clearing must be audited exactly once as LOCKOUT_CLEARED");
    }

    // ---- HTTP helpers -----------------------------------------------------------------------

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private URI uri(String path) {
        return URI.create(baseUrl() + path);
    }

    private HttpRequest loginRequest(String origin, String username, String credential) {
        return HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", origin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody(username, credential)))
                .build();
    }

    private HttpRequest identityRequest(Map<String, String> cookies) {
        return HttpRequest.newBuilder(uri("/api/cp/auth/identity"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Cookie", cookieHeader(cookies))
                .GET().build();
    }

    private HttpRequest fixtureHealthRequest(Map<String, String> cookies) {
        return HttpRequest.newBuilder(uri(HealthFixtureController.PATH))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Cookie", cookieHeader(cookies))
                .GET().build();
    }

    /**
     * Sends the request and asserts the two properties every response in this suite must carry: a
     * fresh correlation id, and — per the upstream wave — that it is never cacheable.
     */
    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertCorrelationIdPresent(response);
        assertNeverCached(response);
        return response;
    }

    private static String assertCorrelationIdPresent(HttpResponse<String> response) {
        var values = response.headers().allValues(CORRELATION_HEADER);
        assertEquals(1, values.size(), () -> "expected exactly one " + CORRELATION_HEADER + " header, saw " + values);
        String value = values.get(0);
        assertDoesNotThrow(() -> UUID.fromString(value), () -> CORRELATION_HEADER + " was not a UUID: " + value);
        return value;
    }

    private static UUID correlationIdOf(HttpResponse<String> response) {
        return UUID.fromString(assertCorrelationIdPresent(response));
    }

    /**
     * Every response — success, error, an unmapped route, a CSRF/origin rejection — must carry
     * {@code Cache-Control: no-store} and {@code Pragma: no-cache}: {@code CorrelationIdFilter}
     * sets both at {@code HIGHEST_PRECEDENCE}, before anything downstream (including a later
     * filter's own failure) runs, so this holds for every response this suite ever sees.
     */
    private static void assertNeverCached(HttpResponse<String> response) {
        assertEquals(List.of("no-store"), response.headers().allValues("Cache-Control"),
                () -> "Cache-Control must be exactly no-store on every response: " + response.uri());
        assertEquals(List.of("no-cache"), response.headers().allValues("Pragma"),
                () -> "Pragma must be exactly no-cache on every response: " + response.uri());
    }

    /** Asserts the {@code Problem} shape, the expected status/code, and that correlationId matches the header. */
    private static JsonNode assertProblem(HttpResponse<String> response, int status, String code)
            throws IOException {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = MAPPER.readTree(response.body());
        assertEquals("about:blank", body.get("type").asText());
        assertEquals(status, body.get("status").asInt());
        assertEquals(code, body.get("code").asText());
        assertFalse(body.has("detail"), () -> "detail must be OMITTED, not null: " + body);
        assertFalse(body.has("instance"), () -> "instance must be OMITTED, not null: " + body);
        String headerCorrelationId = response.headers().allValues(CORRELATION_HEADER).get(0);
        assertEquals(headerCorrelationId, body.get("correlationId").asText(),
                "the Problem body's correlationId must equal the X-Correlation-Id header");
        return body;
    }

    private static JsonNode withoutCorrelationId(JsonNode node) {
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        copy.remove("correlationId");
        return copy;
    }

    private static String loginBody(String username, String credential) {
        return "{\"organization\":\"" + bootstrapOrganizationId + "\",\"username\":\"" + username
                + "\",\"credential\":\"" + credential + "\"}";
    }

    private static String rotateBody(String current, String replacement) {
        return "{\"currentCredential\":\"" + current + "\",\"newCredential\":\"" + replacement + "\"}";
    }

    private static String cookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("; "));
    }

    // ---- cookie parsing -----------------------------------------------------------------------

    private record ParsedCookie(String name, String value, boolean httpOnly, boolean secure, String sameSite,
                                String path, String domain, Long maxAge) {
    }

    private static Map<String, ParsedCookie> parseCookies(HttpResponse<String> response) {
        Map<String, ParsedCookie> out = new LinkedHashMap<>();
        for (String header : response.headers().allValues("Set-Cookie")) {
            String[] parts = header.split(";");
            String[] nameValue = parts[0].split("=", 2);
            String name = nameValue[0].trim();
            String value = nameValue.length > 1 ? nameValue[1].trim() : "";
            boolean httpOnly = false;
            boolean secure = false;
            String sameSite = null;
            String path = null;
            String domain = null;
            Long maxAge = null;
            for (int i = 1; i < parts.length; i++) {
                String attribute = parts[i].trim();
                String lower = attribute.toLowerCase(java.util.Locale.ROOT);
                if (lower.equals("httponly")) {
                    httpOnly = true;
                } else if (lower.equals("secure")) {
                    secure = true;
                } else if (lower.startsWith("samesite=")) {
                    sameSite = attribute.substring("samesite=".length());
                } else if (lower.startsWith("path=")) {
                    path = attribute.substring("path=".length());
                } else if (lower.startsWith("domain=")) {
                    domain = attribute.substring("domain=".length());
                } else if (lower.startsWith("max-age=")) {
                    maxAge = Long.parseLong(attribute.substring("max-age=".length()));
                }
            }
            out.put(name, new ParsedCookie(name, value, httpOnly, secure, sameSite, path, domain, maxAge));
        }
        return out;
    }

    // ---- DB assertions, over a connection held only by this test -------------------------------

    private static Map<String, Object> sessionRowByToken(String bearerToken) {
        return ownerJdbc.queryForMap(
                "SELECT session_id, revoked_at FROM sm_control.session WHERE token_hash = :hash",
                Map.of("hash", sha256Hex(bearerToken)));
    }

    /**
     * A {@code count(*)} over {@code sm_control.change_audit} for a caller-supplied, compile-time
     * literal {@code WHERE} clause — never derived from request/user data ({@code I5}: every
     * actual VALUE still travels as a bind parameter in {@code params}).
     */
    private static int countWhere(String whereClauseLiteral, Map<String, ?> params) {
        Integer count = ownerJdbc.queryForObject(
                "SELECT count(*) FROM sm_control.change_audit WHERE " + whereClauseLiteral, params, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * True if {@code needle} (a credential hash, session token, or CSRF value) appears anywhere in
     * {@code sm_control.change_audit}'s textual columns. Uses {@code position()} rather than
     * {@code LIKE}/{@code ILIKE} so a base64url {@code '_'} in the needle is never mistaken for a
     * wildcard.
     */
    private static int countNeedleInAudit(String needle) {
        Integer count = ownerJdbc.queryForObject("""
                SELECT count(*) FROM sm_control.change_audit
                 WHERE position(:needle IN old_values::text) > 0
                    OR position(:needle IN new_values::text) > 0
                    OR position(:needle IN row_key::text) > 0
                    OR position(:needle IN client_address) > 0
                    OR position(:needle IN array_to_string(changed_columns, ',')) > 0
                    OR position(:needle IN array_to_string(secret_columns_changed, ',')) > 0
                """, Map.of("needle", needle), Integer.class);
        return count == null ? 0 : count;
    }

    /** Exactly {@code SessionService}'s own hashing, so a client-visible token can be matched to its row. */
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * TEST-ONLY. One CATALOG-classified endpoint, registered explicitly via this class's
     * {@code @SpringBootTest(classes = ...)} list (see that annotation's comment for why a plain
     * component-scan pickup does not work under {@code @SpringBootTest}) — nothing here is
     * reachable from production, since this class exists only in this module's test sources.
     * Proves (d): the must-rotate gate blocks a real catalog operation pre-rotation (and the
     * handler body never runs — {@link #INVOCATIONS}), and the real
     * {@code ScopeEvaluatingAuthorizationService} — invoked by {@code AuthenticationInterceptor}
     * itself, via {@link HealthResourceResolver}, BEFORE this handler is ever reached — allows it
     * post-rotation for the seeded Administrator, resolved against the bootstrap organization row
     * {@code V007__bootstrap_admin.sql} already seeds. No additional fixture rows are needed, and
     * this class no longer depends on {@code RequestAuthorizer} — that type was removed; mandatory
     * pre-handler authorization now lives entirely in {@code AuthenticationInterceptor}.
     */
    @RestController
    static class HealthFixtureController {

        static final String PATH = "/api/fixture/health";

        /** Counts actual invocations of {@link #getHealth}, so a 428/403 refusal can be proven to never reach it. */
        static final AtomicInteger INVOCATIONS = new AtomicInteger();

        @GetMapping(PATH)
        @ConsoleOperation(value = "getHealth", resource = HealthResourceResolver.class)
        ResponseEntity<Void> getHealth(Session session) {
            INVOCATIONS.incrementAndGet();
            return ResponseEntity.ok().build();
        }
    }

    /**
     * TEST-ONLY {@link ResourceResolver} for {@link HealthFixtureController}. Registered as a
     * {@code @Component} bean via this class's explicit {@code @SpringBootTest(classes = ...)}
     * list — a bare class with no such annotation is silently skipped by Boot's
     * {@code BeanDefinitionLoader} when passed as a primary source, so this needs the annotation
     * even though it is never component-scanned in production.
     *
     * <p>Resolves to the bootstrap organization every time: the seeded Administrator role is
     * bound at the organization level, so this is exactly the resource
     * {@code ScopeEvaluatingAuthorizationService} must authorize the fixture request against.
     */
    @Component
    static class HealthResourceResolver implements ResourceResolver {

        @Override
        public ResourceRef resolve(HttpServletRequest request, Session session) {
            return ResourceRef.of(ResourceKind.ORGANIZATION, bootstrapOrganizationId, new TenantId(bootstrapOrganizationId));
        }
    }
}
