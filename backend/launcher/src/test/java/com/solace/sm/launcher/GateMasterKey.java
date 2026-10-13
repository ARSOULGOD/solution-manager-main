package com.solace.sm.launcher;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Plan 011 P11-8.1 Step 3: the application's master key for the storage gate, ONE KEY PER BENCHMARK DATABASE.
 *
 * <p>The application seals a canary row with its master key on the first boot and refuses to start on a key the database holds no
 * canary for. {@code TwoSchemaTestDatabase.testMasterKeyProperties()} mints a random key per call, which is right for a throwaway
 * container booted once and wrong for the benchmark database, which the gate boots again for every re-run (the integrator's live run
 * died on the second boot). The benchmark database is disposable and holds no secret of value, so the key is DERIVED, not stored: an
 * HMAC-SHA256 over the database's OWN identity (the cluster's {@code system_identifier} and {@code current_database()}, read over the
 * admin connection the gate already opens), keyed by the benchmark's own admin password, which the environment already supplies and which is
 * never printed. The same database and password give the same key on every boot, however the connection is spelled (a re-made SSH port-forward on
 * another local port, {@code localhost} for {@code 127.0.0.1}); another database or password gives another. It is
 * never a committed default (D-KEY): nothing in the repository determines it.
 */
final class GateMasterKey {

    private static final String LABEL = "sm-bench-gate|";

    private GateMasterKey() {
    }

    /**
     * The properties {@code TwoSchemaTestDatabase.testMasterKeyProperties()} would return, but stable for one database.
     *
     * @param databaseIdentity the benchmark database's own identity, as {@link #identityQuery()} reads it; NOT the JDBC URL, which
     *                         describes the client side of the tunnel and changes with it
     * @param adminPassword the benchmark's admin password (from the environment); the derivation's secret
     * @throws IllegalArgumentException when either is blank
     */
    static Map<String, String> properties(String databaseIdentity, String adminPassword) {
        if (databaseIdentity == null || databaseIdentity.isBlank()) {
            throw new IllegalArgumentException("the benchmark database's identity is required to derive its master key");
        }
        if (adminPassword == null || adminPassword.isEmpty()) {
            throw new IllegalArgumentException("SM_BENCH_ADMIN_PASSWORD is required to derive the benchmark database's master key"
                    + " (there is no committed default key)");
        }
        byte[] key = hmac(adminPassword, LABEL + "key|" + databaseIdentity);
        byte[] id = hmac(adminPassword, LABEL + "id|" + databaseIdentity);
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(TwoSchemaTestDatabase.MASTER_KEY, Base64.getEncoder().encodeToString(key));
        properties.put(TwoSchemaTestDatabase.MASTER_KEY_ID, "bench-gate-" + HexFormat.of().formatHex(id, 0, 4));
        // as TwoSchemaTestDatabase: the key is supplied as a property, which production refuses in any other source (OR-85)
        properties.put(com.solace.sm.dp.secret.SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES, "true");
        return properties;
    }

    /**
     * The query that reads a database's identity over the admin connection: the cluster's {@code system_identifier} (fixed at
     * {@code initdb}, so it survives a restart and a new tunnel) and the database's name. {@code pg_control_system()} is read-only.
     */
    static String identityQuery() {
        return "SELECT (SELECT system_identifier::text FROM pg_control_system()) || '|' || current_database()";
    }

    private static byte[] hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required of every JDK", e);
        }
    }
}
