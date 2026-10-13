package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.secret.Envelope;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.KeyId;
import com.solace.sm.dp.secret.MasterKey;
import com.solace.sm.dp.secret.MasterKeyRing;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Shared arrangement for the secret-store ITs: keys, stores over the runtime credential, install-level resets. */
final class SecretFixtures {

    private SecretFixtures() {
    }

    static byte[] keyBytes(int fill) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) fill);
        return b;
    }

    static String keyBase64(int fill) {
        return Base64.getEncoder().encodeToString(keyBytes(fill));
    }

    /** A key id no other test has used: the canary table is install-level state shared across the JVM. */
    static String freshKeyId() {
        return "it-" + Long.toHexString(UUID.randomUUID().getMostSignificantBits());
    }

    static MasterKey key(String id, int fill) {
        return MasterKey.of(new KeyId(id), keyBytes(fill));
    }

    static MasterKeyRing ring(MasterKey current, MasterKey previous) {
        return new MasterKeyRing(current, Optional.ofNullable(previous));
    }

    static EnvelopeSecretStore store(MasterKeyRing ring) {
        NamedParameterJdbcTemplate jdbc = SmDataPostgres.runtimeJdbc();
        return new EnvelopeSecretStore(new JdbcSecretRepository(jdbc), ring, Envelope.standard(), Clock.systemUTC());
    }

    /**
     * Returns the install to "fresh" as the OWNER (the runtime role has no DELETE, by design): the canary table AND
     * the secret table are emptied together, because the boot check mints a first canary only on an install that holds
     * no secret either (a restore must not look fresh).
     */
    static void resetCanaries() {
        SmDataPostgres.bootstrap();
        SmDataPostgres.superuserJdbc().getJdbcOperations().execute("DELETE FROM sm_secret.kek_canary");
        SmDataPostgres.superuserJdbc().getJdbcOperations().execute("DELETE FROM sm_secret.secret");
    }

    /** An instance for the tenant at the given lifecycle, so the second tenant list can or cannot see it. */
    static void seedInstance(UUID tenant, String lifecycle) {
        SmDataPostgres.bootstrap();
        UUID instance = UUID.randomUUID();
        NamedParameterJdbcTemplate jdbc = SmDataPostgres.runtimeJdbc();
        jdbc.update("INSERT INTO sm_data.instance_state (tenant_id, instance_id, environment_id,"
                        + " catalog_solution_id, solution, declared_package_version, name, lifecycle,"
                        + " acquisition_mode, created_by, created_at) VALUES (:t, :i, :e, :c, 'cache', '2.4', :n,"
                        + " 'pending', 'via-ssh', :u, now())",
                new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance)
                        .addValue("e", UUID.randomUUID()).addValue("c", UUID.randomUUID())
                        .addValue("n", "i-" + instance).addValue("u", UUID.randomUUID()));
        if (!"pending".equals(lifecycle)) {
            jdbc.update("UPDATE sm_data.instance_state SET lifecycle = :l WHERE tenant_id = :t AND instance_id = :i",
                    new MapSqlParameterSource().addValue("l", lifecycle).addValue("t", tenant)
                            .addValue("i", instance));
        }
    }
}
