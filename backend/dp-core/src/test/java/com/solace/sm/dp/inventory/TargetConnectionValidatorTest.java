package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.inventory.TargetConnectionSpec;
import com.solace.sm.dp.apply.persistence.TargetConnectionMain;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Plan 013 T1.1: the field grammars of a target connection live in ONE place, beside V007's CHECKs, and the operator
 * command and the service both use it. The table below is the grammar {@code TargetConnectionMainTest} already pins
 * through the command, stated once without a database.
 */
class TargetConnectionValidatorTest {

    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);

    private static TargetConnectionSpec spec(String host, int port, String user, String fingerprint, String root,
            String prefix, Set<String> others) {
        return new TargetConnectionSpec(host, port, user, fingerprint, root, prefix, others);
    }

    private static TargetConnectionSpec good() {
        return spec("vm1.example.test", 22, "sm", FINGERPRINT, "/srv/sm", "sm-", Set.of());
    }

    @Test
    void hosts() {
        for (String ok : List.of("vm1", "vm1.example.test", "10.0.0.5", "a-b.c", "A", "1", "a".repeat(254))) {
            assertTrue(TargetConnectionValidator.isHost(ok), ok);
        }
        for (String bad : List.of("", "-a", "a-", ".a", "a.", "vm1; rm -rf /", "a b", "a_b", "a/b", "::1", "a".repeat(255),
                "vm1\n")) {
            assertFalse(TargetConnectionValidator.isHost(bad), bad);
        }
    }

    @Test
    void sshUsers() {
        for (String ok : List.of("sm", "_sm", "ec2-user", "a".repeat(32), "U1")) {
            assertTrue(TargetConnectionValidator.isSshUser(ok), ok);
        }
        for (String bad : List.of("", "1abc", "-a", "root user", "a".repeat(33), "a.b", "a\n")) {
            assertFalse(TargetConnectionValidator.isSshUser(bad), bad);
        }
    }

    @Test
    void fingerprints() {
        for (String ok : List.of(FINGERPRINT, "SHA256:" + "A".repeat(20), "SHA256:" + "a+/".repeat(10) + "==",
                "SHA256:" + "A".repeat(86))) {
            assertTrue(TargetConnectionValidator.isFingerprint(ok), ok);
        }
        for (String bad : List.of("", "MD5:aa:bb", "SHA256:", "SHA256:" + "A".repeat(19), "SHA256:" + "A".repeat(87),
                "sha256:" + "A".repeat(43), "SHA256:" + "A".repeat(43) + "===", "SHA256:" + "A".repeat(42) + "-")) {
            assertFalse(TargetConnectionValidator.isFingerprint(bad), bad);
        }
    }

    @Test
    void configRootsAreAbsoluteBelowSlashAndNeverClimb() {
        for (String ok : List.of("/srv", "/srv/sm", "/a.b/c_d/e-f", "/srv/sm.d")) {
            assertTrue(TargetConnectionValidator.isConfigRoot(ok), ok);
        }
        for (String bad : List.of("/", "", "srv/sm", "/srv/../etc", "/srv//sm", "/srv/sm/", "/ srv", "/..", "/srv/..sm",
                "/srv/sm\n")) {
            assertFalse(TargetConnectionValidator.isConfigRoot(bad), bad);
        }
    }

    @Test
    void containerPrefixes() {
        for (String ok : List.of("sm-", "a", "0x", "a".repeat(64), "A_b.c-d")) {
            assertTrue(TargetConnectionValidator.isContainerPrefix(ok), ok);
        }
        for (String bad : List.of("", "  ", "-x", "a/b", "a b", "a".repeat(65), ".a", "sm-\n")) {
            assertFalse(TargetConnectionValidator.isContainerPrefix(bad), bad);
        }
    }

    @Test
    void ports() {
        assertTrue(TargetConnectionValidator.isPort(1));
        assertTrue(TargetConnectionValidator.isPort(22));
        assertTrue(TargetConnectionValidator.isPort(65535));
        assertFalse(TargetConnectionValidator.isPort(0));
        assertFalse(TargetConnectionValidator.isPort(-1));
        assertFalse(TargetConnectionValidator.isPort(65536));
    }

    @Test
    void aGoodSpecPasses() {
        TargetConnectionValidator.validate(good());
        TargetConnectionValidator.validate(spec("h", 1, "u", FINGERPRINT, "/a", "p",
                Set.of("SHA256:" + "B".repeat(43), "SHA256:" + "C".repeat(43))));
    }

    @Test
    void aBadSpecNamesTheFieldAndNeverTheValue() {
        String leaky = "SENTINEL-value;";
        List<TargetConnectionSpec> bad = List.of(
                spec(leaky, 22, "sm", FINGERPRINT, "/srv/sm", "sm-", Set.of()),
                spec("h", 0, "sm", FINGERPRINT, "/srv/sm", "sm-", Set.of()),
                spec("h", 22, leaky, FINGERPRINT, "/srv/sm", "sm-", Set.of()),
                spec("h", 22, "sm", leaky, "/srv/sm", "sm-", Set.of()),
                spec("h", 22, "sm", FINGERPRINT, "/" + leaky, "sm-", Set.of()),
                spec("h", 22, "sm", FINGERPRINT, "/srv/sm", leaky, Set.of()),
                spec("h", 22, "sm", FINGERPRINT, "/srv/sm", "sm-", Set.of(leaky)));
        List<String> fields = List.of("host", "port", "sshUser", "hostKeyFingerprint", "allowedConfigRoot",
                "allowedContainerPrefix", "otherHostKeyFingerprints");
        for (int i = 0; i < bad.size(); i++) {
            TargetConnectionSpec s = bad.get(i);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> TargetConnectionValidator.validate(s), fields.get(i));
            assertTrue(e.getMessage().contains(fields.get(i)), e.getMessage());
            assertFalse(e.getMessage().contains("SENTINEL"), e.getMessage());
        }
    }

    /**
     * "One grammar": the command used to hold its own copy of the five patterns. It must hold none now, so a future
     * edit to one place cannot leave the other behind.
     */
    @Test
    void theOperatorCommandHoldsNoGrammarOfItsOwn() {
        long patterns = Arrays.stream(TargetConnectionMain.class.getDeclaredFields())
                .filter(f -> Pattern.class.isAssignableFrom(f.getType()))
                .filter(f -> Modifier.isStatic(f.getModifiers()))
                .count();

        assertEquals(0, patterns, "TargetConnectionMain must call TargetConnectionValidator, not copy its patterns");
    }
}
