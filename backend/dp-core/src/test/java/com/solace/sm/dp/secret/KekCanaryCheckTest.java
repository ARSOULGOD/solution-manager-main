package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** D-KEY 4.3's decision table, row by row. */
class KekCanaryCheckTest {

    private final Envelope envelope = Envelope.standard();
    private final InMemoryRows rows = new InMemoryRows();

    private final MasterKey q3 = Keys.key("2026-q3", 3);
    private final MasterKey q4 = Keys.key("2026-q4", 4);

    private void check(MasterKey current, MasterKey previous) {
        new KekCanaryCheck(new MasterKeyRing(current, Optional.ofNullable(previous)), rows, envelope,
                Clock.systemUTC()).run();
    }

    private void seedCanary(MasterKey key) {
        rows.insertIfAbsent(new CanaryRow(envelope.sealCanary(key, KekCanaryCheck.CANARY_PLAINTEXT),
                java.time.Instant.EPOCH));
    }

    private static String refusal(Runnable r) {
        return assertThrows(IllegalStateException.class, r::run).getMessage();
    }

    @Test
    void firstBootOfAFreshInstallMintsTheCanaryAndThenAcceptsTheSameKeyAgain() {
        check(q3, null);
        assertEquals(1, rows.canaries.size());
        check(q3, null);
        assertEquals(1, rows.canaries.size(), "a second boot mints nothing");
    }

    @Test
    void anEmptyCanaryTableOverExistingSecretsIsNotAFreshInstall() {
        SecretRow sealedUnderAnotherKey = new SecretRow(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                SecretPurpose.SSH_PRIVATE_KEY,
                Envelope.standard().sealSecret(Keys.key("2026-q1", 1), java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(), SecretPurpose.SSH_PRIVATE_KEY, new byte[]{1}),
                null, java.time.Instant.EPOCH, java.util.UUID.randomUUID());
        rows.insert(sealedUnderAnotherKey);

        String m = refusal(() -> check(q3, null));

        assertTrue(m.contains("SM_DP_MASTER_KEY"), m);
        assertTrue(rows.canaries.isEmpty(), "nothing is minted for a key that may be the wrong one");
    }

    @Test
    void aWrongMasterKeyFailsTheCanaryAndNeverWritesOne() {
        check(q3, null);
        MasterKey lastQuartersKeyUnderThisQuartersId = Keys.key("2026-q3", 9);

        String m = refusal(() -> check(lastQuartersKeyUnderThisQuartersId, null));

        assertTrue(m.contains("SM_DP_MASTER_KEY") && m.contains("2026-q3"), m);
        assertEquals(1, rows.canaries.size());
    }

    @Test
    void anUnknownIdOnANonEmptyInstallWithoutAPreviousKeyIsRefusedAndNothingIsMinted() {
        check(q3, null);

        String m = refusal(() -> check(q4, null));

        assertTrue(m.contains("2026-q4") && m.contains("SM_DP_MASTER_KEY_PREVIOUS"), m);
        assertEquals(1, rows.canaries.size(), "an application given the wrong key never writes a canary for it");
    }

    @Test
    void aRotationStartingMintsTheNewCanaryOnlyAfterProvingThePreviousKey() {
        check(q3, null);

        check(q4, q3);

        assertEquals(2, rows.canaries.size());
        check(q4, q3); // restart during the rotation
        check(q4, null); // after the old key is retired
        assertEquals(2, rows.canaries.size());
    }

    @Test
    void aPreviousKeyThatDoesNotVerifyIsRefusedNamingThePreviousVariableAndNothingIsMinted() {
        check(q3, null);

        String m = refusal(() -> check(q4, Keys.key("2026-q3", 9)));

        assertTrue(m.contains("SM_DP_MASTER_KEY_PREVIOUS"), m);
        assertEquals(1, rows.canaries.size());
    }

    @Test
    void aPreviousKeyWithNoCanaryOfItsOwnIsRefusedEvenOnAnEmptyInstall() {
        String m = refusal(() -> check(q4, q3));

        assertTrue(m.contains("SM_DP_MASTER_KEY_PREVIOUS"), m);
        assertTrue(rows.canaries.isEmpty());
    }

    @Test
    void twoBootsRacingToMintCannotDisagree() {
        // another boot, holding a DIFFERENT key for the same id, wins the insert
        rows.racingCanary = new CanaryRow(envelope.sealCanary(Keys.key("2026-q3", 9), KekCanaryCheck.CANARY_PLAINTEXT),
                java.time.Instant.EPOCH);

        String m = refusal(() -> check(q3, null));

        assertTrue(m.contains("SM_DP_MASTER_KEY"), m);
    }

    @Test
    void theCanaryIsInstallLevelAndCarriesNoTenant() throws Exception {
        for (Constructor<?> c : KekCanaryCheck.class.getConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                assertFalse(SecretRows.class.isAssignableFrom(p),
                        "the boot check is given no handle on tenant secrets, so it cannot read across tenants");
            }
        }
        assertTrue(Arrays.stream(CanaryRow.class.getRecordComponents()).noneMatch(rc -> rc.getName().contains("tenant")));
        assertTrue(Arrays.stream(CanaryRows.class.getMethods()).flatMap(m -> Arrays.stream(m.getParameterTypes()))
                .noneMatch(t -> t == java.util.UUID.class), "no port method takes a tenant id");
    }

    @Test
    void aMessageNeverCarriesKeyMaterial() {
        check(q3, null);
        String m = refusal(() -> check(Keys.key("2026-q3", 9), null));
        assertFalse(m.contains(Keys.base64(9)) || m.contains(Keys.base64(3)), m);
    }
}
