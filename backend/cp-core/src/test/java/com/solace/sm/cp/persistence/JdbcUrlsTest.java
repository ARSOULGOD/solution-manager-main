package com.solace.sm.cp.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.PGProperty;

/**
 * {@link JdbcUrls}' location grammar — cp-core's own copy of dp-core's guard ({@code I7} bars
 * importing dp-core's directly). Mirrors {@code com.solace.sm.dp.apply.persistence.JdbcUrlsTest}: the
 * negative cases plant a SENTINEL where a secret would go and assert the refusal never echoes it; the
 * positive cases are the URLs the install runbook and the test harness actually produce.
 */
class JdbcUrlsTest {

    private static final String SENTINEL = "SNTL-cp-url-9f3c";

    @Test
    void everyAllowlistedParameterIsAPgjdbcPropertyUnderExactlyThatName() {
        for (String name : JdbcUrls.ALLOWED.keySet()) {
            PGProperty property = PGProperty.forName(name);
            assertNotNull(property, "'" + name + "' is not a pgjdbc property");
            assertTrue(name.equals(property.getName()),
                    "'" + name + "' differs in case from pgjdbc's '" + property.getName() + "'");
        }
    }

    @Test
    void theLocationFormsAnInstallerWritesAreAccepted() {
        for (String url : List.of(
                "jdbc:postgresql://db/sm_control",
                "jdbc:postgresql://db.example.internal:5432/sm_control",
                "jdbc:postgresql://10.0.0.7:5432/sm_control",
                "jdbc:postgresql://[::1]:5432/sm_control",
                "jdbc:postgresql://db1:5432,db2:5432/sm_control",
                "jdbc:postgresql://db:5432/sm_control?sslmode=verify-full&sslrootcert=/etc/sm/ca.pem",
                "jdbc:postgresql://db:5432/sm_control?ApplicationName=solution-manager.cp"
                        + "&connectTimeout=10&socketTimeout=30&loginTimeout=10&tcpKeepAlive=true")) {
            assertDoesNotThrow(() -> JdbcUrls.requireLocation(url), url);
        }
    }

    @Test
    void nullAndBlankPassThroughBecauseTheyAreTheCallersOwnCheck() {
        assertDoesNotThrow(() -> JdbcUrls.requireLocation(null));
        assertDoesNotThrow(() -> JdbcUrls.requireLocation("  "));
    }

    @Test
    void passwordUserAndSslpasswordAreRefusedByAbsenceFromTheAllowlist() {
        for (String url : List.of(
                "jdbc:postgresql://db:5432/sm_control?password=" + SENTINEL,
                "jdbc:postgresql://db:5432/sm_control?user=" + SENTINEL,
                "jdbc:postgresql://db:5432/sm_control?sslmode=require&sslpassword=" + SENTINEL,
                "jdbc:postgresql://db:5432/sm_control?PASSWORD=" + SENTINEL)) {
            String message = refusalFor(url);
            assertTrue(message.contains("not in the allowlist"), message);
        }
    }

    @Test
    void aParameterOutsideTheAllowlistIsRefusedByPositionAndItsNameIsNotRepeated() {
        String message = refusalFor("jdbc:postgresql://db:5432/sm_control?sslmode=require&options=" + SENTINEL);
        assertTrue(message.contains("position 2"), message);
        assertFalse(message.contains("options"), message);
    }

    @Test
    void anAllowlistedParameterWithAValueOutsideItsGrammarIsRefusedNamingTheParameterButNotTheValue() {
        Map<String, String> outsideGrammar = Map.of(
                "sslmode", SENTINEL,
                "sslrootcert", "relative/" + SENTINEL,
                "ApplicationName", "has spaces " + SENTINEL,
                "connectTimeout", "10s" + SENTINEL,
                "tcpKeepAlive", "yes" + SENTINEL);
        for (Map.Entry<String, String> e : outsideGrammar.entrySet()) {
            String message = refusalFor("jdbc:postgresql://db:5432/sm_control?" + e.getKey() + "=" + e.getValue());
            assertTrue(message.contains("'" + e.getKey() + "' value"), message);
        }
    }

    @Test
    void userinfoIsRefusedBecauseTheHostGrammarHasNoPlaceForIt() {
        String message = refusalFor("jdbc:postgresql://alice:" + SENTINEL + "@db:5432/sm_control");
        assertTrue(message.contains("host part"), message);
        assertFalse(message.contains("alice"), message);
    }

    @Test
    void theOtherPgjdbcUrlFormsAndOtherSchemesAreRefused() {
        for (String url : List.of(
                "jdbc:postgresql:sm_control",
                "jdbc:postgresql://db:5432",
                "jdbc:postgresql://db:5432/sm_control/" + SENTINEL,
                "jdbc:postgresql://db:5432/sm_control?sslmode",
                "jdbc:postgresql://db:5432/sm_control?",
                "postgresql://db:5432/sm_control",
                "jdbc:nope://x/y")) {
            refusalFor(url);
        }
    }

    @Test
    void locationOnlyStripsTheQueryStringAndPassesNullThrough() {
        assertEquals("jdbc:postgresql://db:5432/sm_control",
                JdbcUrls.locationOnly("jdbc:postgresql://db:5432/sm_control?sslmode=require"));
        assertEquals("jdbc:postgresql://db:5432/sm_control",
                JdbcUrls.locationOnly("jdbc:postgresql://db:5432/sm_control"));
        assertNull(JdbcUrls.locationOnly(null));
    }

    private static String refusalFor(String url) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JdbcUrls.requireLocation(url), "must be refused: " + url);
        String message = e.getMessage();
        assertFalse(message.contains(SENTINEL), "the refusal echoed the planted value. Was: " + message);
        assertFalse(message.contains("jdbc:"), "the refusal must not carry a URL. Was: " + message);
        assertTrue(message.contains("location only") && message.contains("I6"),
                "the refusal names the rule. Was: " + message);
        return message;
    }
}
