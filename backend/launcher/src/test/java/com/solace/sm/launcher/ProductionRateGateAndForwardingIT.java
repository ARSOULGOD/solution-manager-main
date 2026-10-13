package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.persistence.SmControlMigrationMain;
import com.solace.sm.dp.apply.persistence.SmDataMigrationMain;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A SEPARATE Spring context from {@link ProductionAuthenticationIT} — deliberately: that class's
 * gate properties are set generously so its own OR-20/OR-23 flows are never gated (dozens of login
 * attempts from one shared address), which makes it unusable for proving the gate itself. This
 * class does the opposite: a deliberately tiny {@code sm.gateway.auth-rate.capacity} (3), spent in
 * a fixed order across its two tests, so each proves one property without the other's requests
 * exhausting its budget first.
 *
 * <p>Both tests connect to the real embedded server over real HTTP via the literal IPv4 loopback
 * address {@code 127.0.0.1} — never the hostname {@code localhost}, whose resolution to IPv4 vs
 * IPv6 is JVM/OS-dependent — so {@code sm.gateway.trusted-proxies} can name that ONE literal,
 * canonical address deterministically, with {@code sm.gateway.direct-client-connections} left at
 * its default (unset): this process is declared to sit behind that one proxy address, which is
 * exactly {@code java.net.http.HttpClient}'s own real socket peer here.
 *
 * <p>Order: {@link #lastForwardedForFieldWinsAndIsAudited} (Order 1) spends exactly ONE token of
 * the 3-capacity budget; {@link #exceedingTheGateYieldsRateLimitedAndTouchesNoCpCoreState} (Order
 * 2) spends the remaining two on requests that must reach cp-core, then a third that must not.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProductionRateGateAndForwardingIT {

    private static final String CONSOLE_ORIGIN = "https://console.test";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    /** The literal IPv4 loopback address — see the class javadoc for why never "localhost". */
    private static final String LOOPBACK = "127.0.0.1";

    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static NamedParameterJdbcTemplate ownerJdbc;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;

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
        String bootstrapCredentialHash = new CredentialHasher().hash(credential.clone());

        SmControlMigrationMain.run(url, "sm_cp_migrator", cpMigratorPassword, bootstrapCredentialHash);
        SmDataMigrationMain.run(url, "sm_dp_migrator", dpMigratorPassword);

        ownerJdbc = new NamedParameterJdbcTemplate(superuser);

        TwoSchemaTestDatabase.registerTestMasterKey(registry);   // plan 010 T2.6: the secret store fails closed
        registry.add("sm.control.runtime.url", () -> url);
        registry.add("sm.control.runtime.username", () -> "sm_cp_runtime");
        registry.add("sm.control.runtime.password", () -> cpRuntimePassword);
        registry.add("sm.data.runtime.url", () -> url);
        registry.add("sm.data.runtime.username", () -> "sm_dp_runtime");
        registry.add("sm.data.runtime.password", () -> dpRuntimePassword);
        registry.add("sm.gateway.trusted-origins[0]", () -> CONSOLE_ORIGIN);
        // The one real socket peer HttpClient ever presents here — see the class javadoc.
        registry.add("sm.gateway.trusted-proxies[0]", () -> LOOPBACK);
        // sm.gateway.direct-client-connections is deliberately left UNSET: trusted-proxies is
        // non-empty, which is the OTHER half of B1's explicit choice.
        registry.add("sm.gateway.auth-rate.capacity", () -> 3);
        // One token a minute: a failed login costs ~2s, so at 10/min (one per 6s) the bucket refilled
        // between the third and fourth request and the "fourth" was admitted — a timing flake.
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 1);
    }

    private static void runPhase0(DataSource superuser, String classpathResource) {
        try (Connection connection = superuser.getConnection()) {
            new ResourceDatabasePopulator(
                    new org.springframework.core.io.ClassPathResource(classpathResource)).populate(connection);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("phase 0 (" + classpathResource + ") failed", e);
        }
    }

    private static char[] randomCredential() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray();
    }

    /**
     * External-review proof, end to end: TWO separate {@code X-Forwarded-For} header FIELDS (not
     * comma-joined entries within one field) — an attacker-controlled value first, a "proxy"-
     * appended value last — over a real HTTP connection through a real trusted-proxy-configured
     * gateway. The audited {@code client_address} must be the LAST value, never the first.
     *
     * <p>Spends the FIRST of this class's 3-capacity gate budget. The login itself is refused
     * (unknown user) — irrelevant to what this test proves, which is solely which address gets
     * audited.
     */
    @Test
    @Order(1)
    void lastForwardedForFieldWinsAndIsAudited() throws Exception {
        String attackerValue = "203.0.113.66";
        String proxyAppendedValue = "198.51.100.7";

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                // java.net.http.HttpClient sends repeated .header(name, v) calls as separate fields,
                // exactly the shape a proxy that APPENDS (rather than edits) produces.
                .header("X-Forwarded-For", attackerValue)
                .header("X-Forwarded-For", proxyAppendedValue)
                .POST(HttpRequest.BodyPublishers.ofString(loginBody("xff-audit-probe-" + UUID.randomUUID(), "whatever")))
                .build());
        assertEquals(401, response.statusCode(), response.body());

        UUID correlationId = correlationIdOf(response);
        java.util.List<String> addresses = ownerJdbc.queryForList(
                "SELECT DISTINCT client_address FROM sm_control.change_audit"
                        + " WHERE audit_operation = :op AND correlation_id = :cid",
                Map.of("op", AuditOperations.LOGIN_REFUSED, "cid", correlationId), String.class);
        assertEquals(java.util.List.of(proxyAppendedValue), addresses,
                "the audited client_address must be the LAST (proxy-appended) X-Forwarded-For field, "
                        + "never the attacker-supplied first one");
    }

    /**
     * {@code OR-24} end to end: three requests for one fresh client that reach cp-core (spending
     * that client's whole bucket), then a fourth that the gate refuses — {@code 429 RATE_LIMITED}, a
     * whole-second {@code Retry-After}, {@code no-store} — and which leaves NO trace in either
     * {@code sm_control.change_audit} or {@code sm_control.login_throttle}: proof the request
     * never reached cp-core at all.
     */
    @Test
    @Order(2)
    void exceedingTheGateYieldsRateLimitedAndTouchesNoCpCoreState() throws Exception {
        // The gate keys on the RESOLVED client address. An earlier revision assumed the first test's
        // request had spent a token from this test's bucket; it had not — that request was forwarded
        // for a different client, so it spent a different bucket, and this test's third request was
        // correctly allowed. Each gate test now owns a fresh client address (forwarded through the
        // trusted loopback proxy), so the arithmetic depends on nothing another test did.
        String client = "203.0.113." + (1 + (int) (Math.random() * 200));
        for (int spent = 1; spent <= 3; spent++) {
            HttpResponse<String> allowed = send(forwardedLoginRequest(
                    "rate-gate-user-" + spent + "-" + UUID.randomUUID(), client));
            assertEquals(401, allowed.statusCode(), "token " + spent + " of 3 must still reach cp-core: "
                    + allowed.body());
        }

        int auditRowsBefore = totalRows("sm_control.change_audit");
        int throttleRowsBefore = totalRows("sm_control.login_throttle");

        HttpResponse<String> gated = send(forwardedLoginRequest("rate-gate-user-4-" + UUID.randomUUID(), client));
        assertEquals(429, gated.statusCode(), gated.body());
        JsonNode body = MAPPER.readTree(gated.body());
        assertEquals("RATE_LIMITED", body.get("code").asText());
        String retryAfter = gated.headers().firstValue("Retry-After").orElse(null);
        assertNotNull(retryAfter, "a 429 must carry Retry-After");
        assertDoesNotThrow(() -> {
            long seconds = Long.parseLong(retryAfter);
            if (seconds < 1) {
                throw new AssertionError("Retry-After must be a whole number of seconds >= 1, was " + retryAfter);
            }
        });
        assertEquals("no-store", gated.headers().firstValue("Cache-Control").orElse(null));

        assertEquals(auditRowsBefore, totalRows("sm_control.change_audit"),
                "a gated request must add no change_audit row — it never reached cp-core");
        assertEquals(throttleRowsBefore, totalRows("sm_control.login_throttle"),
                "a gated request must add no login_throttle row — it never reached cp-core");
    }

    private int totalRows(String table) {
        Integer count = ownerJdbc.getJdbcTemplate().queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    // ---- HTTP helpers -----------------------------------------------------------------------

    private String baseUrl() {
        return "http://" + LOOPBACK + ":" + port;
    }

    private URI uri(String path) {
        return URI.create(baseUrl() + path);
    }

    private HttpRequest loginRequest(String username) {
        return HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(loginBody(username, "whatever-credential")))
                .build();
    }

    /** A login the trusted loopback proxy forwards for {@code client}, which keys the gate's bucket. */
    private HttpRequest forwardedLoginRequest(String username, String client) {
        return HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                .header("Origin", CONSOLE_ORIGIN)
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", client)
                .POST(HttpRequest.BodyPublishers.ofString(loginBody(username, "whatever-credential")))
                .build();
    }

    private static String loginBody(String username, String credential) {
        // the organization is irrelevant to the gate under test (it counts requests before any credential work)
        return "{\"organization\":\"00000000-0000-4000-8000-000000000011\",\"username\":\"" + username
                + "\",\"credential\":\"" + credential + "\"}";
    }

    private static HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static UUID correlationIdOf(HttpResponse<String> response) {
        String value = response.headers().firstValue(CORRELATION_HEADER)
                .orElseThrow(() -> new AssertionError("no " + CORRELATION_HEADER + " header on " + response));
        return UUID.fromString(value);
    }
}
