package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.hierarchy.HierarchyNameConflictException;
import com.solace.sm.cp.hierarchy.OrganizationRenameService;
import com.solace.sm.cp.identity.AuditOperations;
import java.io.PrintStream;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code OR-131}'s operator command: renames ANY organization. Over HTTP only an install-bound principal can rename, and
 * only its own organization ({@code OR-125} (3)); this is how the others are renamed. Shaped like {@link EntitlementMain}:
 * the {@code sm_cp_runtime} credential from the environment, collaborators built by hand, never a Spring context, nothing
 * echoed but the new name it just stored.
 *
 * <p>Audited as {@link AuditOperations#HIERARCHY_NODE_RENAMED} with client address {@code operator-cli} and no actor, as
 * {@link EntitlementMain} audits {@code ENTITLEMENT_SET} (OR-131 Q1; {@code sm_control} V020). See {@link OrganizationRenameService}.
 */
public final class OrganizationRenameMain {

    public static final String URL_VARIABLE = SmControlLockoutRecoveryMain.URL_VARIABLE;
    public static final String USERNAME_VARIABLE = SmControlLockoutRecoveryMain.USERNAME_VARIABLE;
    public static final String PASSWORD_VARIABLE = SmControlLockoutRecoveryMain.PASSWORD_VARIABLE;

    /** The organization to rename (a UUID). */
    public static final String ORGANIZATION_VARIABLE = "SM_CONTROL_RENAME_ORGANIZATION_ID";

    /** The new name: the API's rules (1 to 100 characters, no control character, stripped). */
    public static final String NAME_VARIABLE = "SM_CONTROL_RENAME_ORGANIZATION_NAME";

    static final String OPERATOR_CLIENT_ADDRESS = SmControlLockoutRecoveryMain.OPERATOR_CLIENT_ADDRESS;

    static final int EXIT_FAILED = 1;
    static final int EXIT_MISSING_VARIABLE = 2;
    /** The organization does not exist. Nothing was written. */
    static final int EXIT_UNKNOWN_ORGANIZATION = 3;
    /** Another organization has that name (ignoring case). Nothing was written. */
    static final int EXIT_NAME_TAKEN = 4;

    private OrganizationRenameMain() {
    }

    /** @param args ignored - configuration arrives through the environment */
    public static void main(String[] args) {
        System.exit(exitCode(System.getenv(), System.out, System.err));
    }

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
        String name = env.get(NAME_VARIABLE);
        if (isBlank(name)) {
            return missing(NAME_VARIABLE, err);
        }
        try {
            String stored = run(url, username, password, organization, name);
            out.println("organization renamed: " + stored);
            return 0;
        } catch (IllegalArgumentException e) {
            // the API's own fixed shape messages; they never contain the value
            err.println(NAME_VARIABLE + " is not a valid name (" + e.getMessage() + ") - nothing was written");
            return EXIT_MISSING_VARIABLE;
        } catch (ResourceNotFoundException e) {
            err.println(ORGANIZATION_VARIABLE + " names no organization - nothing was written");
            return EXIT_UNKNOWN_ORGANIZATION;
        } catch (HierarchyNameConflictException e) {
            err.println("another organization already has that name (compared ignoring case) - nothing was written");
            return EXIT_NAME_TAKEN;
        } catch (RuntimeException e) {
            err.println("renaming the organization failed: " + SmControlMigrationMain.classify(e));
            return EXIT_FAILED;
        }
    }

    /** Renames with the runtime credential; the audit context and the write share one transaction. */
    public static String run(String url, String username, String password, UUID organization, String name) {
        DataSource dataSource = SmControlMigrationMain.dataSource(url, username, password);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new OrganizationRenameService(new JdbcHierarchyWriteRepository(jdbc), new JdbcAuditContext(jdbc), transactions)
                .rename(organization, name, UUID.randomUUID(), OPERATOR_CLIENT_ADDRESS);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int missing(String variable, PrintStream err) {
        err.println(variable + " is not set - the rename command reads its configuration from the environment, "
                + "and will not guess it");
        return EXIT_MISSING_VARIABLE;
    }
}
