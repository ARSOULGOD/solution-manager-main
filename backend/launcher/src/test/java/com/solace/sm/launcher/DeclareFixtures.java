package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.identity.CredentialHasher;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Seed data and an HTTP client shared by {@code InstanceEndpointsIT} and {@code DeclareCrashWindowIT} (plan 010 T4.4).
 * Everything is written straight to the database as the owner (there is no create-organization or create-target
 * endpoint anywhere in the contract): one organization with an organization unit and TWO sibling environments, an
 * Administrator at the organization, an Operator bound at each environment, a Viewer at the organization, one target
 * owned at each environment (each with a data-plane connection record and its envelope), and a second organization
 * whose principal and environment the first must never reach.
 */
final class DeclareFixtures {

    static final String CONSOLE_ORIGIN = "https://console.test";
    static final String SESSION_COOKIE = "__Host-SM_SESSION";
    static final String CREDENTIAL = "declare-it-credential-0001";
    static final ObjectMapper MAPPER = new ObjectMapper();

    static final String ADMIN = "declare-it-admin";
    static final String OPERATOR_ONE = "declare-it-operator-one";
    static final String OPERATOR_TWO = "declare-it-operator-two";
    static final String VIEWER = "declare-it-viewer";
    static final String FOREIGN = "declare-it-foreign";

    private DeclareFixtures() {
    }

    /** The ids of everything {@link #seed} wrote. */
    record World(UUID organization, UUID unit, UUID envOne, UUID envTwo, UUID targetOne, UUID targetTwo,
            UUID otherOrganization, UUID otherEnvironment, UUID otherTarget) {
    }

    static World seed(NamedParameterJdbcTemplate jdbc) {
        UUID organization = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID envOne = UUID.randomUUID();
        UUID envTwo = UUID.randomUUID();
        hierarchy(jdbc, organization, unit, envOne, envTwo);
        UUID otherOrganization = UUID.randomUUID();
        UUID otherUnit = UUID.randomUUID();
        UUID otherEnvironment = UUID.randomUUID();
        hierarchy(jdbc, otherOrganization, otherUnit, otherEnvironment, null);

        UUID orgScope = scope(jdbc, "organization", organization, null, null);
        UUID envOneScope = scope(jdbc, "environment", organization, unit, envOne);
        UUID envTwoScope = scope(jdbc, "environment", organization, unit, envTwo);
        UUID otherScope = scope(jdbc, "organization", otherOrganization, null, null);

        UUID admin = principal(jdbc, organization, ADMIN);
        bind(jdbc, admin, "Administrator", orgScope);
        bind(jdbc, principal(jdbc, organization, OPERATOR_ONE), "Operator", envOneScope);
        bind(jdbc, principal(jdbc, organization, OPERATOR_TWO), "Operator", envTwoScope);
        bind(jdbc, principal(jdbc, organization, VIEWER), "Viewer", orgScope);
        UUID foreign = principal(jdbc, otherOrganization, FOREIGN);
        bind(jdbc, foreign, "Administrator", otherScope);

        UUID targetOne = target(jdbc, organization, envOneScope, "target-one", admin);
        UUID targetTwo = target(jdbc, organization, envTwoScope, "target-two", admin);
        UUID otherTarget = target(jdbc, otherOrganization, otherScope, "target-foreign", foreign);
        connection(jdbc, organization, targetOne, "vm-one.example.test", 'A');
        connection(jdbc, organization, targetTwo, "vm-two.example.test", 'B');
        connection(jdbc, otherOrganization, otherTarget, "vm-foreign.example.test", 'C');
        return new World(organization, unit, envOne, envTwo, targetOne, targetTwo, otherOrganization,
                otherEnvironment, otherTarget);
    }

    private static void hierarchy(NamedParameterJdbcTemplate jdbc, UUID organization, UUID unit, UUID env,
            UUID siblingEnv) {
        jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (:o, 'fixture-' || gen_random_uuid()::text)",
                new MapSqlParameterSource().addValue("o", organization));
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (:o, :u, 'fixture-' || gen_random_uuid()::text)",
                new MapSqlParameterSource().addValue("o", organization).addValue("u", unit));
        for (UUID environment : siblingEnv == null ? new UUID[] {env} : new UUID[] {env, siblingEnv}) {
            jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name)"
                            + " VALUES (:o, :u, :e, 'fixture-' || gen_random_uuid()::text)", new MapSqlParameterSource().addValue("o", organization)
                            .addValue("u", unit).addValue("e", environment));
        }
    }

    static UUID scope(NamedParameterJdbcTemplate jdbc, String kind, UUID organization, UUID unit,
            UUID environment) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id, organization_unit_id,"
                        + " environment_id) VALUES (:id, :kind, :o, :u, :e)",
                new MapSqlParameterSource().addValue("id", id).addValue("kind", kind).addValue("o", organization)
                        .addValue("u", unit).addValue("e", environment));
        return id;
    }

    /**
     * The organization each seeded username belongs to (OR-125 (4): a login names the organization, and a username is unique
     * per organization). Filled by {@link #principal}; the last seeding of a username wins, which is the world of the class
     * that is running.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, UUID> ORGANIZATION_OF =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The install's bootstrap organization (V007), for a principal this class did not seed. */
    static final UUID BOOTSTRAP_ORGANIZATION = UUID.fromString("00000000-0000-4000-8000-000000000011");

    /** The organization a seeded user signs in to; the bootstrap organization for one this class did not seed. */
    static UUID organizationOf(String username) {
        return ORGANIZATION_OF.getOrDefault(username, BOOTSTRAP_ORGANIZATION);
    }

    static UUID principal(NamedParameterJdbcTemplate jdbc, UUID organization, String username) {
        ORGANIZATION_OF.put(username, organization);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username, credential_hash,"
                        + " must_rotate) VALUES (:id, :o, 'local', :u, :h, false)",
                new MapSqlParameterSource().addValue("id", id).addValue("o", organization).addValue("u", username)
                        .addValue("h", new CredentialHasher().hash(CREDENTIAL.toCharArray())));
        return id;
    }

    static void bind(NamedParameterJdbcTemplate jdbc, UUID principal, String role, UUID scope) {
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id) VALUES"
                        + " (:b, :p, (SELECT role_id FROM sm_control.role WHERE name = :r), :s)",
                new MapSqlParameterSource().addValue("b", UUID.randomUUID()).addValue("p", principal)
                        .addValue("r", role).addValue("s", scope));
    }

    private static UUID target(NamedParameterJdbcTemplate jdbc, UUID organization, UUID scope, String name,
            UUID creator) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_control.target (target_id, organization_id, ownership_scope_id, name, kind,"
                        + " transport, created_by, created_at) VALUES (:id, :o, :s, :n, 'docker', 'ssh-docker', :c,"
                        + " now())", new MapSqlParameterSource().addValue("id", id).addValue("o", organization)
                        .addValue("s", scope).addValue("n", name).addValue("c", creator));
        return id;
    }

    private static void connection(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID target, String host, char fp) {
        jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :g, 'ssh-docker', :h, 22, 'sm', :fp, :cred,"
                        + " '/srv/sm', 'sm-', :by, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("g", target).addValue("h", host)
                        .addValue("fp", "SHA256:" + String.valueOf(fp).repeat(43)).addValue("cred", UUID.randomUUID())
                        .addValue("by", UUID.randomUUID()));
    }

    // ---- the package ---------------------------------------------------------------------------------------

    static ObjectNode cachePackage() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            Path candidate = dir.resolve("contracts/validation/fixtures/cache-2.4.package.json");
            if (Files.isRegularFile(candidate)) {
                try {
                    return (ObjectNode) MAPPER.readTree(Files.readString(candidate));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("fixture not found from " + Path.of("").toAbsolutePath());
    }

    /** A valid declare body for {@code solutionId} (package version 1 of the cache fixture). */
    static ObjectNode declareBody(UUID solutionId, UUID target, String name, String container, int healthPort) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("solutionId", solutionId.toString());
        body.put("name", name);
        body.put("targetId", target.toString());
        body.put("declaredPackageVersion", 1);
        ObjectNode placement = body.putObject("placement");
        placement.put("containerName", container);
        placement.put("configDirectory", "/srv/sm/" + container);
        placement.put("containerConfigPath", "/app/external/spring/config");
        placement.put("fileName", "application.yml");
        placement.put("healthPort", healthPort);
        // Plan 011 P11-4.1 (M-Q14): the Cache fixture declares metrics on source port 8081, and a host port is unique among
        // a target's live placements, so two instances declared on one target need DISTINCT mappings (the default, 8081 to
        // 8081, would collide: 409 METRICS_PORT_CONFLICT). Each test already gives its placement a distinct health port.
        // healthPort + 30000 leaves the port range for an ephemeral health port (a stub bound on port 0 is 49152 or more on macOS,
        // so HealthEndpointsIT's declare was refused 400 every run): fold such a port DOWN instead, which stays in range and
        // stays distinct for distinct health ports of the same kind.
        placement.putObject("metricsPorts").put("8081", healthPort + 30_000 <= 65_535 ? healthPort + 30_000 : healthPort - 30_000);
        body.putObject("acquisition").put("mode", "direct-http");
        return body;
    }

    // ---- HTTP ----------------------------------------------------------------------------------------------

    record Login(String session, String csrf) {
    }

    static final class Client {
        /**
         * No test request may wait forever (plan 010 T6.6-1: a publisher that parked a request thread stalled a whole build
         * for ~45 minutes inside {@code send}). A request that is not answered in this long fails the test that issued it.
         */
        static final java.time.Duration REQUEST_TIMEOUT = java.time.Duration.ofSeconds(30);

        private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(REQUEST_TIMEOUT).build();
        private final int port;
        /** Headers every request carries: a test that stands in for a TLS-terminating proxy sets X-Forwarded-Proto/-For here. */
        private final java.util.Map<String, String> extraHeaders;

        Client(int port) {
            this(port, java.util.Map.of());
        }

        Client(int port, java.util.Map<String, String> extraHeaders) {
            this.port = port;
            this.extraHeaders = java.util.Map.copyOf(extraHeaders);
        }

        Login login(String username) throws IOException, InterruptedException {
            HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/cp/auth/login"))
                    .header("Origin", CONSOLE_ORIGIN).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"organization\":\"" + organizationOf(username)
                            + "\",\"username\":\"" + username + "\",\"credential\":\"" + CREDENTIAL + "\"}"))
                    .build());
            assertEquals(200, login.statusCode(), login.body());
            String cookie = login.headers().allValues("Set-Cookie").stream()
                    .filter(header -> header.startsWith(SESSION_COOKIE + "=")).findFirst().orElseThrow();
            String value = cookie.substring((SESSION_COOKIE + "=").length());
            int semicolon = value.indexOf(';');
            return new Login(semicolon < 0 ? value : value.substring(0, semicolon),
                    MAPPER.readTree(login.body()).get("csrfToken").asText());
        }

        HttpResponse<String> get(String path, Login login) throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(uri(path)).header("Cookie", SESSION_COOKIE + "=" + login.session())
                    .GET().build());
        }

        HttpResponse<String> post(String path, Login login, JsonNode body) throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(uri(path)).header("Cookie", SESSION_COOKIE + "=" + login.session())
                    .header("Origin", CONSOLE_ORIGIN).header("X-SM-CSRF", login.csrf())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        }

        HttpResponse<String> postWithoutCsrf(String path, Login login, JsonNode body)
                throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(uri(path)).header("Cookie", SESSION_COOKIE + "=" + login.session())
                    .header("Origin", CONSOLE_ORIGIN).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        }

        /** No cookie. The Origin is the trusted one, so the refusal is the missing SESSION, not the origin check. */
        HttpResponse<String> anonymous(String method, String path) throws IOException, InterruptedException {
            return send(HttpRequest.newBuilder(uri(path)).header("Origin", CONSOLE_ORIGIN)
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString("{}")).build());
        }

        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }

        private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
            // Every request the client makes carries the bound, whichever helper built it.
            HttpRequest.Builder bounded = HttpRequest.newBuilder(request, (name, value) -> true).timeout(REQUEST_TIMEOUT);
            extraHeaders.forEach(bounded::header);
            return HTTP.send(bounded.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
