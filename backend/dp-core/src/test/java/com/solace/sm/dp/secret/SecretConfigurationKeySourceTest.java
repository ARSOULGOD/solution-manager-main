package com.solace.sm.dp.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * OR-85: the master key comes from the process environment (or the _FILE of it), nowhere else. A command-line
 * argument is visible in {@code ps}; a {@code -D} property, {@code SPRING_APPLICATION_JSON} or a properties file
 * is a way to commit a key. Those sources are refused, naming the variable, unless the test-only flag is set.
 */
class SecretConfigurationKeySourceTest {

    private static final String KEY = EnvironmentMasterKeyProvider.KEY;

    private static StandardEnvironment environment(Map<String, Object> processEnvironment,
            Map<String, Object> otherSource) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        processEnvironment));
        env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", otherSource));
        return env;
    }

    @Test
    void aVariableInTheProcessEnvironmentIsRead() {
        Function<String, String> lookup = SecretConfiguration.keyLookup(environment(Map.of(KEY, "abc"), Map.of()));

        assertEquals("abc", lookup.apply(KEY));
        assertNull(lookup.apply(EnvironmentMasterKeyProvider.PREVIOUS));
    }

    @Test
    void aKeyInAnyOtherPropertySourceIsRefusedNamingTheVariableAndNeverTheValue() {
        Function<String, String> lookup =
                SecretConfiguration.keyLookup(environment(Map.of(), Map.of(KEY, "SENTINEL-on-the-command-line")));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> lookup.apply(KEY));

        assertTrue(e.getMessage().contains(KEY), e.getMessage());
        assertTrue(!e.getMessage().contains("SENTINEL"), e.getMessage());
    }

    @Test
    void theRelaxedBindingSpellingDoesNotSmuggleOneInEither() {
        Function<String, String> lookup =
                SecretConfiguration.keyLookup(environment(Map.of(), Map.of("sm.dp.master-key", "SENTINEL")));

        // Environment.getProperty("SM_DP_MASTER_KEY") does not resolve sm.dp.master-key (T2.6 M16), so the
        // lookup reports unset, which the provider turns into its own "not set" refusal
        assertNull(lookup.apply(KEY));
    }

    @Test
    void theTestOnlyFlagAdmitsAnyPropertySource() {
        Function<String, String> lookup = SecretConfiguration.keyLookup(environment(Map.of(),
                Map.of(KEY, "from-a-test", SecretConfiguration.TEST_ACCEPT_PROPERTY_SOURCES, "true")));

        assertEquals("from-a-test", lookup.apply(KEY));
    }
}
