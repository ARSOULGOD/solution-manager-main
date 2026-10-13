package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.engine.AdmissionService;
import com.solace.sm.dp.apply.engine.ApplyEngineConfiguration;
import com.solace.sm.dp.apply.engine.AttemptPolicy;
import com.solace.sm.dp.apply.engine.AttemptService;
import com.solace.sm.dp.apply.engine.OutcomeService;
import com.solace.sm.dp.apply.engine.StepIntentService;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link ApplyPersistenceConfiguration} + {@link ApplyEngineConfiguration} as Spring actually builds
 * them — and, since the external review's finding 1, specifically whether an application that CANNOT
 * migrate still refuses to serve a schema nobody has migrated.
 *
 * <p><b>Why a database of its own per test.</b> Every other IT shares one already-migrated database, so
 * a context that never checked anything would still come up green there. Each test below creates an
 * EMPTY database, gives it only what Phase 0 gives ({@code CREATE SCHEMA … AUTHORIZATION sm_dp_owner}
 * plus the {@code USAGE} grant), and then does or does not run {@link SmDataMigrationMain}. Per test
 * rather than per class so the three cases do not depend on JUnit's method order.
 *
 * <p>The three cases are the whole of the new posture: unmigrated ⇒ the context refuses to start and
 * says how to fix it; migrated ⇒ it comes up wired, and its bound properties contain no migrator
 * credential to leak; migrated twice ⇒ the one-shot step is idempotent, so a runbook that re-runs it is
 * safe.
 */
@Testcontainers(disabledWithoutDocker = true)
class ApplyPersistenceConfigurationIT {

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();          // roles are CLUSTER-scoped; Phase 0 already made them
    }

    @Test
    void anUnmigratedDatabaseStopsTheContextStartingWithAMessageNamingTheMigrationStep() {
        String url = freshDatabase("sm_ctx_unmigrated");

        runner(url).run(context -> {
            Throwable failure = context.getStartupFailure();
            assertNotNull(failure, "a context over an UNMIGRATED sm_data must not start: the runtime "
                    + "role cannot create the tables, so coming up would mean serving a schema that "
                    + "does not exist");
            String chain = causeChain(failure);
            System.out.println("MEASURED: readiness refusal — " + chain);
            assertTrue(chain.contains("sm_data is not migrated to the shape this build expects "
                            + "(found 0 of 5 tables)"), chain);
            assertTrue(chain.contains("SmDataMigrationMain with the sm_dp_migrator credential"),
                    "the message must name the step that fixes it, or an operator is left guessing. "
                            + "Was: " + chain);
        });
    }

    /**
     * OR-108 (1): the observation port reads, and the engine writes, {@code apply_attempt.pre_image_change} (V015). Its
     * readiness check passes on a migrated database and names the migration step on one without the column - checked on
     * the check itself because the one-shot-migration test below does not start the observation configuration.
     */
    @Test
    void theAttemptPreImageChangeReadinessPassesWhereV015RanAndRefusesWhereTheColumnIsAbsent() throws Exception {
        ApplyObservationPersistenceConfiguration config = new ApplyObservationPersistenceConfiguration();

        config.attemptPreImageChangeReadiness(SmDataPostgres.runtimeDataSource()).afterPropertiesSet();

        String url = freshDatabase("sm_ctx_no_pre_image_change");
        PostgreSQLContainer<?> container = SmDataPostgres.container();
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> config.attemptPreImageChangeReadiness(SmDataPostgres.dataSource(url, container.getUsername(),
                        container.getPassword())).afterPropertiesSet());
        assertTrue(refused.getMessage().contains("found 0 of 1") && refused.getMessage().contains("SmDataMigrationMain"),
                refused.getMessage());
    }

    /**
     * The SUCCESS path, and what the JVM logs while it runs.
     *
     * <p>The migration step controls its own two streams and prints no URL. It does not control
     * Flyway, which logs {@code Database: <url> (PostgreSQL …)} at INFO on every migrate — measured
     * below through a logback {@code ListAppender} on the root logger, and deliberately NOT suppressed:
     * a build that silenced Flyway would be hiding the evidence rather than fixing the exposure. That
     * line is the whole justification for {@link JdbcUrls}: since the URL will be logged, a URL may not
     * carry a credential.
     */
    @Test
    void afterTheOneShotMigrationStepTheContextComesUpWiredAndHoldsNoMigratorCredential() {
        String url = freshDatabase("sm_ctx_migrated");
        LogCapture.Captured<MigrateResult> migrated = LogCapture.during(
                () -> SmDataMigrationMain.run(url, "sm_dp_migrator",
                        SmDataPostgres.MIGRATOR_PASSWORD));
        MigrateResult result = migrated.value();
        assertEquals(37, result.migrationsExecuted,
                "V001..V027, V037..V046 — 37 (OR-147 reconciliation: V043..V046 are plan 015's agent migrations renumbered after the Connections ones — V046 the discovery release rule, V045 agent_session features and clock offset, V044 agent command retention, V043 agent gateway state; V037..V042 are plans 013/014's Connections and the instance role; V028..V036 are the removed old-agent slots; V027 is OR-125 (2)'s, V026 is OR-125 (1)'s, V025 is plan 012 T7's, V024 is plan 008 S4's, V023 is plan 012 T3's, V022 is plan 013 T1.3's, V021 is plan 008 S2's, V020 is OR-112's second round, V015 is OR-108's, V016 is plan 011 P11-2.2's, V017 is P11-5.1's, V018 is OR-112 (1)'s, V019 is OR-112 (2)'s) must all have run — in a database that was EMPTY a moment ago");;

        List<LogCapture.Line> namingTheUrl = migrated.lines().stream()
                .filter(l -> l.message().contains(url)).toList();
        System.out.println("MEASURED: " + migrated.lines().size() + " log events during the "
                + "migration; " + namingTheUrl.size() + " name the JDBC URL:");
        namingTheUrl.forEach(l -> System.out.println("MEASURED:   " + l));

        assertFalse(namingTheUrl.isEmpty(),
                "a library prints the connection URL. If that ever stops being true the assertion "
                        + "should be inverted deliberately, not deleted — JdbcUrls' whole reason for "
                        + "existing is that a URL handed to Flyway and Hikari WILL be logged");
        assertTrue(namingTheUrl.stream().anyMatch(l -> "INFO".equals(l.level())),
                "…and at INFO, i.e. at the level a production deployment actually runs, not only at "
                        + "DEBUG. Was: " + namingTheUrl);
        assertFalse(migrated.text().contains(SmDataPostgres.MIGRATOR_PASSWORD),
                "the PASSWORD is not in any of those lines, because it is not in the URL — which is "
                        + "the property JdbcUrls.requireLocation preserves");

        runner(url).run(context -> {
            assertNull(context.getStartupFailure(),
                    "context failed to start: " + context.getStartupFailure());

            NamedParameterJdbcTemplate jdbc = context.getBean(NamedParameterJdbcTemplate.class);
            assertEquals(0, jdbc.queryForObject("SELECT count(*)::int FROM sm_data.apply_request",
                            new MapSqlParameterSource(), Integer.class),
                    "the runtime pool reaches the migrated tables");

            for (Class<?> bean : List.of(AdmissionService.class, OutcomeService.class,
                    StepIntentService.class, AttemptService.class, ApplyScopeRepository.class,
                    ApplyRequestRepository.class, StepIntentRepository.class,
                    AttemptRepository.class)) {
                assertNotNull(context.getBean(bean), bean.getSimpleName() + " must be wired");
            }
            assertEquals(5, context.getBean(AttemptPolicy.class).maxAttempts(),
                    "sm.data.apply.max-attempts must bind through relaxed naming");

            // Finding 1's structural claim, asserted structurally: there is no migrator component to
            // read. A test that only checked "we did not set sm.data.migrator.*" would pass against a
            // record that still HAS the field, which is the thing being removed.
            List<String> components = Arrays.stream(
                            context.getBean(SmDataProperties.class).getClass().getRecordComponents())
                    .map(RecordComponent::getName).toList();
            System.out.println("MEASURED: SmDataProperties components = " + components);
            assertEquals(List.of("runtime", "apply", "pool"), components,
                    "the application binds the runtime credential, the engine knobs and the one pool's "
                            + "size (plan 011 P11-3.1) — a migrator component here is a schema-owner "
                            + "password inside the application JVM");

            ScopeKey scope = new ScopeKey(new TenantId(UUID.randomUUID()),
                    new InstanceId(UUID.randomUUID()), PlacementId.INSTANCE_WIDE);
            // plan 010 T2.2: admission needs a declared instance — declared through the CONTEXT's own pool
            InventoryFixtures.declare(jdbc, scope.tenant().value(), scope.instance().value(), "active");
            assertInstanceOf(AdmissionOutcome.Admitted.class,
                    context.getBean(AdmissionService.class)
                            .admit(ApplyEngineHarness.apply(scope, 1)),
                    "an admission through the CONTEXT's own beans — the transaction manager, the "
                            + "pool and the repositories all agreeing");
        });
    }

    @Test
    void runningTheOneShotMigrationStepASecondTimeExecutesNothing() {
        String url = freshDatabase("sm_ctx_idempotent");

        assertEquals(37, SmDataMigrationMain.run(url, "sm_dp_migrator",
                SmDataPostgres.MIGRATOR_PASSWORD).migrationsExecuted);
        MigrateResult again = SmDataMigrationMain.run(url, "sm_dp_migrator",
                SmDataPostgres.MIGRATOR_PASSWORD);
        System.out.println("MEASURED: second run of SmDataMigrationMain executed "
                + again.migrationsExecuted + " migrations, target " + again.targetSchemaVersion);
        assertEquals(0, again.migrationsExecuted,
                "a runbook that re-runs the step — an init container on a pod restart, an operator "
                        + "being careful — must be a no-op, not a second attempt at V001");
    }

    /**
     * R6 — the external review's finding on {@link JdbcUrls}: its javadoc justifies itself as
     * refusing "at bind time, before a pool exists to print anything", but that claim is about WHERE
     * the refusal happens, not about whether Spring Boot's OWN startup-failure report might still echo
     * the raw property value on its way out. {@code SmDataPropertiesTest} only constructs
     * {@code SmDataProperties.Credentials} directly, so it never measured Boot's reporter at all — this
     * is the first Spring-wired test in the plan, so it is where that gets measured.
     *
     * <p><b>Why {@link SpringApplicationBuilder} and not {@link ApplicationContextRunner}.</b> The
     * runner only hands back {@code context.getStartupFailure()} — the raw exception, never routed
     * through Boot's {@code FailureAnalyzer}/{@code FailureAnalysisReporter} machinery, and
     * {@code BindFailureAnalyzer} itself is package-private, so this module cannot invoke it directly.
     * {@code SpringApplication.run()} is what actually drives that machinery in production, reporting
     * through {@code LoggingFailureAnalysisReporter} — which is the ACTUAL path an operator sees.
     *
     * <p><b>Why raw {@code System.out}/{@code System.err} capture and not {@link LogCapture}.</b>
     * Measured: {@code SpringApplication.run()} re-initialises the logging system on the way in
     * ({@code LoggingApplicationListener}), which detaches any appender a test attached to the root
     * logger BEFORE calling {@code run()} — the rendered analysis then prints through Boot's OWN
     * freshly-installed console appender, invisible to {@link LogCapture}'s collector (measured: the
     * report appears in the build console yet {@code captured.text()} does not contain it). Redirecting
     * the two streams the console appender actually writes to sidesteps that reinitialisation entirely
     * and captures the literal bytes an operator's terminal would show.
     *
     * <p><b>The mechanism, fix round 1 (measured against the shipped
     * {@code spring-boot-3.5.16.jar} with {@code javap}).</b> {@code BindFailureAnalyzer}'s
     * {@code buildDescription} method carries the {@code Value: "%s"} line this class's own javadoc
     * flagged from the constant pool — but that line is reached only inside an
     * {@code if (property != null)} guard. This test's failure is a record compact-constructor
     * {@code IllegalArgumentException} thrown while binding a VALUE OBJECT ({@code Credentials}), and
     * that shape never populates {@code property} on the {@code BindException} the analyzer reads —
     * so the guard is closed and the line is never reached. A positive control confirms the capture
     * mechanism itself is sound rather than blind: binding a plain scalar CONVERSION failure instead
     * ({@code --sm.data.apply.max-attempts=hunter2sentinel}, an int that will not parse) DOES populate
     * {@code property}, and the SAME capture technique here prints {@code Value: "hunter2sentinel"} —
     * proving the negative result below is a fact about THIS failure shape, not a blind spot in how
     * this test captures output.
     *
     * <p><b>The boundary, stated so the negative is never read as universal.</b> This test's finding
     * is {@code shape-dependent, not general}: it holds because {@code JdbcUrls.requireLocation} runs
     * inside a record's compact constructor, which Boot's binder treats as a value-object failure with
     * no attributable {@code ConfigurationProperty}. If that check were ever moved to a
     * {@code Converter}, a plain setter, or a {@code @Validated} bean-validation constraint — any path
     * that leaves {@code property} populated on the resulting {@code BindException} — Boot's own
     * reporter WOULD print the raw URL, exactly as the conversion-failure positive control above
     * demonstrates for a different property. The assertion below would catch that regression the day
     * it happened (it starts failing the moment {@code Value:} plus the sentinel appear in the
     * capture); it is not proof the pattern is safe in general, only that THIS implementation of the
     * refusal is.
     */
    @Test
    void aMalformedRuntimeUrlsRenderedBootFailureAnalysisCarriesNoSentinel() {
        String sentinel = "hunter2sentinel";
        String badUrl = "jdbc:postgresql://alice:" + sentinel + "@localhost:5432/sm_data";

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream tee = new PrintStream(captured, true, StandardCharsets.UTF_8);
        Throwable failure = null;
        try {
            System.setOut(tee);
            System.setErr(tee);
            SpringApplicationBuilder builder = new SpringApplicationBuilder(
                    ApplyPersistenceConfiguration.class, ApplyEngineConfiguration.class)
                    .web(WebApplicationType.NONE);
            try {
                builder.run(
                        "--sm.data.runtime.url=" + badUrl,
                        "--sm.data.runtime.username=sm_dp_runtime",
                        "--sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD,
                        "--sm.data.apply.max-attempts=5").close();
            } catch (Throwable t) {
                failure = t;
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String rendered = captured.toString(StandardCharsets.UTF_8);
        originalOut.println("MEASURED: captured " + rendered.length()
                + " chars of console output during SpringApplication.run()");

        assertNotNull(failure, "a URL that fails JdbcUrls.requireLocation must refuse at BIND time, "
                + "and SpringApplication.run() must propagate that failure to the caller. If this is "
                + "null, requireLocation stopped firing during property binding and the whole claim "
                + "needs re-measuring");
        originalOut.println("MEASURED: SpringApplication.run() failure — " + causeChain(failure));

        int analysisAt = rendered.indexOf("APPLICATION FAILED TO START");
        assertTrue(analysisAt >= 0, "BindFailureAnalyzer produced no rendered report at all on the "
                + "console — the failure shape changed (still a bind failure? re-measure which "
                + "analyzer now applies rather than deleting this test). Captured output was:\n"
                + rendered);
        originalOut.println("MEASURED: Boot's rendered failure analysis —\n"
                + rendered.substring(analysisAt));

        // R6's real question: does Boot's OWN reporter echo the value JdbcUrls refused to name.
        // Asserted at the strength the measurement actually supports — reported as a finding, not
        // silently weakened, if it ever turns up true.
        assertFalse(rendered.contains(sentinel), "R6 MEASURED FINDING: Spring Boot's own "
                + "BindFailureAnalyzer/LoggingFailureAnalysisReporter echoed the raw "
                + "sm.data.runtime.url value into its rendered failure analysis, even though "
                + "JdbcUrls.requireLocation's own exception message never names it. JdbcUrls' "
                + "bind-time posture does not fully hold once Boot's own diagnostics render the "
                + "failure. Rendered console output:\n" + rendered);
    }

    // -------------------------------------------------------------------------------------------

    private ApplicationContextRunner runner(String url) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ApplyPersistenceConfiguration.class,
                        ApplyEngineConfiguration.class)
                .withPropertyValues(
                        "sm.data.runtime.url=" + url,
                        "sm.data.runtime.username=sm_dp_runtime",
                        "sm.data.runtime.password=" + SmDataPostgres.RUNTIME_PASSWORD,
                        "sm.data.apply.max-attempts=5");
    }

    /** An empty database with Phase 0's schema half and deliberately NOT the migrations. */
    private static String freshDatabase(String database) {
        PostgreSQLContainer<?> container = SmDataPostgres.container();
        try (Connection admin = SmDataPostgres.superuser().getConnection();
                Statement s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + database);
            s.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create " + database, e);
        }
        String url = "jdbc:postgresql://" + container.getHost() + ":"
                + container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
        try (Connection admin = SmDataPostgres.dataSource(url, container.getUsername(),
                        container.getPassword()).getConnection();
                Statement s = admin.createStatement()) {
            s.execute("CREATE SCHEMA sm_data AUTHORIZATION sm_dp_owner");
            s.execute("GRANT USAGE ON SCHEMA sm_data TO sm_dp_runtime");
            // plan 010 T2.4: Phase 0's second schema, which V009 creates its tables in
            s.execute("CREATE SCHEMA sm_secret AUTHORIZATION sm_dp_owner");
            s.execute("GRANT USAGE ON SCHEMA sm_secret TO sm_dp_runtime");
            // plan 011 P11-2.1: Phase 0's third schema; SmDataMigrationMain.run now migrates it after sm_data
            s.execute("CREATE SCHEMA sm_metrics AUTHORIZATION sm_dp_owner");
            s.execute("GRANT USAGE ON SCHEMA sm_metrics TO sm_dp_runtime");
        } catch (SQLException e) {
            throw new IllegalStateException("could not lay Phase 0's schema half in " + database, e);
        }
        return url;
    }

    /** Every message in the failure's cause chain — the readiness sentence is somewhere inside it. */
    private static String causeChain(Throwable t) {
        StringBuilder b = new StringBuilder();
        for (Throwable c = t; c != null && c.getCause() != c; c = c.getCause()) {
            b.append(c).append(" | ");
        }
        return b.toString();
    }
}
