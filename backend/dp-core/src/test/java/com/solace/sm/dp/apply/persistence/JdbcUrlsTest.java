package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.PGProperty;

/**
 * {@link JdbcUrls}' location grammar, both halves: what an installer may write, and everything else.
 *
 * <p>The negative cases each plant a SENTINEL where a secret would go — a value, an unknown parameter's
 * NAME, the userinfo — and assert the refusal names the rule without the sentinel. The positive cases
 * are the URLs the install runbook and the integration harness actually produce.
 */
class JdbcUrlsTest {

    private static final String SENTINEL = "SNTL-url-6b1e";

    // -------------------------------------------------------------------------------------------
    // The allowlist is made of real pgjdbc property names — verified against the driver, not recalled.
    // -------------------------------------------------------------------------------------------

    @Test
    void everyAllowlistedParameterIsAPgjdbcPropertyUnderExactlyThatName() {
        for (String name : JdbcUrls.ALLOWED.keySet()) {
            PGProperty property = PGProperty.forName(name);
            assertNotNull(property, "'" + name + "' is not a pgjdbc property — the allowlist would "
                    + "admit a parameter the driver ignores, and the runbook would set it in vain");
            assertTrue(name.equals(property.getName()),
                    "'" + name + "' differs in case from pgjdbc's '" + property.getName() + "'");
        }
        System.out.println("MEASURED: allowlist " + JdbcUrls.ALLOWED.keySet()
                + " all resolve via PGProperty.forName");
    }

    // -------------------------------------------------------------------------------------------
    // Accepted: locations.
    // -------------------------------------------------------------------------------------------

    @Test
    void theLocationFormsAnInstallerWritesAreAccepted() {
        for (String url : List.of(
                "jdbc:postgresql://db/sm",
                "jdbc:postgresql://db.example.internal:5432/sm_data",
                "jdbc:postgresql://10.0.0.7:5432/sm",
                "jdbc:postgresql://[::1]:5432/sm",
                "jdbc:postgresql://db1:5432,db2:5432/sm",
                "jdbc:postgresql://db:5432/sm?sslmode=verify-full&sslrootcert=/etc/sm/ca.pem",
                "jdbc:postgresql://db:5432/sm?sslmode=verify-full&sslcert=/etc/sm/tls.crt"
                        + "&sslkey=/etc/sm/tls.pk8",
                "jdbc:postgresql://db:5432/sm?ApplicationName=solution-manager.dp"
                        + "&connectTimeout=10&socketTimeout=30&loginTimeout=10&tcpKeepAlive=true")) {
            assertDoesNotThrow(() -> JdbcUrls.requireLocation(url), url);
        }
    }

    @Test
    void nullAndBlankPassThroughBecauseTheyAreTheCallersOwnCheck() {
        assertDoesNotThrow(() -> JdbcUrls.requireLocation(null));
        assertDoesNotThrow(() -> JdbcUrls.requireLocation("  "));
    }

    // -------------------------------------------------------------------------------------------
    // Refused: everything that is not a location — and the refusal never repeats the input.
    // -------------------------------------------------------------------------------------------

    @Test
    void theThreeCredentialParametersTheFirstDraftBlocklistedAreStillRefusedNowByAbsenceFromTheAllowlist() {
        for (String url : List.of(
                "jdbc:postgresql://db:5432/sm?password=" + SENTINEL,
                "jdbc:postgresql://db:5432/sm?user=" + SENTINEL,
                "jdbc:postgresql://db:5432/sm?sslmode=require&sslpassword=" + SENTINEL,
                "jdbc:postgresql://db:5432/sm?PASSWORD=" + SENTINEL)) {
            String message = refusalFor(url);
            assertTrue(message.contains("not in the allowlist"), message);
        }
    }

    @Test
    void aParameterTheDriverAcceptsButTheAllowlistDoesNotIsRefusedByPositionAndItsNameIsNotRepeated() {
        // `options=` is the review's example: a free-text payload pgjdbc forwards to the server.
        String message = refusalFor("jdbc:postgresql://db:5432/sm?sslmode=require&options=" + SENTINEL);
        assertTrue(message.contains("position 2"), "the refusal locates the parameter. Was: " + message);
        assertFalse(message.contains("options"),
                "…without naming it — a NAME can carry a secret too. Was: " + message);

        // And a parameter whose NAME is the secret.
        message = refusalFor("jdbc:postgresql://db:5432/sm?" + SENTINEL + "=1");
        assertTrue(message.contains("position 1"), message);
    }

    @Test
    void anAllowlistedParameterWithAValueOutsideItsGrammarIsRefusedNamingTheParameterButNotTheValue() {
        Map<String, String> outsideGrammar = Map.of(
                "sslmode", SENTINEL,
                "sslrootcert", "relative/" + SENTINEL,
                "sslkey", "/etc/sm/with space " + SENTINEL,
                "ApplicationName", "has spaces " + SENTINEL,
                "connectTimeout", "10s" + SENTINEL,
                "tcpKeepAlive", "yes" + SENTINEL);
        for (Map.Entry<String, String> e : outsideGrammar.entrySet()) {
            String message = refusalFor("jdbc:postgresql://db:5432/sm?" + e.getKey() + "=" + e.getValue());
            assertTrue(message.contains("'" + e.getKey() + "' value"),
                    "the refusal names the allowlisted parameter. Was: " + message);
        }
    }

    @Test
    void userinfoIsRefusedBecauseTheHostGrammarHasNoPlaceForIt() {
        String message = refusalFor("jdbc:postgresql://alice:" + SENTINEL + "@db:5432/sm");
        assertTrue(message.contains("host part"), message);
        assertFalse(message.contains("alice"), "the role is half a credential. Was: " + message);
    }

    @Test
    void theOtherPgjdbcUrlFormsAndOtherSchemesAreRefused() {
        for (String url : List.of(
                "jdbc:postgresql:sm",                         // pgjdbc's host-less short form
                "jdbc:postgresql:/",                          // …and its bare form
                "jdbc:postgresql://db:5432",                  // no database
                "jdbc:postgresql://db:5432/sm/" + SENTINEL,   // a path after the database
                "jdbc:postgresql://db:5432/sm?sslmode",       // a parameter without '='
                "jdbc:postgresql://db:5432/sm?",              // an empty query — pgjdbc tolerates it, the grammar does not
                "jdbc:postgresql://db:5432/sm?sslmode=require;password=" + SENTINEL, // ';' is not a separator
                "postgresql://db:5432/sm",                    // libpq's URI, not JDBC's
                "jdbc:nope://x/y",
                "jdbc:h2:mem:" + SENTINEL)) {
            refusalFor(url);
        }
    }

    /** Asserts refusal and the two invariants every refusal shares: no sentinel, no {@code jdbc:}. */
    private static String refusalFor(String url) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JdbcUrls.requireLocation(url), "must be refused: " + url);
        String message = e.getMessage();
        assertFalse(message.contains(SENTINEL),
                "the refusal echoed the planted value. Was: " + message);
        assertFalse(message.contains("jdbc:"),
                "the refusal must not carry a URL — nor the literal scheme, so that every stream the "
                        + "migration step prints can be held to 'no jdbc: at all'. Was: " + message);
        assertTrue(message.contains("location only") && message.contains("I6"),
                "the refusal names the rule. Was: " + message);
        return message;
    }
}
