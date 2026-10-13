package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ScopePath;
import com.solace.sm.cp.api.identity.LocalCredentials;
import com.solace.sm.cp.api.identity.LocalLoginResult;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.cp.api.target.TargetView;
import com.solace.sm.cp.authz.ScopeEvaluatingAuthorizationService;
import com.solace.sm.cp.authz.ScopePathResolver;
import com.solace.sm.cp.identity.CredentialHasher;
import com.solace.sm.cp.identity.LocalLoginService;
import com.solace.sm.cp.identity.LoginRateLimitPolicy;
import com.solace.sm.cp.identity.SessionService;
import com.solace.sm.cp.target.TargetService;
import java.io.PrintStream;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The control-plane half of target registration (plan 010 T2.7; {@code OR-28}). Target registration is the
 * provisioning path for a Target: {@code target.create} has no endpoint anywhere in the contract (§5.5). It writes
 * both planes and no single module may ({@code I7}), so it is two standalone {@code main}s; this is the CP one, and
 * {@code com.solace.sm.dp.apply.persistence.TargetConnectionMain} is the DP one.
 *
 * <p><b>Shaped like {@link SmControlLockoutRecoveryMain}</b>: the {@code sm_cp_runtime} credential comes from the
 * environment, collaborators are constructed by hand, and <b>no Spring context is ever started</b> (so it can never
 * start a second worker pool; the CP has none, but the rule is the same on both planes). Nothing it is given is echoed.
 *
 * <p><b>It authenticates a principal, and authorizes the verb.</b> The username and the credential are read from the
 * console (never a flag, never an environment variable) and checked by {@link LocalLoginService}, the very service
 * the HTTP login uses, so the same durable throttle and the same {@code LOGIN_*} audit apply. Then
 * {@code require(TARGET_CREATE)} at the ownership scope the target will be owned at, a verb at a scope, never a role
 * ({@code OR-8}). The session it opened is revoked before it exits.
 *
 * <p><b>What it prints.</b> The new {@code targetId} on stdout, and nothing else. Prompts and diagnostics go to
 * stderr/the terminal, so a script can capture stdout.
 *
 * <p><b>Idempotent</b> on the organization and the name: the same registration again prints the existing
 * {@code targetId} and writes nothing; a differing one under the same name is refused.
 */
public final class TargetRegistrationMain {

    /** {@code jdbc:postgresql://…} — the SM database. Credentials do NOT belong in it. */
    public static final String URL_VARIABLE = "SM_CONTROL_RUNTIME_URL";
    /** The runtime role, normally {@code sm_cp_runtime}. */
    public static final String USERNAME_VARIABLE = "SM_CONTROL_RUNTIME_USERNAME";
    /** That role's secret. Read, used, and never echoed. */
    public static final String PASSWORD_VARIABLE = "SM_CONTROL_RUNTIME_PASSWORD";

    /**
     * The organization the operator signs in to, by id or by name (OR-125 (4): a username is unique per organization,
     * so a login names both). Not a secret, so it is a variable; the username and credential still come from the
     * terminal.
     */
    public static final String ORGANIZATION_VARIABLE = "SM_TARGET_ORGANIZATION";
    /** The target's name, unique within the organization. */
    public static final String NAME_VARIABLE = "SM_TARGET_NAME";
    /** The MI runtime; defaults to {@code docker} (v1's one pair). */
    public static final String KIND_VARIABLE = "SM_TARGET_KIND";
    /** How SM reaches it; defaults to {@code ssh-docker} (v1's one pair). */
    public static final String TRANSPORT_VARIABLE = "SM_TARGET_TRANSPORT";
    /** Optional: own the target at this organization unit. Absent (with no environment) means the organization. */
    public static final String OWNER_UNIT_VARIABLE = "SM_TARGET_OWNER_ORGANIZATION_UNIT_ID";
    /** Optional: own the target at this environment (requires {@link #OWNER_UNIT_VARIABLE}, its parent). */
    public static final String OWNER_ENVIRONMENT_VARIABLE = "SM_TARGET_OWNER_ENVIRONMENT_ID";
    /** Optional: the id to register under (so both commands can be scripted with one id); minted when absent. */
    public static final String TARGET_ID_VARIABLE = "SM_TARGET_ID";

    /** {@code change_audit.client_address} recorded for every write this command performs. */
    static final String OPERATOR_CLIENT_ADDRESS = "127.0.0.1";

    /** The registration failed (a database error, or a name already registered differently). */
    static final int EXIT_FAILED = 1;
    /** A variable is missing or malformed, or the request is not a registrable target. */
    static final int EXIT_MISSING_VARIABLE = 2;
    /** The login was refused, the credential must be rotated first, or the verb is not held at that scope. */
    static final int EXIT_REFUSED = 3;
    /** No terminal to read the username and credential from. */
    static final int EXIT_NO_CONSOLE = 4;

    /** Where the username and credential come from: a terminal, never an argument or a variable. */
    public interface Credentials {

        /** The login name, or {@code null} when there is no terminal. */
        String readUsername();

        /** The credential, or {@code null} when there is no terminal. The caller zeroises it. */
        char[] readCredential();
    }

    /** The real terminal, or one that reports there is none. */
    static Credentials systemConsole() {
        java.io.Console console = System.console();
        if (console == null) {
            return new Credentials() {
                @Override
                public String readUsername() {
                    return null;
                }

                @Override
                public char[] readCredential() {
                    return null;
                }
            };
        }
        return new Credentials() {
            @Override
            public String readUsername() {
                return console.readLine("Username: ");
            }

            @Override
            public char[] readCredential() {
                return console.readPassword("Credential: ");
            }
        };
    }

    private TargetRegistrationMain() {
    }

    /** @param args ignored — configuration arrives through the environment, never the command line */
    public static void main(String[] args) {
        System.exit(exitCode(System.getenv(), systemConsole(), System.out, System.err));
    }

    /** {@link #main} minus {@link System#exit}, with both streams injected so a test can read every byte. */
    static int exitCode(Map<String, String> env, Credentials credentials, PrintStream out, PrintStream err) {
        for (String name : new String[] {URL_VARIABLE, USERNAME_VARIABLE, PASSWORD_VARIABLE, ORGANIZATION_VARIABLE,
                NAME_VARIABLE}) {
            if (isBlank(env.get(name))) {
                err.println(name + " is not set: the target registration reads its configuration from the "
                        + "environment, and will not guess it");
                return EXIT_MISSING_VARIABLE;
            }
        }
        try {
            JdbcUrls.requireLocation(env.get(URL_VARIABLE));
        } catch (IllegalArgumentException e) {
            err.println(URL_VARIABLE + " is not a bare location: " + e.getMessage()
                    + ". The credential belongs in " + USERNAME_VARIABLE + " / " + PASSWORD_VARIABLE);
            return EXIT_MISSING_VARIABLE;
        }
        UUID unit;
        UUID environment;
        UUID requestedId;
        try {
            unit = optionalUuid(env, OWNER_UNIT_VARIABLE);
            environment = optionalUuid(env, OWNER_ENVIRONMENT_VARIABLE);
            requestedId = optionalUuid(env, TARGET_ID_VARIABLE);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return EXIT_MISSING_VARIABLE;
        }
        if (environment != null && unit == null) {
            err.println(OWNER_ENVIRONMENT_VARIABLE + " needs " + OWNER_UNIT_VARIABLE + ", the environment's parent");
            return EXIT_MISSING_VARIABLE;
        }

        String organization = env.get(ORGANIZATION_VARIABLE);
        String username = credentials.readUsername();
        char[] credential = username == null ? null : credentials.readCredential();
        if (username == null || credential == null) {
            err.println("no terminal: the username and the credential are read from the console, never from a "
                    + "flag or a variable");
            return EXIT_NO_CONSOLE;
        }
        try {
            Spec spec = new Spec(requestedId, env.get(NAME_VARIABLE), orDefault(env.get(KIND_VARIABLE), "docker"),
                    orDefault(env.get(TRANSPORT_VARIABLE), "ssh-docker"), unit, environment);
            TargetView registered = run(env.get(URL_VARIABLE), env.get(USERNAME_VARIABLE),
                    env.get(PASSWORD_VARIABLE), organization.strip(), username.strip(), credential, spec);
            out.println(registered.targetId());
            return 0;
        } catch (LoginRefusedException e) {
            err.println("refused: the login was not accepted");
            return EXIT_REFUSED;
        } catch (CredentialMustRotateException e) {
            err.println("refused: this principal must rotate its credential before it can register a target");
            return EXIT_REFUSED;
        } catch (AccessDeniedException | ResourceNotFoundException e) {
            // One message for both: a caller who may not create a target at that scope is not told whether the
            // scope exists.
            err.println("refused: not permitted to register a target at that scope");
            return EXIT_REFUSED;
        } catch (IllegalArgumentException e) {
            err.println("the target registration was not accepted: " + e.getMessage());
            return EXIT_MISSING_VARIABLE;
        } catch (IllegalStateException e) {
            err.println("the target registration was not accepted: " + e.getMessage());
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            err.println("the target registration failed: " + SmControlMigrationMain.classify(e));
            return EXIT_FAILED;
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    /** What to register; the organization is the authenticated principal's own, never an input. */
    public record Spec(UUID targetId, String name, String kind, String transport, UUID organizationUnitId,
            UUID environmentId) {
    }

    /** A principal whose credential is flagged for rotation may not act until it has rotated it. */
    static final class CredentialMustRotateException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CredentialMustRotateException() {
            super("the credential must be rotated first");
        }
    }

    /**
     * Authenticates, authorizes {@code target.create} at the ownership scope, and registers (or finds) the target,
     * then revokes the session it opened.
     *
     * @throws LoginRefusedException the credential was not accepted (throttled and audited like the HTTP login)
     * @throws CredentialMustRotateException the principal must rotate its credential first
     * @throws AccessDeniedException the verb is not held at the scope
     * @throws ResourceNotFoundException the scope does not exist in the principal's organization
     */
    public static TargetView run(String url, String dbUsername, String dbPassword, String loginOrganization,
            String username, char[] credential, Spec spec) {
        DataSource dataSource = SmControlMigrationMain.dataSource(url, dbUsername, dbPassword);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        Clock clock = Clock.systemUTC();
        JdbcAuditContext auditContext = new JdbcAuditContext(jdbc);
        JdbcPrincipalRepository principals = new JdbcPrincipalRepository(jdbc);

        SessionService sessions = new SessionService(new JdbcSessionRepository(jdbc), principals, auditContext,
                transactions, clock);
        LocalLoginService login = new LocalLoginService(principals, sessions, new CredentialHasher(),
                new JdbcLoginThrottleRepository(jdbc), LoginRateLimitPolicy.LOCAL_DEFAULT,
                LoginRateLimitPolicy.ADDRESS_DEFAULT, auditContext, transactions, clock);
        TargetRegistration registration = new TargetService(new JdbcTargetRepository(jdbc),
                new ScopeEvaluatingAuthorizationService(new JdbcPrincipalOrganizationResolver(jdbc),
                        new JdbcAuthorizationGrantRepository(jdbc),
                        new ScopePathResolver(new JdbcResourceScopeDirectory(jdbc))),
                auditContext, transactions, clock);

        RequestOrigin origin = new RequestOrigin(OPERATOR_CLIENT_ADDRESS, UUID.randomUUID());
        LocalLoginResult session = login.authenticate(new LocalCredentials(loginOrganization, username, credential), origin);
        try {
            if (session.session().mustRotate()) {
                throw new CredentialMustRotateException();
            }
            UUID organization = session.session().organizationId();
            return registration.register(session.session().principal(), organization,
                    new TargetRegistration.Request(spec.targetId(), spec.name(), spec.kind(), spec.transport(),
                            ownershipOf(organization, spec)),
                    origin);
        } finally {
            sessions.revoke(session.session().id(), origin);
        }
    }

    private static ScopePath ownershipOf(UUID organization, Spec spec) {
        if (spec.environmentId() != null) {
            return ScopePath.environment(organization, spec.organizationUnitId(), spec.environmentId());
        }
        if (spec.organizationUnitId() != null) {
            return ScopePath.subOrganization(organization, spec.organizationUnitId());
        }
        return ScopePath.organization(organization);
    }

    private static UUID optionalUuid(Map<String, String> env, String name) {
        String value = env.get(name);
        if (isBlank(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " is not a valid UUID");
        }
    }

    private static String orDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
