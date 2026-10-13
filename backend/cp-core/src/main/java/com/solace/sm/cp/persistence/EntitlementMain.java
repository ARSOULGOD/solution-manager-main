package com.solace.sm.cp.persistence;

import com.solace.sm.cp.catalog.EntitlementService;
import com.solace.sm.cp.identity.AuditOperations;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code OR-48}'s operator command: sets (or changes) one organization's entitlement to one solution
 * ({@code OR-40}). Nothing over HTTP can do this in v1, and no catalog verb is added or bent: the authority is the
 * runtime credential on the host, exactly as {@link SmControlLockoutRecoveryMain} (which this mirrors, down to its
 * exit codes and its refusal to echo a secret or a URL).
 *
 * <p><b>Standalone, and never a Spring context.</b> It connects with the {@code sm_cp_runtime} credential from the
 * environment and constructs its collaborators by hand, so it can never start a second apply worker or recovery
 * sweep against the live database (plan 010 Global Constraints; {@code EntitlementMainTest} pins that the class
 * names no context type).
 *
 * <p><b>Audited with no actor.</b> The write is {@link AuditOperations#ENTITLEMENT_SET} with audit actor
 * {@code NULL} and client address {@link #OPERATOR_CLIENT_ADDRESS}, which V013's table-scoped exemption admits on
 * {@code entitlement} only. The row's own {@code set_by} column is {@code NOT NULL REFERENCES principal}, so the
 * command names the person on whose standing it acts ({@link #SET_BY_VARIABLE}, a local active principal of the
 * same organization); that is recorded on the row and is never the audit actor.
 *
 * <p>Reads its configuration from the environment only, never the command line; a value is never printed back
 * except the solution and limit it just set.
 */
public final class EntitlementMain {

    public static final String URL_VARIABLE = SmControlLockoutRecoveryMain.URL_VARIABLE;
    public static final String USERNAME_VARIABLE = SmControlLockoutRecoveryMain.USERNAME_VARIABLE;
    public static final String PASSWORD_VARIABLE = SmControlLockoutRecoveryMain.PASSWORD_VARIABLE;

    /** The organization the entitlement belongs to (a UUID). */
    public static final String ORGANIZATION_VARIABLE = "SM_CONTROL_ENTITLEMENT_ORGANIZATION_ID";

    /** The solution entitled: a catalog solution name, {@code ^[a-z][a-z0-9-]{0,63}$}. */
    public static final String SOLUTION_VARIABLE = "SM_CONTROL_ENTITLEMENT_SOLUTION";

    /** How many instances of it the organization may run (a positive integer). */
    public static final String MAX_INSTANCES_VARIABLE = "SM_CONTROL_ENTITLEMENT_MAX_INSTANCES";

    /** The username of the local, active principal of that organization the row is attributed to ({@code set_by}). */
    public static final String SET_BY_VARIABLE = "SM_CONTROL_ENTITLEMENT_SET_BY";

    /** {@code change_audit.client_address} recorded for every write this command performs. */
    static final String OPERATOR_CLIENT_ADDRESS = SmControlLockoutRecoveryMain.OPERATOR_CLIENT_ADDRESS;

    /** The write failed. */
    static final int EXIT_FAILED = 1;

    /** A required variable was absent, blank or malformed. Decided before any connection is attempted. */
    static final int EXIT_MISSING_VARIABLE = 2;

    /** The {@code set_by} principal is not an active local principal of that organization. Nothing was written. */
    static final int EXIT_UNKNOWN_PRINCIPAL = 3;

    private EntitlementMain() {
    }

    /**
     * @param args ignored - configuration arrives through the environment, never the command line
     */
    public static void main(String[] args) {
        System.exit(exitCode(System.getenv(), System.out, System.err));
    }

    /**
     * {@link #main} minus {@link System#exit}, with both streams injected so a test can read every byte emitted.
     *
     * @return 0 when the entitlement was set, {@value #EXIT_FAILED}, {@value #EXIT_MISSING_VARIABLE} or {@value
     *         #EXIT_UNKNOWN_PRINCIPAL}
     */
    static int exitCode(Map<String, String> env, PrintStream out, PrintStream err) {
        String url = env.get(URL_VARIABLE);
        String username = env.get(USERNAME_VARIABLE);
        String password = env.get(PASSWORD_VARIABLE);
        if (isBlank(url)) {
            return missing(URL_VARIABLE, err);
        }
        if (isBlank(username)) {
            return missing(USERNAME_VARIABLE, err);
        }
        if (isBlank(password)) {
            return missing(PASSWORD_VARIABLE, err);
        }
        try {
            JdbcUrls.requireLocation(url);
        } catch (IllegalArgumentException e) {
            // JdbcUrls' own words: the rule and the allowlist, never the URL, a value or a parameter name.
            err.println(URL_VARIABLE + " is not a bare location: " + e.getMessage()
                    + ". The credential belongs in " + USERNAME_VARIABLE + " / " + PASSWORD_VARIABLE);
            return EXIT_MISSING_VARIABLE;
        }
        String organizationText = env.get(ORGANIZATION_VARIABLE);
        if (isBlank(organizationText)) {
            return missing(ORGANIZATION_VARIABLE, err);
        }
        UUID organization;
        try {
            organization = UUID.fromString(organizationText.trim());
        } catch (IllegalArgumentException e) {
            err.println(ORGANIZATION_VARIABLE + " is not a valid UUID");
            return EXIT_MISSING_VARIABLE;
        }
        String solution = env.get(SOLUTION_VARIABLE);
        if (isBlank(solution)) {
            return missing(SOLUTION_VARIABLE, err);
        }
        solution = solution.trim();
        if (!EntitlementService.isValidSolution(solution)) {
            err.println(SOLUTION_VARIABLE + " is not a catalog solution name (lowercase letters, digits and '-', "
                    + "starting with a letter, at most 64 characters)");
            return EXIT_MISSING_VARIABLE;
        }
        String maxText = env.get(MAX_INSTANCES_VARIABLE);
        if (isBlank(maxText)) {
            return missing(MAX_INSTANCES_VARIABLE, err);
        }
        int maxInstances;
        try {
            maxInstances = Integer.parseInt(maxText.trim());
        } catch (NumberFormatException e) {
            maxInstances = 0;
        }
        if (maxInstances < 1) {
            err.println(MAX_INSTANCES_VARIABLE + " is not a positive integer");
            return EXIT_MISSING_VARIABLE;
        }
        String setBy = env.get(SET_BY_VARIABLE);
        if (isBlank(setBy)) {
            return missing(SET_BY_VARIABLE, err);
        }

        try {
            run(url, username, password, organization, solution, maxInstances, setBy.trim());
            out.println("entitlement set: solution " + solution + ", max instances " + maxInstances);
            return 0;
        } catch (EntitlementService.UnknownSetByPrincipalException e) {
            err.println(SET_BY_VARIABLE + " does not name an active local principal of that organization "
                    + "- nothing was written");
            return EXIT_UNKNOWN_PRINCIPAL;
        } catch (RuntimeException e) {
            err.println("setting the entitlement failed: " + SmControlMigrationMain.classify(e));
            return EXIT_FAILED;
        }
    }

    /**
     * Applies the entitlement with the runtime credential, audited as {@link AuditOperations#ENTITLEMENT_SET} with
     * no actor and a fresh correlation id. The audit context and the write run in one transaction, so the trigger
     * sees the context.
     *
     * @throws EntitlementService.UnknownSetByPrincipalException nothing was written
     */
    public static void run(String url, String username, String password, UUID organization, String solution,
            int maxInstances, String setByUsername) {
        DataSource dataSource = SmControlMigrationMain.dataSource(url, username, password);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new EntitlementService(new JdbcEntitlementRepository(jdbc), new JdbcPrincipalRepository(jdbc),
                new JdbcAuditContext(jdbc), transactions, Clock.systemUTC())
                .set(organization, solution, maxInstances, setByUsername, UUID.randomUUID(), OPERATOR_CLIENT_ADDRESS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int missing(String variable, PrintStream err) {
        err.println(variable + " is not set - the entitlement command reads its configuration from the "
                + "environment, and will not guess it");
        return EXIT_MISSING_VARIABLE;
    }
}
