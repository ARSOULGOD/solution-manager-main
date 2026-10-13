package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Plan 010 T6.6: the walkthrough's target comes from system properties and nothing else, and a malformed or incomplete
 * set is refused BEFORE a container is started or a host is contacted. Pure: no Docker, no network.
 *
 * <p>Every refusal names the offending PROPERTY, never a value (I6): the SSH key path, the fingerprint and above all the
 * broker password must not travel into a failure report.
 */
class WalkthroughTargetTest {

    private static final String FINGERPRINT = "SHA256:" + "A".repeat(43);
    private static final String PASSWORD = "p4ssw0rd-never-logged";

    @TempDir
    Path tmp;

    private Map<String, String> properties;
    private Map<String, String> environment;

    @BeforeEach
    void minimalTarget() throws IOException {
        Path key = Files.writeString(tmp.resolve("id_walkthrough"), "-----BEGIN PRIVATE KEY-----\nkey-bytes\n");
        properties = new HashMap<>();
        properties.put("sm.walkthrough.ssh.host", "e2e-host.example.test");
        properties.put("sm.walkthrough.ssh.user", "ec2-user");
        properties.put("sm.walkthrough.ssh.fingerprint", FINGERPRINT);
        properties.put("sm.walkthrough.ssh.key-file", key.toString());
        properties.put("sm.walkthrough.ssh.container", "cache-engine");
        properties.put("sm.walkthrough.config-dir", "/srv/cache-engine/config");
        environment = new HashMap<>();
    }

    private WalkthroughTarget parse() {
        return WalkthroughTarget.parse(properties::get, environment::get);
    }

    @Test
    void aCompleteSetParsesAndEveryOptionalPropertyHasItsDocumentedDefault() {
        WalkthroughTarget target = parse();

        assertEquals("e2e-host.example.test", target.host());
        assertEquals(22, target.port());
        assertEquals("ec2-user", target.user());
        assertEquals(FINGERPRINT, target.fingerprint());
        assertEquals("cache-engine", target.container());
        assertEquals("/srv/cache-engine/config", target.configDirectory());
        assertEquals("/app/external/spring/config", target.containerConfigPath());
        assertEquals("application.yml", target.fileName());
        assertEquals(8081, target.healthPort());
        assertEquals("via-ssh", target.acquisition(), "the DP reaches the health port through the SSH session unless told otherwise");
        assertEquals("e2e-walkthrough", target.namespace());
        assertTrue(target.restartOnRestore(), "a restore that left the container on the staged config would hide state");
        assertNull(target.broker(), "no Cache broker configured: the staged config keeps solace disabled");
        assertNull(target.brokerPassword());
    }

    @Test
    void theGateIsTheHostPropertyAndANonBlankValueEnablesIt() {
        assertEquals("sm.walkthrough.ssh.host", WalkthroughTarget.GATE_PROPERTY);
        assertTrue(WalkthroughTarget.isRequested(properties::get));
        properties.put("sm.walkthrough.ssh.host", "  ");
        assertFalse(WalkthroughTarget.isRequested(properties::get), "a blank host does not request a live run");
        properties.remove("sm.walkthrough.ssh.host");
        assertFalse(WalkthroughTarget.isRequested(properties::get));
    }

    @Test
    void eachRequiredPropertyIsNamedWhenMissingAndNoValueIsEchoed() {
        for (String name : List.of("sm.walkthrough.ssh.host", "sm.walkthrough.ssh.user",
                "sm.walkthrough.ssh.fingerprint", "sm.walkthrough.ssh.key-file", "sm.walkthrough.ssh.container",
                "sm.walkthrough.config-dir")) {
            Map<String, String> shortened = new HashMap<>(properties);
            shortened.remove(name);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> WalkthroughTarget.parse(shortened::get, environment::get), name);

            assertTrue(refused.getMessage().contains(name), name + " is named: " + refused.getMessage());
            assertFalse(refused.getMessage().contains(FINGERPRINT), "no value is echoed");
            assertFalse(refused.getMessage().contains("id_walkthrough"), "no path is echoed");
        }
    }

    @Test
    void malformedValuesAreRefusedByPropertyNameNotByValue() {
        record Bad(String property, String value) {
        }
        for (Bad bad : List.of(new Bad("sm.walkthrough.ssh.port", "ssh"), new Bad("sm.walkthrough.ssh.port", "0"),
                new Bad("sm.walkthrough.ssh.port", "70000"), new Bad("sm.walkthrough.ssh.fingerprint", "MD5:ab:cd"),
                new Bad("sm.walkthrough.ssh.user", "root; reboot"), new Bad("sm.walkthrough.ssh.host", "bad host"),
                new Bad("sm.walkthrough.config-dir", "relative/dir"), new Bad("sm.walkthrough.config-dir", "/"),
                new Bad("sm.walkthrough.config-dir", "/srv/../etc"), new Bad("sm.walkthrough.ssh.container", "-x"),
                new Bad("sm.walkthrough.health-port", "99999"), new Bad("sm.walkthrough.acquisition", "carrier-pigeon"), new Bad("sm.walkthrough.file-name", "../x.yml"))) {
            Map<String, String> changed = new HashMap<>(properties);
            changed.put(bad.property(), bad.value());

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> WalkthroughTarget.parse(changed::get, environment::get), bad.toString());

            assertTrue(refused.getMessage().contains(bad.property()), bad + " -> " + refused.getMessage());
            if (bad.value().length() > 3) {
                assertFalse(refused.getMessage().contains(bad.value()),
                        "the offending value is not echoed: " + refused.getMessage());
            }
        }
    }

    @Test
    void aKeyFileThatIsNotReadableIsRefusedByNameAndNeverRead(@TempDir Path other) {
        properties.put("sm.walkthrough.ssh.key-file", other.resolve("absent").toString());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, this::parse);

        assertTrue(refused.getMessage().contains("sm.walkthrough.ssh.key-file"));
        assertFalse(refused.getMessage().contains("absent"));
    }

    @Test
    void theBrokerPasswordComesFromTheEnvironmentOnlyAndAPropertyCarryingOneIsRefused() {
        properties.put("sm.walkthrough.cache.broker.host", "tcp://cache-broker.example.test:55555");
        properties.put("sm.walkthrough.cache.broker.vpn", "cache-vpn");
        properties.put("sm.walkthrough.cache.broker.username", "cache-client");
        properties.put("sm.walkthrough.cache.broker.password", PASSWORD);
        environment.put("SM_E2E_BROKER_PASSWORD", PASSWORD);   // even WITH the right variable set, a property is refused

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, this::parse);

        assertTrue(refused.getMessage().contains("sm.walkthrough.cache.broker.password"), "the property is named");
        assertTrue(refused.getMessage().contains("SM_E2E_BROKER_PASSWORD"), "it says where the password belongs");
        assertFalse(refused.getMessage().contains(PASSWORD), "I6: the value is never echoed");

        properties.remove("sm.walkthrough.cache.broker.password");
        WalkthroughTarget target = parse();
        assertNotNull(target.broker());
        assertEquals("cache-vpn", target.broker().vpn());
        assertArrayEquals(PASSWORD.toCharArray(), target.brokerPassword());
        assertFalse(target.toString().contains(PASSWORD), "I6: toString");
        assertFalse(target.broker().toString().contains(PASSWORD), "I6: the broker record's toString");
        assertFalse(target.toString().contains("key-bytes"), "the key file's content is never loaded here");
    }

    @Test
    void aPartialBrokerConfigurationIsRefusedNotSilentlyIgnored() {
        properties.put("sm.walkthrough.cache.broker.host", "tcp://cache-broker.example.test:55555");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, this::parse);

        assertTrue(refused.getMessage().contains("sm.walkthrough.cache.broker.vpn"), refused.getMessage());
        assertTrue(refused.getMessage().contains("sm.walkthrough.cache.broker.username"), refused.getMessage());
        assertFalse(refused.getMessage().contains("cache-broker.example.test"));
    }

    @Test
    void aBrokerWithoutAPasswordInTheEnvironmentIsRefusedBecauseTheStagedSecretWouldBeEmpty() {
        properties.put("sm.walkthrough.cache.broker.host", "tcp://cache-broker.example.test:55555");
        properties.put("sm.walkthrough.cache.broker.vpn", "cache-vpn");
        properties.put("sm.walkthrough.cache.broker.username", "cache-client");

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, this::parse);

        assertTrue(refused.getMessage().contains("SM_E2E_BROKER_PASSWORD"), refused.getMessage());
    }

    @Test
    void theEnvelopeTheTargetIsRegisteredWithContainsTheCacheContainerAndItsDirectory() {
        WalkthroughTarget target = parse();

        assertEquals("/srv/cache-engine/config", target.allowedConfigRoot(),
                "the config directory is its own root: a declare naming any sibling directory is outside it");
        assertEquals("cache-engine", target.allowedContainerPrefix());
        assertTrue(target.container().startsWith(target.allowedContainerPrefix()));
    }

    @Test
    void theEvidenceDescriptionNamesTheTargetWithoutAnySecretOrKeyPath() {
        environment.put("SM_E2E_BROKER_PASSWORD", PASSWORD);
        String description = parse().describeForEvidence();

        assertTrue(description.contains("e2e-host.example.test"), description);
        assertTrue(description.contains("cache-engine"), description);
        assertFalse(description.contains("id_walkthrough"), "the key's path stays out of the evidence");
        assertFalse(description.contains(PASSWORD));
    }

    @Test
    void theRehearsalTargetIsSyntheticAndReadsNoPropertyOrEnvironment(@TempDir Path other) throws IOException {
        Path key = Files.writeString(other.resolve("throwaway"), "not a key");

        WalkthroughTarget rehearsal = WalkthroughTarget.rehearsal(key);

        assertTrue(rehearsal.host().endsWith(".example.test"), "a name that cannot resolve to a real host");
        assertEquals(WalkthroughTarget.REHEARSAL_BROKER_PASSWORD, new String(rehearsal.brokerPassword()));
        assertNotNull(rehearsal.broker());
        assertFalse(rehearsal.toString().contains(WalkthroughTarget.REHEARSAL_BROKER_PASSWORD));
        assertEquals(rehearsal.configDirectory(), rehearsal.allowedConfigRoot());
    }
}
