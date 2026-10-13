package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SmDataProperties.Credentials} as the {@code I6} gate on the APPLICATION's runtime URL.
 *
 * <p>{@link SmDataMigrationMainTest} covers the migration step's environment; this covers the other
 * door a URL comes through. The two are not the same check written twice: the migration step reads an
 * environment variable and exits, while this one runs at Spring bind time, before
 * {@link ApplyPersistenceConfiguration}'s Hikari pool exists — and Hikari names the URL in its own
 * pool-startup and connection-failure diagnostics, so "refused at bind time" is what keeps anything
 * but a bare location from being logged by a library this code does not control.
 */
class SmDataPropertiesTest {

    private static final String SENTINEL = "SENTINEL-PROPS-PW-4b6d";

    @Test
    void aRuntimeUrlCarryingAPasswordParameterIsRefusedAtBindTimeWithoutEchoingIt() {
        String url = "jdbc:postgresql://db.example:5432/sm?password=" + SENTINEL;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmDataProperties.Credentials(url, "sm_dp_runtime", "pw"));

        System.out.println("MEASURED: ?password= refusal = " + e.getMessage());
        assertTrue(e.getMessage().contains("position 1") && e.getMessage().contains("allowlist"),
                "the refusal locates the parameter and names the rule. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains(SENTINEL), e.getMessage());
        assertFalse(e.getMessage().contains(url), e.getMessage());
    }

    @Test
    void aRuntimeUrlCarryingUserinfoIsRefusedAtBindTimeWithoutEchoingIt() {
        String url = "jdbc:postgresql://alice:" + SENTINEL + "@db.example/sm";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmDataProperties.Credentials(url, "sm_dp_runtime", "pw"));

        System.out.println("MEASURED: userinfo refusal = " + e.getMessage());
        assertTrue(e.getMessage().contains("userinfo"), e.getMessage());
        assertFalse(e.getMessage().contains(SENTINEL), e.getMessage());
        assertFalse(e.getMessage().contains(url), e.getMessage());
        assertFalse(e.getMessage().contains("alice"), e.getMessage());
    }

    @Test
    void aRuntimeUrlCarryingAParameterOutsideTheAllowlistIsRefusedAtBindTimeWithoutEchoingIt() {
        // The review's second pass: `options=` is a real pgjdbc property and a free-text one. Under the
        // first draft's blocklist it was admitted, and Hikari would have named the URL in its diagnostics.
        String url = "jdbc:postgresql://db.example:5432/sm?connectTimeout=5&options=" + SENTINEL;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new SmDataProperties.Credentials(url, "sm_dp_runtime", "pw"));

        System.out.println("MEASURED: ?options= refusal = " + e.getMessage());
        assertTrue(e.getMessage().contains("position 2") && e.getMessage().contains("allowlist"),
                e.getMessage());
        assertFalse(e.getMessage().contains("options"), "the NAME is not repeated. Was: " + e.getMessage());
        assertFalse(e.getMessage().contains(SENTINEL), e.getMessage());
        assertFalse(e.getMessage().contains(url), e.getMessage());
    }

    @Test
    void aUrlWithAllowlistedParametersIsAcceptedBecauseARuleThatRefusedEveryParameterWouldBeUnusable() {
        String url = "jdbc:postgresql://h:5432/db?sslmode=verify-full&sslrootcert=/etc/sm/ca.pem"
                + "&connectTimeout=5";
        SmDataProperties.Credentials c = new SmDataProperties.Credentials(url, "sm_dp_runtime", "pw");
        assertEquals(url, c.url(),
                "the positive control: without it the refusals above would be tested green by a rule "
                        + "that refused everything");
    }

    @Test
    void bothHalvesOfTheCredentialAreRedactedByToStringAndTheUrlIsNot() {
        SmDataProperties.Credentials c = new SmDataProperties.Credentials(
                "jdbc:postgresql://h:5432/db", "sm_dp_runtime", SENTINEL);

        System.out.println("MEASURED: Credentials.toString() = " + c);
        assertFalse(c.toString().contains(SENTINEL),
                "a record's generated toString prints every component — this one is overridden for "
                        + "exactly that reason. Was: " + c);
        assertTrue(c.toString().contains("password=****") && c.toString().contains("username=****"),
                c.toString());
        assertFalse(c.toString().contains("sm_dp_runtime"),
                "the ROLE is redacted too — JdbcUrls calls it half of a credential, and this record "
                        + "must not say otherwise. Was: " + c);
        assertTrue(c.toString().contains("jdbc:postgresql://h:5432/db"),
                "the URL stays: a location by construction, and what a diagnosis needs");
    }
}
