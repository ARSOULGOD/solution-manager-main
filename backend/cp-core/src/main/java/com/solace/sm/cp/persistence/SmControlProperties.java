package com.solace.sm.cp.persistence;

import com.solace.sm.cp.identity.LoginRateLimitPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code sm_control} configuration the APPLICATION binds, from {@code sm.control.*} — and, as
 * with {@code SmDataProperties} in {@code dp-core}, that is the {@code sm_cp_runtime} credential and
 * nothing else.
 *
 * <p><b>The migrator/owner credential never reaches the application process.</b> {@code sm_cp_migrator}
 * is a member of {@code sm_cp_owner} and can rewrite the schema. Binding it here would put a
 * schema-owner password in a bean any component of this JVM could read. Phase 1 is the separate
 * one-shot process {@link SmControlMigrationMain}, run by the install runbook with that credential;
 * what starts afterwards holds only {@code runtime}: {@code NOINHERIT}, not an owner member, carrying
 * only the per-table (and, on several tables, per-column) grants the migrations issue.
 * {@link ControlPlanePersistenceConfiguration}'s readiness check is how the application refuses to
 * serve an unmigrated schema without ever being able to migrate one.
 *
 * <p>The password is a deployment secret ({@code I6}): supplied by the environment or the secret
 * backend, never persisted to config storage, never logged. The nested record overrides its default
 * {@code toString} for the same reason {@code SmDataProperties.Credentials} does.
 *
 * @param runtime      the application credential — the only one this process is given
 * @param login        the local-login (username dimension) throttle policy; defaulted when absent
 * @param loginAddress the local-login client-address dimension throttle policy ({@code OR-20});
 *                     defaulted when absent
 */
@ConfigurationProperties(prefix = "sm.control")
public record SmControlProperties(Credentials runtime, LoginRateLimitPolicy login, LoginRateLimitPolicy loginAddress) {

    public SmControlProperties {
        if (runtime == null) {
            throw new IllegalArgumentException("sm.control.runtime must be set");
        }
        if (login == null) {
            login = LoginRateLimitPolicy.LOCAL_DEFAULT;
        }
        if (loginAddress == null) {
            loginAddress = LoginRateLimitPolicy.ADDRESS_DEFAULT;
        }
    }

    /**
     * One JDBC identity: a location, a role, and that role's secret.
     *
     * <p>The URL may carry nothing but a location, enforced at bind time by {@link JdbcUrls#requireLocation}
     * — cp-core's own copy of dp-core's {@code JdbcUrls} grammar ({@code I7} bars importing dp-core's).
     * Flyway and HikariCP both log the URL, so a {@code ?password=}/{@code ?user=} or any parameter
     * outside the allowlist is refused here rather than reviewed.
     *
     * @param url      a {@code jdbc:postgresql://…} URL fitting {@link JdbcUrls}' location grammar
     * @param username the PostgreSQL role to log in as ({@code sm_cp_runtime})
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
                throw new IllegalArgumentException("sm.control.runtime." + name + " must be set");
            }
        }

        /**
         * Both halves of the credential redacted, and the URL's query string dropped too: the guard
         * above already guarantees the query carries no credential, but this prints the location only
         * regardless, so nothing about the allowlist has to be re-reasoned about here.
         */
        @Override
        public String toString() {
            return "Credentials[url=" + JdbcUrls.locationOnly(url) + ", username=****, password=****]";
        }
    }
}
