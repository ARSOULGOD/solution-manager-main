package com.solace.sm.cp.persistence;

import com.solace.sm.cp.api.audit.ClientAddresses;
import com.solace.sm.cp.identity.AuditOperations;
import com.solace.sm.cp.identity.CredentialRotationService;
import com.solace.sm.cp.identity.LocalLoginService;
import com.solace.sm.cp.identity.PrincipalRepository;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code OR-20}'s one-shot operator recovery command: clears exactly ONE login-throttle lockout —
 * by username, by client address (for IPv6, this means the whole {@code /64} that address belongs
 * to — M1: that is the granularity the durable throttle itself keys on), or a principal's own
 * credential-rotation throttle — mirroring {@link SmControlMigrationMain}'s structure and exit codes.
 *
 * <p><b>Connects with the {@code sm_cp_runtime} credential, never the migrator/owner one.</b>
 * Clearing a lockout is only ever an {@code UPDATE} on {@code sm_control.login_throttle}, a grant
 * {@code sm_cp_runtime} already carries (V010) — this command needs no schema-owner privilege at
 * all, unlike {@link SmControlMigrationMain}.
 *
 * <p>The clearing is itself audited ({@code OR-19}): {@link AuditOperations#LOCKOUT_CLEARED}, actor
 * {@code NULL} (an operator command acts on nobody's behalf, not a principal's), a fresh
 * correlation id, and {@link #OPERATOR_CLIENT_ADDRESS} as the client address. The audit context and
 * the {@code UPDATE} run in one transaction ({@link #run}), so the trigger sees the context.
 */
public final class SmControlLockoutRecoveryMain {

    /** {@code jdbc:postgresql://…} — the SM database. Credentials do NOT belong in it. */
    public static final String URL_VARIABLE = "SM_CONTROL_RUNTIME_URL";

    /** The runtime role, normally {@code sm_cp_runtime}. */
    public static final String USERNAME_VARIABLE = "SM_CONTROL_RUNTIME_USERNAME";

    /** That role's secret. Read, used, and never echoed. */
    public static final String PASSWORD_VARIABLE = "SM_CONTROL_RUNTIME_PASSWORD";

    /** Clears the USERNAME throttle dimension's lockout for this local username (of the organization below). */
    public static final String LOCKOUT_USERNAME_VARIABLE = "SM_CONTROL_LOCKOUT_USERNAME";

    /**
     * Required with {@link #LOCKOUT_USERNAME_VARIABLE} (OR-125 (4)): the organization the username belongs to, by id or
     * by name, spelt as a login would. A username is unique per organization, so the same name in two organizations is
     * two throttle subjects. An organization that names nothing is still a subject: a login against it is counted
     * (exactly as a wrong username is), so the operator can clear that too.
     */
    public static final String LOCKOUT_ORGANIZATION_VARIABLE = "SM_CONTROL_LOCKOUT_ORGANIZATION";

    /**
     * Clears the CLIENT-ADDRESS throttle dimension's lockout for this resolved address literal — or,
     * for an IPv6 literal, for the {@code /64} that contains it (M1: the durable throttle keys IPv6
     * by {@code /64}, {@link com.solace.sm.cp.api.audit.ClientAddresses#throttleKey}), so an operator
     * may type ANY address within that {@code /64}, not only the exact one a request happened to log,
     * and still clear the one lockout it shares with every sibling address in the same {@code /64}.
     */
    public static final String LOCKOUT_CLIENT_ADDRESS_VARIABLE = "SM_CONTROL_LOCKOUT_CLIENT_ADDRESS";

    /** Clears this principal's OWN credential-rotation throttle — not a login dimension at all. */
    public static final String LOCKOUT_PRINCIPAL_ID_VARIABLE = "SM_CONTROL_LOCKOUT_PRINCIPAL_ID";

    /** {@code change_audit.client_address} recorded for every clearing this command performs. */
    static final String OPERATOR_CLIENT_ADDRESS = "operator-cli";

    /** A required environment variable was absent, blank, malformed, or the selector was ambiguous. */
    static final int EXIT_MISSING_VARIABLE = 2;

    /** The clearing failed. */
    static final int EXIT_FAILED = 1;

    /**
     * External finding 4: the selected subject has no ACTIVE lockout to clear — either no throttle
     * row exists at all, or one exists but {@code locked_until} is not in the future. Deliberately
     * distinct from {@code 0} (success): a caller scripting this command must be able to tell
     * "nothing needed clearing" apart from "a lockout was cleared" without parsing stdout.
     */
    static final int EXIT_NO_ACTIVE_LOCKOUT = 3;

    private SmControlLockoutRecoveryMain() {
    }

    /**
     * @param args ignored — configuration arrives through the environment, never the command line
     */
    public static void main(String[] args) {
        System.exit(exitCode(System.getenv(), System.out, System.err));
    }

    /**
     * {@link #main} minus {@link System#exit}, with both streams injected so a test can read every
     * byte this step emits.
     *
     * @return 0 on success, {@value #EXIT_FAILED} on a clearing failure, {@value
     *         #EXIT_MISSING_VARIABLE} when a variable is missing, malformed, or the selector among
     *         {@link #LOCKOUT_USERNAME_VARIABLE}/{@link #LOCKOUT_CLIENT_ADDRESS_VARIABLE}/
     *         {@link #LOCKOUT_PRINCIPAL_ID_VARIABLE} is not exactly one, {@value
     *         #EXIT_NO_ACTIVE_LOCKOUT} when the selected subject has no ACTIVE lockout (no row, or a
     *         row whose {@code locked_until} is not in the future) — nothing is cleared and nothing
     *         is audited
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
            // Its message is JdbcUrls' own words — the rule and the allowlist, never the URL, a
            // value or an unrecognised parameter name — so it is safe to print.
            err.println(URL_VARIABLE + " is not a bare location: " + e.getMessage()
                    + ". The credential belongs in " + USERNAME_VARIABLE + " / " + PASSWORD_VARIABLE);
            return EXIT_MISSING_VARIABLE;
        }

        List<String> present = new ArrayList<>();
        for (String name : new String[] {
                LOCKOUT_USERNAME_VARIABLE, LOCKOUT_CLIENT_ADDRESS_VARIABLE, LOCKOUT_PRINCIPAL_ID_VARIABLE}) {
            if (!isBlank(env.get(name))) {
                present.add(name);
            }
        }
        if (present.size() != 1) {
            err.println("exactly one of " + LOCKOUT_USERNAME_VARIABLE + ", " + LOCKOUT_CLIENT_ADDRESS_VARIABLE
                    + ", " + LOCKOUT_PRINCIPAL_ID_VARIABLE + " must be set — found " + present.size());
            return EXIT_MISSING_VARIABLE;
        }

        String subjectHash;
        String selected = present.get(0);
        if (selected.equals(LOCKOUT_USERNAME_VARIABLE)) {
            if (isBlank(env.get(LOCKOUT_ORGANIZATION_VARIABLE))) {
                err.println(LOCKOUT_USERNAME_VARIABLE + " needs " + LOCKOUT_ORGANIZATION_VARIABLE
                        + ": a username is unique per organization");
                return EXIT_MISSING_VARIABLE;
            }
            try {
                // The organization is NOT trimmed (W5D-7): login does not trim it either (a request with surrounding
                // whitespace is a 400 there), so a trimmed value here could name a subject no login ever counted under.
                subjectHash = usernameSubjectHash(url, username, password, env.get(LOCKOUT_ORGANIZATION_VARIABLE),
                        env.get(LOCKOUT_USERNAME_VARIABLE).trim());
            } catch (RuntimeException e) {
                err.println("the lockout recovery failed: " + SmControlMigrationMain.classify(e));
                return EXIT_FAILED;
            }
        } else if (selected.equals(LOCKOUT_CLIENT_ADDRESS_VARIABLE)) {
            // Canonicalised through the SAME ClientAddresses spelling RequestOrigin applied when the
            // lockout was recorded (m6) — otherwise an operator-typed "::1" would hash to a DIFFERENT
            // subject than the "0:0:0:0:0:0:0:1" a servlet container actually reported. addressSubjectHash
            // (M1) then derives the SAME /64-collapsed key LocalLoginService recorded the lockout under,
            // so any address within that /64 — not only the exact one a request logged — clears it.
            String canonical = ClientAddresses.canonicalize(env.get(LOCKOUT_CLIENT_ADDRESS_VARIABLE)).orElse(null);
            if (canonical == null) {
                err.println(LOCKOUT_CLIENT_ADDRESS_VARIABLE + " is not an IP literal");
                return EXIT_MISSING_VARIABLE;
            }
            subjectHash = LocalLoginService.addressSubjectHash(canonical);
        } else {
            UUID principalId;
            try {
                principalId = UUID.fromString(env.get(LOCKOUT_PRINCIPAL_ID_VARIABLE));
            } catch (IllegalArgumentException e) {
                err.println(LOCKOUT_PRINCIPAL_ID_VARIABLE + " is not a valid UUID");
                return EXIT_MISSING_VARIABLE;
            }
            subjectHash = CredentialRotationService.rotationSubjectHash(principalId);
        }

        try {
            boolean cleared = run(url, username, password, subjectHash);
            if (cleared) {
                out.println("cleared 1 lockout");
                return 0;
            }
            out.println("no active lockout for this subject — nothing to clear, nothing audited");
            return EXIT_NO_ACTIVE_LOCKOUT;
        } catch (RuntimeException e) {
            err.println("the lockout recovery failed: " + SmControlMigrationMain.classify(e));
            return EXIT_FAILED;
        }
    }

    /**
     * The throttle subject {@code LocalLoginService} counts a login of {@code username} to {@code organization} under:
     * the organization resolved exactly as a login resolves it (the SAME repository method), or, when it names nothing,
     * the unresolved subject a login against it was counted under.
     */
    static String usernameSubjectHash(String url, String dbUsername, String dbPassword, String organization,
            String username) {
        DataSource dataSource = SmControlMigrationMain.dataSource(url, dbUsername, dbPassword);
        JdbcPrincipalRepository principals = new JdbcPrincipalRepository(new NamedParameterJdbcTemplate(dataSource));
        java.util.Optional<UUID> resolved = principals.resolveOrganization(organization);
        // The same keys, in the same order, as LocalLoginService: the principal's id when the principal exists (the
        // database's lower() decides who "the same user" is), else the resolved organization's fallback, else the
        // unresolved one.
        return principals.findLocalByUsername(resolved.orElse(PrincipalRepository.NO_ORGANIZATION), username)
                .map(found -> LocalLoginService.principalSubjectHash(found.id()))
                .or(() -> resolved.map(id -> LocalLoginService.loginSubjectHash(id, username)))
                .orElseGet(() -> LocalLoginService.unresolvedLoginSubjectHash(organization, username));
    }

    /**
     * Applies the clearing with the runtime credential, audited as {@link AuditOperations#LOCKOUT_CLEARED}.
     * Clears only an ACTIVE lockout — external finding 4: a row that exists but is not locked
     * ({@code locked_until} not in the future, including {@code NULL}) is left completely untouched,
     * exactly like a subject with no row at all; {@code failure_count} is never reset below the
     * threshold, and nothing is audited, since the {@code UPDATE} matches zero rows either way.
     *
     * @return {@code true} if an ACTIVE lockout existed and was cleared, {@code false} if the subject
     *         has no active lockout to clear (no row, or a row that is not currently locked) —
     *         nothing to clear, and therefore nothing audited: a trigger never fires for zero
     *         affected rows
     */
    public static boolean run(String url, String username, String password, String subjectHash) {
        DataSource dataSource = SmControlMigrationMain.dataSource(url, username, password);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcAuditContext auditContext = new JdbcAuditContext(jdbc);
        AtomicBoolean cleared = new AtomicBoolean(false);
        transactions.executeWithoutResult(status -> {
            auditContext.set(null, AuditOperations.LOCKOUT_CLEARED, UUID.randomUUID(), OPERATOR_CLIENT_ADDRESS);
            int updated = jdbc.update("""
                    UPDATE sm_control.login_throttle
                       SET window_started_at = now(),
                           failure_count = 0,
                           locked_until = NULL
                     WHERE subject_hash = :subjectHash
                       AND locked_until > now()
                    """, new MapSqlParameterSource().addValue("subjectHash", subjectHash));
            cleared.set(updated == 1);
        });
        return cleared.get();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static int missing(String variable, PrintStream err) {
        err.println(variable + " is not set — the lockout recovery step reads its configuration from "
                + "the environment, and will not guess it");
        return EXIT_MISSING_VARIABLE;
    }
}
