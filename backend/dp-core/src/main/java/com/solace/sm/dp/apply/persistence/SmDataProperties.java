package com.solace.sm.dp.apply.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code sm_data} configuration the APPLICATION binds, from {@code sm.data.*} — and, as of the
 * external review's finding 1, that is the {@code sm_dp_runtime} credential and nothing else.
 *
 * <p><b>The migrator credential never reaches the application process.</b> Design §3.1's privilege
 * topology IS the security boundary: {@code sm_dp_migrator} is a member of {@code sm_dp_owner} and can
 * rewrite the schema. Binding it here would have put a schema-owner password in a bean any component
 * of the same JVM could read — the boundary would then be a convention about who calls
 * {@code props.migrator()}, not a property of the process. So Phase 1 is a SEPARATE one-shot process,
 * {@link SmDataMigrationMain}, run by the install runbook with that credential; what starts afterwards
 * holds only {@code runtime}: {@code NOINHERIT}, not an owner member, carrying only the per-table (and,
 * on three tables, per-column) grants the migrations issue. {@link ApplyPersistenceConfiguration}'s
 * readiness check is how the application still refuses to serve an unmigrated schema without ever
 * being able to migrate one.
 *
 * <p><b>The password is a deployment secret</b> ({@code I6}): supplied by the environment or the secret
 * backend, never persisted to config storage, never logged. Records generate a component-printing
 * {@code toString} by default, so the nested record overrides it — the same precedent
 * {@code MeshProperties} sets.
 *
 * @param runtime the application credential — the only one this process is given
 * @param apply   the apply engine's own knobs; defaulted when the block is absent
 */
@ConfigurationProperties(prefix = "sm.data")
public record SmDataProperties(Credentials runtime, Apply apply) {

    public SmDataProperties {
        if (runtime == null) {
            throw new IllegalArgumentException("sm.data.runtime must be set");
        }
        if (apply == null) {
            apply = new Apply(null);
        }
    }

    /**
     * Engine configuration the database deliberately cannot hold.
     *
     * <p>{@code maxAttempts} is obligation (m)'s cap: {@code attempt_no_positive} gives the lower
     * bound in SQL, the upper bound is configuration and therefore lives here. Default 3 — the number
     * design §3.3's growth arithmetic prices ("a 10-replica instance at 3 retries worst-cases 150
     * rows/apply").
     *
     * @param maxAttempts tries per step before the exhaustion path; null means the default
     */
    public record Apply(Integer maxAttempts) {

        /** The design's own worked number (§3.3 growth arithmetic). */
        public static final int DEFAULT_MAX_ATTEMPTS = 3;

        public Apply {
            if (maxAttempts == null) {
                maxAttempts = DEFAULT_MAX_ATTEMPTS;
            }
            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "sm.data.apply.max-attempts must be at least 1, was " + maxAttempts);
            }
        }
    }

    /**
     * One JDBC identity: a location, a role, and that role's secret — three separate things.
     *
     * <p><b>The URL may carry nothing but a location</b>, and that is enforced here rather than
     * reviewed. HikariCP names the URL in its pool diagnostics exactly as Flyway prints it at INFO on
     * every migrate (the measured line is in {@link SmDataMigrationMain}'s javadoc), so whatever the
     * URL holds will be logged. {@link JdbcUrls#requireLocation} therefore admits only
     * {@code jdbc:postgresql://host[:port]/database} plus an allowlist of nine non-secret pgjdbc
     * parameters with fixed value grammars — not merely "no {@code password=}" — at bind time, before a
     * pool exists to print anything, and without echoing the URL.
     *
     * @param url      a {@code jdbc:postgresql://…} URL fitting {@link JdbcUrls}' location grammar
     * @param username the PostgreSQL role to log in as
     * @param password that role's secret — never log it, never store it
     */
    public record Credentials(String url, String username, String password) {

        public Credentials {
            requireText("url", url);
            requireText("username", username);
            JdbcUrls.requireLocation(url);
            if (password == null) {
                password = "";
            }
        }

        private static void requireText(String name, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("sm.data.*." + name + " must be set");
            }
        }

        /**
         * Both halves of the credential redacted — {@link JdbcUrls} calls the role name "half of a
         * credential", and a {@code toString} that printed it would contradict that in the one place
         * Spring is most likely to print this record (a binding failure). The URL stays: it is a
         * location by construction, and it is what a misconfiguration diagnosis needs.
         */
        @Override
        public String toString() {
            return "Credentials[url=" + url + ", username=****, password=****]";
        }
    }
}
