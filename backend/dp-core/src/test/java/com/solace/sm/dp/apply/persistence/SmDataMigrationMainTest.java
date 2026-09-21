package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SmDataMigrationMain}'s environment contract, without a database.
 *
 * <p>{@code main} itself calls {@link System#exit}, which a test cannot let happen, so the class
 * delegates to a package-private {@link SmDataMigrationMain#exitCode(Map, PrintStream, PrintStream)}
 * and that is what is measured here. Both streams are injected rather than swapped out from under
 * {@link System}, so a test reads exactly the bytes the step emitted and nothing a neighbouring test
 * happened to print.
 *
 * <p>Two families of case. The first is what a runbook can get wrong before a connection is attempted:
 * a missing variable, and — the external review's finding 1, tightened by its second pass — a URL that is
 * not a bare location: one carrying the credential that is supposed to live in the other two variables,
 * or carrying ANY parameter outside {@link JdbcUrls}' allowlist, since whatever the URL holds is logged. The second is what happens when the connection is
 * attempted and fails: the assertion that matters there is negative, and it is checked against
 * SENTINEL values planted in the environment. Nothing the step prints may contain the password, the
 * username, the host, or the string {@code jdbc:}.
 *
 * <p>Every failure case also captures the JVM's own LOG through {@link LogCapture}, because the step's
 * two streams are only half the surface: Flyway, Hikari and Spring's {@code SimpleDriverDataSource} log
 * on their own account. The split there is measured, not assumed. The two CREDENTIALS never reach a
 * logger. The URL does — {@code SimpleDriverDataSource} names it at DEBUG on the connect attempt, and
 * Flyway names it at INFO on the success path ({@link ApplyPersistenceConfigurationIT} measures that
 * one) — and neither is suppressed, because silencing a library would hide the exposure rather than
 * remove it. Removing it is {@link JdbcUrls}' job: since the URL will be logged, it may not carry a
 * credential.
 */
class SmDataMigrationMainTest {

    private static final String PASSWORD = "s3cr3t-migrator-pw";

    /** Planted in the environment of the connect-failure case and hunted for in every output. */
    private static final String SENTINEL_HOST = "sentinel-host-2e4d.invalid";
    private static final String SENTINEL_USER = "SENTINEL-USER-5d8e";
    private static final String SENTINEL_CONNECT_PW = "SENTINEL-PW-0a2f";

    // -------------------------------------------------------------------------------------------
    // Before a connection: missing variables.
    // -------------------------------------------------------------------------------------------

    @Test
    void aMissingUrlExitsTwoAndNamesTheVariableWithoutEchoingThePassword() {
        Map<String, String> env = complete();
        env.remove(SmDataMigrationMain.URL_VARIABLE);
        assertMissing(SmDataMigrationMain.URL_VARIABLE, env);
    }

    @Test
    void aMissingUsernameExitsTwoAndNamesTheVariableWithoutEchoingThePassword() {
        Map<String, String> env = complete();
        env.remove(SmDataMigrationMain.USERNAME_VARIABLE);
        assertMissing(SmDataMigrationMain.USERNAME_VARIABLE, env);
    }

    @Test
    void aBlankPasswordCountsAsMissingBecauseAnEmptySecretIsAlmostAlwaysAnUnsetOne() {
        Map<String, String> env = complete();
        env.put(SmDataMigrationMain.PASSWORD_VARIABLE, "   ");
        assertMissing(SmDataMigrationMain.PASSWORD_VARIABLE, env);
    }

    // -------------------------------------------------------------------------------------------
    // Before a connection: a URL that carries what the other two variables are for.
    // -------------------------------------------------------------------------------------------

    @Test
    void aUrlCarryingAPasswordParameterIsRefusedBeforeConnectingAndTheSentinelIsPrintedNowhere() {
        String sentinel = "SENTINEL-PW-9f3a";
        String url = "jdbc:postgresql://db.example:5432/sm?password=" + sentinel;
        Run run = run(withUrl(url));

        System.out.println("MEASURED: ?password= URL → exit " + run.code + ", stderr: " + run.stderr());
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code,
                "a credential-bearing URL is a configuration error (2), not a migration failure (1)");
        assertTrue(run.stderr().contains(SmDataMigrationMain.URL_VARIABLE)
                        && run.stderr().contains(SmDataMigrationMain.PASSWORD_VARIABLE),
                "the line must say where the credential belongs instead. Was: " + run.stderr());
        assertNowhere(run, sentinel, url);
    }

    @Test
    void aUrlCarryingAnSslpasswordParameterIsRefusedTooBecauseItIsARealPgjdbcCredential() {
        // Measured (review of plan 004): `sslpassword` is PGProperty.SSL_PASSWORD, the client-key
        // passphrase — a credential the first draft of JdbcUrls did not name, and one Flyway would
        // have logged at INFO inside the URL.
        String sentinel = "SENTINEL-SSLPW-4b6c";
        String url = "jdbc:postgresql://db.example:5432/sm?ssl=true&SSLPASSWORD=" + sentinel;
        Run run = run(withUrl(url));

        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertNowhere(run, sentinel, url);
    }

    @Test
    void aUrlCarryingUserinfoBeforeTheHostIsRefusedBeforeConnectingAndTheSentinelIsPrintedNowhere() {
        String sentinel = "SENTINEL-UI-7c1b";
        String url = "jdbc:postgresql://alice:" + sentinel + "@db.example/sm";
        Run run = run(withUrl(url));

        System.out.println("MEASURED: userinfo URL → exit " + run.code + ", stderr: " + run.stderr());
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code);
        assertTrue(run.stderr().contains(SmDataMigrationMain.URL_VARIABLE), run.stderr());
        assertNowhere(run, sentinel, url);
        assertFalse(run.stderr().contains("alice"),
                "the role in the userinfo is half a credential too. Was: " + run.stderr());
    }

    @Test
    void aUrlCarryingAParameterOutsideTheAllowlistIsRefusedAndNeitherItsNameNorItsValueIsPrinted() {
        // The external review's second-pass finding: a blocklist of three parameter names admitted a
        // value smuggled into any OTHER pgjdbc property — `options=` being the driver's free-text one —
        // which Flyway would then have logged at INFO. The grammar admits only allowlisted names.
        String value = "SENTINEL-OPT-3e9d";
        String url = "jdbc:postgresql://db.example:5432/sm?sslmode=require&options=" + value;
        Run run = run(withUrl(url));

        System.out.println("MEASURED: ?options= URL → exit " + run.code + ", stderr: " + run.stderr());
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains("position 2") && run.stderr().contains("allowlist"),
                "the line locates the parameter and names the rule. Was: " + run.stderr());
        assertFalse(run.stderr().contains("options"),
                "…and does not repeat the parameter's NAME, which can carry a secret as well as a "
                        + "value. Was: " + run.stderr());
        assertNowhere(run, value, url);

        // The name-is-the-secret shape.
        String name = "SENTINEL-NAME-8c47";
        url = "jdbc:postgresql://db.example:5432/sm?" + name + "=1";
        run = run(withUrl(url));
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertNowhere(run, name, url);
    }

    @Test
    void anAllowlistedParameterWithAValueOutsideItsGrammarIsRefusedWithoutPrintingTheValue() {
        String value = "SENTINEL-MODE-1f0b";
        String url = "jdbc:postgresql://db.example:5432/sm?sslmode=" + value;
        Run run = run(withUrl(url));

        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code, run.stderr());
        assertTrue(run.stderr().contains("'sslmode' value"),
                "an ALLOWLISTED name is this code's own word and may be printed. Was: " + run.stderr());
        assertNowhere(run, value, url);
    }

    // -------------------------------------------------------------------------------------------
    // The connection itself fails — Flyway's own message names the URL and the role, so it is never
    // printed. This is the case the whole refactor exists for.
    // -------------------------------------------------------------------------------------------

    @Test
    void anUnreachableHostExitsOneWithClassifiersOnlyAndLeaksNeitherCredentialNorUrl() {
        Map<String, String> env = new LinkedHashMap<>();
        // connectTimeout is a URL PARAMETER, not a credential: it bounds this test rather than
        // relying on the resolver failing a .invalid lookup quickly.
        env.put(SmDataMigrationMain.URL_VARIABLE,
                "jdbc:postgresql://" + SENTINEL_HOST + ":1/sm?connectTimeout=2");
        env.put(SmDataMigrationMain.USERNAME_VARIABLE, SENTINEL_USER);
        env.put(SmDataMigrationMain.PASSWORD_VARIABLE, SENTINEL_CONNECT_PW);

        long startedAt = System.nanoTime();
        Run run = run(env);
        Duration took = Duration.ofNanos(System.nanoTime() - startedAt);

        System.out.println("MEASURED: unreachable host → exit " + run.code + " in " + took.toMillis()
                + " ms, stderr: " + run.stderr());
        System.out.println("MEASURED: JVM log during the failure (" + run.log.lines().size()
                + " events); first line naming a jdbc: URL = " + run.log.firstContaining("jdbc:"));

        assertEquals(SmDataMigrationMain.EXIT_FAILED, run.code,
                "an unreachable database IS a migration failure (1) — a runbook may retry it");
        assertTrue(took.compareTo(Duration.ofSeconds(10)) < 0,
                "the failure must be bounded; took " + took.toMillis() + " ms");
        assertTrue(run.stderr().contains(SmDataMigrationMain.NO_MESSAGE_TEXT.strip()),
                "the line must say WHY it is thin, or the next reader deletes the classifier. Was: "
                        + run.stderr());
        assertTrue(run.stderr().contains("SQLSTATE") || run.stderr().matches("(?s).*Exception.*"),
                "the line must carry a SQLSTATE or the exception's simple name. Was: " + run.stderr());

        for (String sentinel : List.of(SENTINEL_HOST, SENTINEL_USER, SENTINEL_CONNECT_PW)) {
            assertFalse(run.stdout().contains(sentinel) || run.stderr().contains(sentinel),
                    "the step printed " + sentinel + ". out=" + run.stdout() + " err=" + run.stderr());
        }
        assertFalse(run.stderr().contains("jdbc:") || run.stdout().contains("jdbc:"),
                "no JDBC URL reaches either stream — Flyway's own failure message would carry one, "
                        + "which is why the exception is classified rather than printed. Was: "
                        + run.stderr());

        // The LOG is a different surface, and the split below is the measured truth rather than the
        // convenient one. The two CREDENTIALS are absent from it — nothing hands them to a logger.
        for (String credential : List.of(SENTINEL_USER, SENTINEL_CONNECT_PW)) {
            assertFalse(run.log.text().contains(credential),
                    "a library LOGGED the " + (credential.equals(SENTINEL_USER) ? "role" : "password")
                            + ": " + run.log.firstContaining(credential));
        }
        // The URL is NOT absent, and this build does not pretend otherwise. Measured: Spring's
        // SimpleDriverDataSource prints it at DEBUG on the connect attempt, and Flyway prints it at
        // INFO on the success path (ApplyPersistenceConfigurationIT measures that one). Neither is
        // suppressed — silencing them would hide the exposure instead of removing it. What removes
        // it is JdbcUrls: since the URL WILL be logged, the URL may not carry a credential.
        String logged = run.log.firstContaining(SENTINEL_HOST);
        assertNotNull(logged,
                "if no library logs the URL any more, this assertion should be inverted "
                        + "deliberately — JdbcUrls' justification rests on the measurement");
        assertTrue(logged.startsWith("DEBUG"),
                "…and it is DEBUG-level here, i.e. below a production root level. Was: " + logged);
    }

    @Test
    void anUnknownUrlSchemeExitsTwoAsAMalformedLocationBeforeAnyDriverIsConsulted() {
        Map<String, String> env = withUrl("jdbc:nope://x/y");
        Run run = run(env);

        System.out.println("MEASURED: jdbc:nope → exit " + run.code + ", stderr: " + run.stderr());
        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code,
                "a URL outside the location grammar is a configuration error (2): the variable IS set, "
                        + "it just does not name a PostgreSQL location");
        assertTrue(run.stderr().contains("scheme other than pgjdbc's"),
                "…and it must say which part of the grammar was missed. Was: " + run.stderr());
        assertFalse(run.stderr().contains("jdbc:nope") || run.stderr().contains("jdbc:"), run.stderr());
        assertFalse(run.stderr().contains(PASSWORD), run.stderr());
    }

    @Test
    void theDriverResolutionFailureIsStillReachableAndStillNamesNoUrl() throws java.sql.SQLException {
        // Fix round 2: grammar-first means a non-pgjdbc scheme like "jdbc:nope://" is now refused by
        // JdbcUrls itself (IllegalArgumentException, "a scheme other than pgjdbc's") before this
        // method ever calls DriverManager — so it can no longer drive NoDriverRegistered. The case
        // NoDriverRegistered documents — a distribution shipped without the driver — is reproduced
        // more faithfully anyway: a GRAMMAR-CONFORMING URL, with the postgres driver itself
        // deregistered for the length of the call. Deregistering the exact registered instance (read
        // back from DriverManager, not a fresh `new Driver()`, which is a distinct, unregistered
        // object) and re-registering it in a finally leaves every other test's driver resolution
        // unaffected — verified by the surrounding suite staying green.
        String url = "jdbc:postgresql://db.example:5432/sm";
        java.sql.Driver registered = java.sql.DriverManager.getDriver(url);
        java.sql.DriverManager.deregisterDriver(registered);
        try {
            SmDataMigrationMain.NoDriverRegistered e = assertThrows(
                    SmDataMigrationMain.NoDriverRegistered.class,
                    () -> SmDataMigrationMain.dataSource(url, SENTINEL_USER, SENTINEL_CONNECT_PW));
            assertFalse(e.getMessage().contains("jdbc:") || e.getMessage().contains("db.example"),
                    e.getMessage());
        } finally {
            java.sql.DriverManager.registerDriver(registered);
        }
        // The driver is back: confirms the finally ran and no other test is left holding a
        // deregistered driver.
        assertNotNull(java.sql.DriverManager.getDriver(url));
    }

    // -------------------------------------------------------------------------------------------
    // Fix round 1 finding 1: JdbcUrls.requireLocation must guard EVERY path to Flyway, not only the
    // environment-variable reader in exitCode. run()/dataSource() are the choke point every caller —
    // including SmDataPostgres's own bootstrap — passes through on the way to Flyway or
    // SimpleDriverDataSource, both of which log the URL. These two tests call run()/dataSource()
    // DIRECTLY, bypassing exitCode's own (separate, and still-present) check entirely, to prove the
    // choke point itself refuses rather than relying on the env-path call that happens to run first.
    // -------------------------------------------------------------------------------------------

    @Test
    void runCalledDirectlyWithAUrlOutsideTheAllowlistIsRefusedByPositionWithoutEchoingAnything() {
        String value = "SENTINEL-RUN-OPT-7d2a";
        String url = "jdbc:postgresql://db.example:5432/sm?sslmode=require&options=" + value;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SmDataMigrationMain.run(url, SENTINEL_USER, SENTINEL_CONNECT_PW),
                "run() must refuse before ever reaching Flyway.migrate(), not merely the env-variable "
                        + "reader in exitCode() — this call bypasses exitCode entirely");

        System.out.println("MEASURED: run() called directly with ?options= → " + e.getMessage());
        assertTrue(e.getMessage().contains("position 2") && e.getMessage().contains("allowlist"),
                "the refusal locates the parameter and names the rule. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains("options"),
                "the parameter's NAME is not repeated. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains(value),
                "the parameter's VALUE is not repeated. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains(url),
                "the URL itself is not repeated. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains("jdbc:"),
                "not even the literal scheme is repeated. Was: " + e.getMessage());
    }

    @Test
    void dataSourceCalledDirectlyWithAUrlOutsideTheAllowlistIsRefusedBeforeAnySimpleDriverDataSourceIsBuilt() {
        // The harness's own bootstrap (SmDataPostgres.migrate()) reaches Flyway through exactly this
        // method with no exitCode() in between it and Flyway.migrate() — this is the call the review
        // measured as unguarded (Testcontainers' getJdbcUrl() carries ?loggerLevel=OFF, which
        // JdbcUrls.ALLOWED does not admit). SmDataPostgres now builds its URLs without that parameter
        // (see SmDataPostgres.jdbcUrl()); this test proves the choke point itself would have refused
        // it if it had not.
        String value = "SENTINEL-DS-LVL-3f81";
        String url = "jdbc:postgresql://db.example:5432/sm?loggerLevel=" + value;

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SmDataMigrationMain.dataSource(url, SENTINEL_USER, SENTINEL_CONNECT_PW));

        System.out.println("MEASURED: dataSource() called directly with ?loggerLevel= → " + e.getMessage());
        assertTrue(e.getMessage().contains("position 1") && e.getMessage().contains("allowlist"),
                "the refusal locates the parameter and names the rule. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains("loggerLevel"),
                "the parameter's NAME is not repeated. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains(value),
                "the parameter's VALUE is not repeated. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains(url),
                "the URL itself is not repeated. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains("jdbc:"),
                "not even the literal scheme is repeated. Was: " + e.getMessage());
    }

    // -------------------------------------------------------------------------------------------
    // Fix round 2: the driver-first ordering fix round 1 left in place was measured to leak. Three
    // jdbc:postgresql:// URLs fail pgjdbc's OWN parseURL (called from Driver.acceptsURL, which
    // DriverManager.getDriver invokes on every registered driver) and each logs a java.util.logging
    // WARNING containing the offending URL — including a "?password=" value and the alice:hunter2@
    // userinfo form — BEFORE JdbcUrls.requireLocation ever runs, if the grammar check runs second.
    // These two tests drive that exact case through BOTH dataSource() and run(), with the sentinel
    // hunted for on two independent surfaces: the exception message (this class's own words) and the
    // java.util.logging records the org.postgresql logger publishes directly, captured via
    // JulCapture — NOT the real process streams; java.util.logging bypasses this codebase's
    // Logback-routed LogCapture entirely, so LogCapture would see nothing here even if the leak were
    // still present, which is why it is not used below.
    // -------------------------------------------------------------------------------------------

    @Test
    void aGrammarViolatingUrlCarryingASentinelPasswordNeverReachesPgjdbcsParseUrlThroughDataSource() {
        // Malformed host (no "/database" at all) AND a password parameter — the first of the three
        // measured JUL-leaking shapes, chosen because it is also the shape JdbcUrls' own grammar
        // refuses earliest (no slash after the host).
        String sentinel = "SENTINEL-JUL-DS-PW-9e21";
        String url = "jdbc:postgresql://db.example?password=" + sentinel;

        JulCapture.Captured<IllegalArgumentException> captured = JulCapture.during(() ->
                assertThrows(IllegalArgumentException.class,
                        () -> SmDataMigrationMain.dataSource(url, "irrelevant", "irrelevant")));

        System.out.println("MEASURED: dataSource() with malformed host + ?password= → "
                + captured.value().getMessage() + " | pgjdbc JUL records emitted: "
                + captured.lines().size());
        assertRefusedWithoutLeaking(captured, sentinel, url);
        assertEquals(0, captured.lines().size(),
                "pgjdbc's parseURL must never run at all — zero org.postgresql JUL records is the "
                        + "proof, not merely 'none contained the sentinel'. Was: " + captured.lines());
    }

    @Test
    void aGrammarViolatingUrlCarryingASentinelPasswordNeverReachesPgjdbcsParseUrlThroughRun() {
        // The userinfo form specifically — this is the exact shape JdbcUrls' own javadoc names as
        // "refused without echoing", and the one whose JUL warning would have printed the credential
        // through a completely different logger (org.postgresql.util.PGPropertyUtil) than the other
        // two, so covering it separately matters.
        String sentinel = "SENTINEL-JUL-RUN-PW-4c7f";
        String url = "jdbc:postgresql://alice:" + sentinel + "@db.example/sm";

        JulCapture.Captured<IllegalArgumentException> captured = JulCapture.during(() ->
                assertThrows(IllegalArgumentException.class,
                        () -> SmDataMigrationMain.run(url, "irrelevant", "irrelevant"),
                        "run() must refuse before Flyway.migrate() ever sees the URL"));

        System.out.println("MEASURED: run() with userinfo → " + captured.value().getMessage()
                + " | pgjdbc JUL records emitted: " + captured.lines().size());
        assertRefusedWithoutLeaking(captured, sentinel, url);
        assertFalse(captured.value().getMessage().contains("alice"),
                "the role in the userinfo is half a credential too. Was: " + captured.value().getMessage());
        assertEquals(0, captured.lines().size(),
                "pgjdbc's parseURL must never run at all. Was: " + captured.lines());
    }

    /**
     * The two invariants both leak-proof tests share: the refusal's own message names neither the
     * sentinel nor the URL nor the literal {@code jdbc:}, AND — the surface the round 2 finding
     * actually broke — no {@code java.util.logging} record published under the {@code org.postgresql}
     * logger (captured by {@link JulCapture}, formatted exactly as a real {@link
     * java.util.logging.Handler} would print it) names the sentinel either. Fix round 3 minor 2:
     * this javadoc previously said the second surface was "the REAL process streams" — it is not;
     * {@code JulCapture} never touches {@link System#err} or {@link System#out} at all, only the
     * logger, for the reason its own class javadoc gives.
     */
    private static void assertRefusedWithoutLeaking(
            JulCapture.Captured<IllegalArgumentException> captured, String sentinel, String url) {
        String message = captured.value().getMessage();
        assertFalse(message.contains(sentinel), "the message echoed the sentinel. Was: " + message);
        assertFalse(message.contains(url), "the message echoed the URL. Was: " + message);
        assertFalse(message.contains("jdbc:"), "the message echoed the scheme. Was: " + message);
        for (JulCapture.Line line : captured.lines()) {
            assertFalse(line.toString().contains(sentinel),
                    "a JUL record leaked the sentinel: " + line);
        }
    }

    /**
     * Captures every {@code java.util.logging} record published under the {@code org.postgresql}
     * logger while a block runs — the surface {@link LogCapture} (Logback-routed) cannot see, and the
     * one the round 2 finding measured leaking. A {@link java.util.logging.Handler} attached directly
     * to the logger is used rather than redirecting {@link System#err}: pgjdbc's static initializer
     * may already have handed a {@link java.util.logging.ConsoleHandler} the ORIGINAL
     * {@code System.err} reference before this method ever runs, in which case swapping
     * {@code System.setErr} afterwards would not be seen by that handler at all — attaching a handler
     * to the logger itself receives every record regardless of when any {@code ConsoleHandler} was
     * constructed or which stream it was bound to.
     */
    private static final class JulCapture {

        private JulCapture() {
        }

        record Line(String level, String logger, String message) {
            @Override
            public String toString() {
                return level + " " + logger + " — " + message;
            }
        }

        record Captured<T>(T value, List<Line> lines) {
        }

        static <T> Captured<T> during(java.util.function.Supplier<T> body) {
            java.util.logging.Logger pg = java.util.logging.Logger.getLogger("org.postgresql");
            java.util.logging.Level original = pg.getLevel();
            Collector collector = new Collector();
            pg.setLevel(java.util.logging.Level.ALL);
            pg.addHandler(collector);
            try {
                return new Captured<>(body.get(), List.copyOf(collector.lines));
            } finally {
                pg.removeHandler(collector);
                pg.setLevel(original);
            }
        }

        private static final class Collector extends java.util.logging.Handler {

            /**
             * Fix round 3 minor 1: {@code record.getMessage()} alone is the RAW pattern
             * ({@code "JDBC URL invalid port number: {0}"}), not what a handler prints — the sentinel
             * lives in {@code record.getParameters()}, substituted in only by a {@link
             * java.util.logging.Formatter}. Capturing the raw pattern made the sentinel-scan half of
             * {@code assertRefusedWithoutLeaking} vacuous: it would pass even with the leak fully
             * present, because the sentinel never appears in the unformatted string. {@code
             * Formatter.formatMessage(LogRecord)} is public precisely for callers like this one and is
             * what every real {@code Handler} (a {@code ConsoleHandler} included) calls internally, so
             * using it here makes the capture see exactly what a real handler would have printed.
             */
            private static final java.util.logging.Formatter FORMATTER =
                    new java.util.logging.SimpleFormatter();

            private final List<Line> lines = new ArrayList<>();

            @Override
            public void publish(java.util.logging.LogRecord record) {
                lines.add(new Line(record.getLevel().toString(), record.getLoggerName(),
                        FORMATTER.formatMessage(record)));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        }
    }

    // -------------------------------------------------------------------------------------------

    private static Map<String, String> complete() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(SmDataMigrationMain.URL_VARIABLE, "jdbc:postgresql://db:5432/sm");
        env.put(SmDataMigrationMain.USERNAME_VARIABLE, "sm_dp_migrator");
        env.put(SmDataMigrationMain.PASSWORD_VARIABLE, PASSWORD);
        return env;
    }

    private static Map<String, String> withUrl(String url) {
        Map<String, String> env = complete();
        env.put(SmDataMigrationMain.URL_VARIABLE, url);
        return env;
    }

    /** Both injected streams, the exit code, and everything the JVM logged while it ran. */
    private record Run(int code, ByteArrayOutputStream out, ByteArrayOutputStream err,
            LogCapture.Captured<Integer> log) {

        String stdout() {
            return out.toString(StandardCharsets.UTF_8).strip();
        }

        String stderr() {
            return err.toString(StandardCharsets.UTF_8).strip();
        }
    }

    private static Run run(Map<String, String> env) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        LogCapture.Captured<Integer> log =
                LogCapture.during(() -> SmDataMigrationMain.exitCode(env, outStream, errStream));
        return new Run(log.value(), out, err, log);
    }

    /** The sentinel appears in neither stream, nor does the URL that carried it. */
    private static void assertNowhere(Run run, String sentinel, String url) {
        for (String printed : List.of(run.stdout(), run.stderr())) {
            assertFalse(printed.contains(sentinel),
                    "the credential inside the URL must never be printed. Was: " + printed);
            assertFalse(printed.contains(url),
                    "…and neither must the URL that carried it. Was: " + printed);
            assertFalse(printed.contains("jdbc:"), "no URL at all. Was: " + printed);
        }
        assertFalse(run.log.text().contains(sentinel),
                "nothing may LOG it either — the refusal happens before any library sees the URL. "
                        + "Was: " + run.log.firstContaining(sentinel));
    }

    /** Runs {@code exitCode} and reads both the code and what was printed. */
    private static void assertMissing(String variable, Map<String, String> env) {
        Run run = run(env);
        System.out.println("MEASURED: missing " + variable + " → exit " + run.code + ", stderr: "
                + run.stderr());

        assertEquals(SmDataMigrationMain.EXIT_MISSING_VARIABLE, run.code,
                "a missing variable is a configuration error (2), not a migration failure (1) — a "
                        + "runbook that retries on 1 must not retry on this");
        assertTrue(run.stderr().contains(variable),
                "the message must name the variable that is missing. Was: " + run.stderr());
        assertFalse(run.stderr().contains(PASSWORD),
                "the failure must not echo the migrator password — the whole point of finding 1 is "
                        + "that this secret exists in as few places as possible. Was: " + run.stderr());
    }
}
