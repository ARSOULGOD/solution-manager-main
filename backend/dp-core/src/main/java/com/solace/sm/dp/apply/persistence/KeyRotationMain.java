package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.EnvironmentMasterKeyProvider;
import com.solace.sm.dp.secret.KekCanaryCheck;
import com.solace.sm.dp.secret.KeyId;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.MasterKeyRing;
import com.solace.sm.dp.secret.SealedSecret;
import com.solace.sm.dp.secret.SecretException;
import com.solace.sm.dp.secret.SecretRow;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The key re-wrap command of D-KEY 5 (draft decision K-3), plan 010 T2.4 Step 3b: moves every data key
 * wrapped under the PREVIOUS master key to the CURRENT one, and leaves every value ciphertext byte-identical.
 *
 * <p><b>A standalone {@code main}.</b> It never boots a Spring context, so it can never start a second
 * apply worker or recovery sweep against the live database; it connects with the DP RUNTIME credential
 * ({@value #URL_VARIABLE} / {@value #USERNAME_VARIABLE} / {@value #PASSWORD_VARIABLE}), whose column grant
 * allows exactly {@code UPDATE (key_id, wrapped_dek, dek_iv)} on {@code sm_secret.secret}; the owner and
 * migrator credentials never reach it. Both keys come from the environment, as for the application
 * ({@link EnvironmentMasterKeyProvider}).
 *
 * <p><b>Which tenants.</b> The second tenant list ({@link TenantEnumerationRepository#tenantsWithActiveInstances},
 * {@code OR-49}) plus every {@code --tenant <uuid>} the operator names. That list does not reach a tenant
 * that holds secrets but no active instance (D-KEY 5.4); owner ruling {@code OR-85} (OQ-1) rules route (d):
 * no third cross-tenant read, the operator names stragglers, and the old key is not retired until the
 * operator's own database check shows nothing still names it. So this command <b>never prints an unqualified
 * "none remain"</b>: its report says which tenants it covered and that tenants outside that set are NOT
 * covered.
 *
 * <p><b>Safe by construction.</b> Before any row moves it requires both keys' canaries to exist and verify,
 * read-only: a mistyped new key would otherwise re-wrap every data key under garbage, which is permanent loss.
 * Each tenant is processed in batches, one {@code UPDATE} per row guarded by the old id (a concurrent reader
 * sees the row entirely old or entirely new; a re-run after a crash updates zero rows), and every batch is
 * tenant-bound. The run stops on the first authentication failure, naming the tenant and key id and never a
 * byte. It prints counts, key ids and tenant ids only.
 */
public final class KeyRotationMain {

    /** {@code jdbc:postgresql://…} — the SM database. Credentials do NOT belong in it. */
    public static final String URL_VARIABLE = "SM_DATA_RUNTIME_URL";
    /** The DP runtime role, normally {@code sm_dp_runtime}. */
    public static final String USERNAME_VARIABLE = "SM_DATA_RUNTIME_USERNAME";
    /** That role's secret. Read, used, and never echoed. */
    public static final String PASSWORD_VARIABLE = "SM_DATA_RUNTIME_PASSWORD";

    static final String TENANT_ARGUMENT = "--tenant";

    /** The run stopped: an authentication failure, or the keys are not the ones the database was written under. */
    static final int EXIT_FAILED = 1;
    /** A variable or argument is missing or malformed. */
    static final int EXIT_MISSING_VARIABLE = 2;
    /** Rows remain under the old key in a covered tenant. Not success. */
    static final int EXIT_ROWS_REMAIN = 3;

    /** Rows per transaction. */
    static final int BATCH_SIZE = 100;

    private KeyRotationMain() {
    }

    public static void main(String[] args) {
        System.exit(exitCode(args, System.getenv(), System.out, System.err));
    }

    /** {@link #main} minus {@link System#exit}, with both streams injected so a test can read every byte. */
    static int exitCode(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        List<UUID> named = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (!TENANT_ARGUMENT.equals(args[i]) || i + 1 >= args.length) {
                err.println("usage: KeyRotationMain [" + TENANT_ARGUMENT + " <tenant-uuid>]...");
                return EXIT_MISSING_VARIABLE;
            }
            try {
                named.add(UUID.fromString(args[++i]));
            } catch (IllegalArgumentException e) {
                err.println(TENANT_ARGUMENT + " takes a tenant UUID");
                return EXIT_MISSING_VARIABLE;
            }
        }
        for (String name : new String[] {URL_VARIABLE, USERNAME_VARIABLE, PASSWORD_VARIABLE}) {
            String value = env.get(name);
            if (value == null || value.isBlank()) {
                err.println(name + " is not set: the key re-wrap reads its credential from the environment, "
                        + "and will not guess one");
                return EXIT_MISSING_VARIABLE;
            }
        }
        try {
            JdbcUrls.requireLocation(env.get(URL_VARIABLE));
        } catch (IllegalArgumentException e) {
            err.println(URL_VARIABLE + " is not a bare location: " + e.getMessage() + ". The credential belongs in "
                    + USERNAME_VARIABLE + " / " + PASSWORD_VARIABLE);
            return EXIT_MISSING_VARIABLE;
        }
        MasterKeyRing ring;
        try {
            ring = new EnvironmentMasterKeyProvider(env::get).load();
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return EXIT_MISSING_VARIABLE;
        }
        if (ring.previous().isEmpty()) {
            err.println(EnvironmentMasterKeyProvider.PREVIOUS + " (or " + EnvironmentMasterKeyProvider.PREVIOUS_FILE
                    + ") and " + EnvironmentMasterKeyProvider.PREVIOUS_ID + " are required: the command moves data "
                    + "keys FROM the previous key TO the current one");
            return EXIT_MISSING_VARIABLE;
        }

        try {
            DataSource ds = SmDataMigrationMain.dataSource(env.get(URL_VARIABLE), env.get(USERNAME_VARIABLE),
                    env.get(PASSWORD_VARIABLE));
            Report report = rotate(ds, ring, Envelope.standard(), named, out);
            out.println(report.summary());
            return report.remaining() == 0 ? 0 : EXIT_ROWS_REMAIN;
        } catch (RotationHalted e) {
            err.println(e.getMessage());
            return EXIT_FAILED;
        } catch (RuntimeException e) {
            // classifiers only: a driver or Flyway message may carry the URL or the role
            err.println("the key re-wrap failed: " + e.getClass().getSimpleName());
            return EXIT_FAILED;
        }
    }

    /**
     * Re-wraps every data key of every covered tenant from the previous key to the current one.
     *
     * @param named tenants the operator added with {@code --tenant}, beyond the second list
     * @param progress one line per tenant goes here
     * @throws RotationHalted when the keys are not the database's, or a row fails authentication
     */
    static Report rotate(DataSource ds, MasterKeyRing ring, Envelope envelope, List<UUID> named,
            PrintStream progress) {
        MasterKey from = ring.previous().orElseThrow();
        MasterKey to = ring.current();
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
        JdbcSecretRepository secrets = new JdbcSecretRepository(jdbc);
        requireCanaries(new JdbcKekCanaryRepository(jdbc), envelope, from, to);

        Set<UUID> listed = new LinkedHashSet<>();
        for (TenantId t : new TenantEnumerationRepository(jdbc).tenantsWithActiveInstances()) {
            listed.add(t.value());
        }
        Set<UUID> covered = new LinkedHashSet<>(listed);
        covered.addAll(named);
        int namedExtra = covered.size() - listed.size();

        long movedTotal = 0;
        long remainingTotal = 0;
        for (UUID tenant : covered) {
            long moved = 0;
            while (true) {
                Integer batch = transactions.execute(status -> rewrapBatch(secrets, envelope, from, to, tenant));
                if (batch == null || batch == 0) {
                    break;
                }
                moved += batch;
            }
            long remaining = secrets.countUnderKey(tenant, from.id());
            progress.println("tenant " + tenant + ": re-wrapped " + moved + ", still under '" + from.id() + "': "
                    + remaining);
            movedTotal += moved;
            remainingTotal += remaining;
        }
        return new Report(covered.size(), listed.size(), namedExtra, from.id(), to.id(), movedTotal, remainingTotal);
    }

    private static int rewrapBatch(JdbcSecretRepository secrets, Envelope envelope, MasterKey from, MasterKey to,
            UUID tenant) {
        List<SecretRow> rows = secrets.lockBatchUnderKey(tenant, from.id(), BATCH_SIZE);
        for (SecretRow row : rows) {
            SealedSecret moved;
            try {
                moved = envelope.rewrap(from, to, tenant, row.secretId(), row.purpose(), row.sealed());
            } catch (SecretException e) {
                throw new RotationHalted("a data key of tenant " + tenant + " did not authenticate under key id '"
                        + from.id() + "': stopped, nothing further was changed in this batch");
            }
            secrets.rewrap(tenant, row.secretId(), from.id(), moved);
        }
        return rows.size();
    }

    /** Read-only: both keys must be the ones the database holds a canary for. */
    private static void requireCanaries(JdbcKekCanaryRepository canaries, Envelope envelope, MasterKey from,
            MasterKey to) {
        for (MasterKey key : new MasterKey[] {from, to}) {
            boolean ok = KekCanaryCheck.canaryVerifies(envelope, key, canaries.find(key.id()));
            if (!ok) {
                throw new RotationHalted("key id '" + key.id() + "' has no canary that its key verifies: start the "
                        + "application with both keys first (the new key's canary is minted at boot), and check "
                        + "the key and its id. Nothing was changed");
            }
        }
    }

    /** The command stopped before or during a run, with a message safe to print. */
    static final class RotationHalted extends RuntimeException {

        private static final long serialVersionUID = 1L;

        RotationHalted(String message) {
            super(message);
        }
    }

    /** What a run covered and what it moved. */
    record Report(int covered, int fromList, int named, KeyId from, KeyId to, long moved, long remaining) {

        String summary() {
            return "covered " + covered + " tenants (" + fromList + " from the active-instance list, " + named
                    + " named with " + TENANT_ARGUMENT + "); tenants outside this set are NOT covered.\n"
                    + "re-wrapped " + moved + " from '" + from + "' to '" + to + "'; " + remaining
                    + " still under '" + from + "' in the covered tenants.\n"
                    + "Do not retire '" + from + "' until your own database check shows no row of ANY tenant still "
                    + "names it.\n"
                    + "Run, as the schema owner: SELECT key_id, count(*) FROM sm_secret.secret GROUP BY key_id;"
                    + " retire '" + from + "' only when its count is 0 (or it is absent).";
        }
    }
}
