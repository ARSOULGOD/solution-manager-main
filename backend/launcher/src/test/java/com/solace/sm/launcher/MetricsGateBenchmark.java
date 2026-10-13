package com.solace.sm.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.persistence.SmControlMigrationMain;
import com.solace.sm.dp.metrics.bench.BenchResults;
import com.solace.sm.dp.metrics.bench.BenchSettings;
import com.solace.sm.dp.metrics.bench.Evidence;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * Plan 011 P11-8.1 Step 3: the storage gate's AS-AN-OPERATOR half. It boots the real {@code SolutionManagerApplication} against
 * the benchmark database (both schemas migrated), seeds an Operator bound at the loaded instances' environment the way the landed
 * launcher ITs do ({@code DeclareFixtures}: organization, unit, environment, an {@code environment} scope, a local principal, a
 * {@code role_binding}), logs in over HTTP and times {@code POST /query/range} through {@link GateRunner}. It never reads the
 * metrics tables itself: whatever it measures went through authentication, CSRF, reach filtering and the verb check.
 *
 * <p>The load is the data-plane benchmark's ({@code MetricsStorageBenchmarkIT}), found through the same results directory
 * ({@code sm.bench.out}): the tenant, environment and solution ids it stored are the organization, environment and solution
 * seeded here, so the Operator reaches exactly those instances. Passwords come from the environment only
 * ({@code SM_BENCH_RUNTIME_PASSWORD}, {@code SM_BENCH_ADMIN_PASSWORD}, {@code SM_BENCH_CP_RUNTIME_PASSWORD},
 * {@code SM_BENCH_CP_MIGRATOR_PASSWORD}); the Operator's own credential is minted per run and never leaves this JVM.
 */
final class MetricsGateBenchmark implements AutoCloseable {

    static final String STEP = "3";
    static final String ORIGIN = "https://bench.test";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final BenchSettings settings;
    private final GateConfig config;
    private final Function<String, String> env;
    private final BenchResults results;
    private final Evidence evidence;
    private NamedParameterJdbcTemplate admin;
    private DataSource adminDs;
    private final Supplier<String> databaseIdentity;

    MetricsGateBenchmark(BenchSettings settings, GateConfig config, Function<String, String> env, PrintStream out) {
        this(settings, config, env, out, null);
    }

    /** @param databaseIdentity how the database's own identity is read; null reads it over the admin connection ({@link GateMasterKey}) */
    MetricsGateBenchmark(BenchSettings settings, GateConfig config, Function<String, String> env, PrintStream out,
            Supplier<String> databaseIdentity) {
        this.databaseIdentity = databaseIdentity != null ? databaseIdentity
                : () -> admin().getJdbcTemplate().queryForObject(GateMasterKey.identityQuery(), String.class);
        this.settings = settings;
        this.config = config;
        this.env = env;
        this.results = new BenchResults(settings.outDir());
        this.evidence = new Evidence(results, out);
        for (String variable : List.of("SM_BENCH_RUNTIME_PASSWORD", "SM_BENCH_ADMIN_PASSWORD", "SM_BENCH_CP_RUNTIME_PASSWORD",
                "SM_BENCH_CP_MIGRATOR_PASSWORD", "SM_BENCH_MIGRATOR_PASSWORD")) {
            String value = env.apply(variable);
            if (value != null) {
                evidence.redact(value);
            }
        }
    }

    BenchResults results() {
        return results;
    }

    Evidence evidence() {
        return evidence;
    }

    private String secret(String variable) {
        String v = env.apply(variable);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException(variable + " is not set (the password is read from the environment only)");
        }
        return v;
    }

    private NamedParameterJdbcTemplate admin() {
        if (admin == null) {
            adminDs = new DriverManagerDataSource(settings.url(), settings.adminUser(), secret("SM_BENCH_ADMIN_PASSWORD"));
            admin = new NamedParameterJdbcTemplate(adminDs);
        }
        return admin;
    }

    @Override
    public void close() {
        // nothing is held between steps: each opens and closes what it uses
    }

    // ---- the ids the data-plane load stored --------------------------------------------------------------------------

    private UUID stored(String key, java.util.Optional<UUID> configured) {
        return configured.orElseGet(() -> UUID.fromString(results.get(key).orElseThrow(() -> new IllegalStateException(
                key + " is not recorded in " + settings.outDir() + ": run the data-plane Steps 0b and 1 first (same sm.bench.out),"
                        + " or set sm.bench.tenant and sm.bench.environment"))));
    }

    private UUID tenant() {
        return stored("site.main.tenant", settings.tenant());
    }

    private UUID environment() {
        return stored("site.main.environment", settings.environment());
    }

    private UUID solution() {
        return UUID.fromString(results.get("site.main.solution").orElseThrow(() -> new IllegalStateException(
                "site.main.solution is not recorded: run the data-plane Steps 0b and 1 first (same sm.bench.out)")));
    }

    private UUID unit() {
        return UUID.nameUUIDFromBytes(("bench-unit-" + tenant()).getBytes());
    }

    // ---- prepare: sm_control, and the Operator ------------------------------------------------------------------------

    void prepare() {
        NamedParameterJdbcTemplate jdbc = admin();
        boolean roles = Boolean.TRUE.equals(jdbc.getJdbcTemplate().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'sm_cp_owner')", Boolean.class));
        if (roles) {
            evidence.note("prepare", "the sm_control Phase 0 already ran in this cluster (role sm_cp_owner exists): not repeated");
        } else {
            try (Connection c = adminDs.getConnection()) {
                new ResourceDatabasePopulator(new ClassPathResource("db/bootstrap/sm_control_phase0.sql")).populate(c);
            } catch (SQLException e) {
                throw new IllegalStateException("sm_control Phase 0 failed: " + e.getClass().getSimpleName() + " SQLSTATE " + e.getSQLState());
            }
            evidence.note("prepare", "the sm_control Phase 0 ran (roles sm_cp_owner, sm_cp_migrator, sm_cp_runtime; schema sm_control)");
        }
        setPassword("sm_cp_runtime", secret("SM_BENCH_CP_RUNTIME_PASSWORD"));
        setPassword("sm_cp_migrator", secret("SM_BENCH_CP_MIGRATOR_PASSWORD"));
        // The bootstrap administrator's credential is unused here (the gate logs in as its own Operator); a random hash satisfies the step.
        SmControlMigrationMain.run(settings.url(), "sm_cp_migrator", secret("SM_BENCH_CP_MIGRATOR_PASSWORD"),
                new CredentialHasher().hash(randomCredential().toCharArray()));
        seedOperator();
        evidence.measured("prepare", "operator", config.operatorUsername());
    }

    private void setPassword(String role, String password) {
        String statement = admin().getJdbcTemplate().queryForObject("SELECT format('ALTER ROLE %I PASSWORD %L', ?::text, ?::text)",
                String.class, role, password);
        admin().getJdbcTemplate().execute(statement);
    }

    /**
     * The organization IS the data-plane tenant, and the environment the one the instances sit in. A superuser seed: the product has no
     * create-organization or create-principal endpoint in v1 (the landed ITs seed the same way).
     */
    private void seedOperator() {
        UUID organization = tenant();
        UUID unit = unit();
        UUID environment = environment();
        UUID solution = solution();
        UUID scope = UUID.nameUUIDFromBytes(("bench-scope-" + environment).getBytes());
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("o", organization).addValue("u", unit).addValue("e", environment)
                .addValue("s", solution).addValue("scope", scope).addValue("name", config.operatorUsername())
                .addValue("pid", UUID.nameUUIDFromBytes(("bench-principal-" + organization).getBytes()))
                .addValue("binding", UUID.nameUUIDFromBytes(("bench-binding-" + organization).getBytes()))
                .addValue("hash", new CredentialHasher().hash(randomCredential().toCharArray()));
        NamedParameterJdbcTemplate jdbc = admin();
        jdbc.update("INSERT INTO sm_control.organization (organization_id, name) VALUES (:o, 'fixture-' || gen_random_uuid()::text) ON CONFLICT DO NOTHING", p);
        jdbc.update("INSERT INTO sm_control.organization_unit (organization_id, organization_unit_id, name) VALUES (:o, :u, 'fixture-' || gen_random_uuid()::text) ON CONFLICT DO NOTHING", p);
        jdbc.update("INSERT INTO sm_control.environment (organization_id, organization_unit_id, environment_id, name) VALUES (:o, :u, :e, 'fixture-' || gen_random_uuid()::text)"
                + " ON CONFLICT DO NOTHING", p);
        jdbc.update("INSERT INTO sm_control.solution (organization_id, organization_unit_id, environment_id, solution_id) VALUES (:o, :u, :e, :s)"
                + " ON CONFLICT DO NOTHING", p);
        // Reach filtering asks the control plane which instances belong where; the data plane's instances are registered there too.
        jdbc.update("INSERT INTO sm_control.instance (organization_id, organization_unit_id, environment_id, solution_id, instance_id)"
                + " SELECT :o, :u, :e, :s, instance_id FROM sm_data.instance_state WHERE tenant_id = :o ON CONFLICT DO NOTHING", p);
        jdbc.update("INSERT INTO sm_control.scope (scope_id, kind, organization_id, organization_unit_id, environment_id)"
                + " VALUES (:scope, 'environment', :o, :u, :e) ON CONFLICT DO NOTHING", p);
        jdbc.update("INSERT INTO sm_control.principal (id, organization_id, source, username, credential_hash, must_rotate)"
                + " SELECT :pid, :o, 'local', :name, :hash, false WHERE NOT EXISTS (SELECT 1 FROM sm_control.principal"
                + " WHERE organization_id = :o AND username = :name)", p);
        jdbc.update("INSERT INTO sm_control.role_binding (binding_id, principal_id, role_id, scope_id)"
                + " SELECT :binding, pr.id, (SELECT role_id FROM sm_control.role WHERE name = 'Operator'), :scope"
                + " FROM sm_control.principal pr WHERE pr.organization_id = :o AND pr.username = :name"
                + " AND NOT EXISTS (SELECT 1 FROM sm_control.role_binding b WHERE b.binding_id = :binding)", p);
    }

    private static String randomCredential() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---- Step 3 ------------------------------------------------------------------------------------------------------

    /** What a booted application and a logged-in Operator are. */
    private record Session(String base, String session, String csrf) {
    }

    void step3() throws IOException, InterruptedException {
        String credential = randomCredential();
        admin().update("UPDATE sm_control.principal SET credential_hash = :hash, must_rotate = false WHERE organization_id = :o AND username = :name",
                new MapSqlParameterSource().addValue("hash", new CredentialHasher().hash(credential.toCharArray()))
                        .addValue("o", tenant()).addValue("name", config.operatorUsername()));
        evidence.redact(credential);
        List<UUID> instances = admin().queryForList("SELECT instance_id FROM sm_data.instance_state WHERE tenant_id = :t ORDER BY instance_id",
                new MapSqlParameterSource("t", tenant()), UUID.class);
        if (instances.isEmpty()) {
            throw new IllegalStateException("no instances are loaded for the benchmark tenant: run the data-plane Steps 0b and 1 first");
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SolutionManagerApplication.class)
                .web(WebApplicationType.SERVLET).properties(bootProperties()).run()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            Session s = login("http://127.0.0.1:" + port, credential);
            evidence.note(STEP, "the application is booted against the benchmark database and " + config.operatorUsername()
                    + " (role Operator, bound at the loaded instances' environment) is logged in over HTTP; every timing below is as that principal");
            requireEnvironmentVisible(s);
            runCases(s, instances);
        }
    }

    Map<String, Object> bootProperties() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sm.control.runtime.url", settings.url());
        p.put("sm.control.runtime.username", "sm_cp_runtime");
        p.put("sm.control.runtime.password", secret("SM_BENCH_CP_RUNTIME_PASSWORD"));
        p.put("sm.data.runtime.url", settings.url());
        p.put("sm.data.runtime.username", settings.runtimeUser());
        p.put("sm.data.runtime.password", secret("SM_BENCH_RUNTIME_PASSWORD"));
        p.put("sm.gateway.trusted-origins[0]", ORIGIN);
        p.put("sm.gateway.direct-client-connections", "true");
        p.put("sm.gateway.auth-rate.capacity", "1000");
        p.put("sm.gateway.auth-rate.refill-per-minute", "6000");
        p.put("server.port", "0");
        // Nothing else may touch the benchmark database while it is timed: no collector, no push subscriber, no health poller.
        p.put("sm.dp.health.enabled", "false");
        p.put("sm.dp.metrics.collect.enabled", "false");
        p.put("sm.dp.metrics.push.enabled", "false");
        p.putAll(GateMasterKey.properties(databaseIdentity.get(), secret("SM_BENCH_ADMIN_PASSWORD")));
        return p;
    }

    private Session login(String base, String credential) throws IOException, InterruptedException {
        HttpResponse<String> login = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/cp/auth/login")).timeout(Duration.ofSeconds(60))
                .header("Origin", ORIGIN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"organization\":\"" + tenant() + "\",\"username\":\"" + config.operatorUsername() + "\",\"credential\":\"" + credential + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        if (login.statusCode() != 200) {
            throw new IllegalStateException("the Operator could not log in: HTTP " + login.statusCode());
        }
        String cookie = login.headers().allValues("Set-Cookie").stream().filter(h -> h.startsWith(GateRunner.SESSION_COOKIE + "="))
                .findFirst().orElseThrow(() -> new IllegalStateException("login set no session cookie"));
        String value = cookie.substring((GateRunner.SESSION_COOKIE + "=").length());
        int semicolon = value.indexOf(';');
        return new Session(base, semicolon < 0 ? value : value.substring(0, semicolon), MAPPER.readTree(login.body()).get("csrfToken").asText());
    }

    /** The seed is right only if the Operator can see the environment its instances sit in (a hierarchy read that exists today). */
    private void requireEnvironmentVisible(Session s) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(s.base() + "/api/cp/hierarchy/organizations/" + tenant()
                + "/sub-orgs/" + unit() + "/environments")).timeout(Duration.ofSeconds(60))
                .header("Cookie", GateRunner.SESSION_COOKIE + "=" + s.session()).GET().build(), HttpResponse.BodyHandlers.ofString());
        boolean visible = false;
        if (response.statusCode() == 200) {
            for (JsonNode item : MAPPER.readTree(response.body()).path("items")) {
                visible |= environment().toString().equals(item.path("id").asText());
            }
        }
        evidence.measured(STEP, "operatorSeesTheLoadedEnvironment", visible);
        if (!visible) {
            throw new IllegalStateException("the Operator does not see the environment the instances were loaded into (HTTP "
                    + response.statusCode() + "): the seed is wrong, so no query would reach them");
        }
    }

    private void runCases(Session s, List<UUID> all) throws IOException, InterruptedException {
        GateRunner runner = new GateRunner(s.base(), s.session(), s.csrf(), ORIGIN, config);
        boolean probed = false;
        for (int wanted : config.instanceCounts()) {
            int n = Math.min(wanted, all.size());
            if (n < wanted) {
                evidence.note(STEP, "only " + all.size() + " instances are loaded: the " + wanted + "-instance shape runs over " + n);
            }
            List<UUID> chosen = all.subList(0, n);
            for (GateRunner.GateCase c : GateRunner.planCases()) {
                GateRunner.CaseResult r = runner.run(c, chosen);
                if (!probed) {
                    probed = true;
                    if (r.allNotFound()) {
                        throw new IllegalStateException("POST " + GateRunner.QUERY_RANGE + " answered 404: this build does not serve the monitoring"
                                + " query routes (plan 011 P11-7.2 is not merged into it). Nothing was measured");
                    }
                    if (r.evidence() == GateRunner.Evidence.EMPTY) {
                        throw new IllegalStateException("the first probed shape (" + r.name() + ", " + r.instances() + " instance(s)) answered"
                                + " 2xx with series=" + r.series() + " points=" + r.points() + ": the seed, entity type, metric or the Operator's"
                                + " reach does not match the loaded data. Nothing was recorded as measured");
                    }
                }
                report(r, wanted);
            }
        }
    }

    private void report(GateRunner.CaseResult r, int wanted) {
        String key = r.name() + "." + r.instances() + ".";
        evidence.measured(STEP, key + "statuses", r.statuses());
        if (r.evidence() != GateRunner.Evidence.REFUSED) {
            evidence.measured(STEP, key + "series", r.series());
            evidence.measured(STEP, key + "points", r.points());
        }
        if (r.evidence() == GateRunner.Evidence.TIMED) {
            evidence.measured(STEP, key + "p50Millis", GateRunner.p50(r));
            evidence.measured(STEP, key + "p95Millis", GateRunner.p95(r));
            evidence.measured(STEP, key + "coldMillis", r.coldMillis());
            evidence.measured(STEP, key + "timedRuns", r.millis().size());
            evidence.measured(STEP, key + "servedTier", r.served());
            if (r.served() != null && !r.served().equals(r.expectedServed())) {
                evidence.finding(STEP, key + "the shape expected the " + r.expectedServed() + " tier and the server served " + r.served()
                        + ": the timing is of that tier, not of the one the plan names");
            }
        } else if (r.evidence() == GateRunner.Evidence.EMPTY) {
            evidence.finding(STEP, key + "answered 2xx with series=" + r.series() + " points=" + r.points() + ": the query matched no data"
                    + " (a seed, entity type, metric or reach mismatch), so its timings are of an EMPTY query and are NOT recorded as"
                    + " MEASURED evidence for REQ-007");
        } else {
            evidence.finding(STEP, key + "no 2xx answer (statuses " + r.statuses() + "): nothing is timed for this shape; refused is a result"
                    + ", not a timing");
        }
        if (r.rateLimited() > 0) {
            evidence.finding(STEP, key + "was rate limited " + r.rateLimited() + " times: raise sm.bench.gate.spacing-ms");
        }
        if (r.evidence() == GateRunner.Evidence.TIMED) {
            evidence.note(STEP, key + String.format(Locale.ROOT, "label=MEASURED as an Operator over HTTP; requestedInstances=%d", wanted));
        }
    }
}
