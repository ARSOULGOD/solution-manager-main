package com.solace.sm.dp.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@code sm.dp.fleet.no-validation.settle} as Spring Boot binds it (plan 012 T6, {@code OPS-Q8} (x)): the real
 * {@link Binder} over the real record, so the nested {@code @DefaultValue} and the relaxed key are proven, not assumed.
 */
class FleetPropertiesTest {

    private static FleetProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate("sm.dp.fleet", FleetProperties.class);
    }

    @Test
    void nothingSetGivesThirtySecondsAndTheOtherDefaults() {
        FleetProperties properties = bind(Map.of());

        assertEquals(Duration.ofSeconds(30), properties.noValidation().settle());
        assertEquals(Duration.ofSeconds(1), properties.advanceInterval());
        assertEquals(true, properties.enabled());
    }

    @Test
    void theSettleDelayBindsFromItsDocumentedKey() {
        assertEquals(Duration.ofSeconds(5), bind(Map.of("sm.dp.fleet.no-validation.settle", "5s")).noValidation().settle());
        assertEquals(Duration.ofSeconds(5), bind(Map.of("sm.dp.fleet.noValidation.settle", "5s")).noValidation().settle(),
                "relaxed binding");
        assertEquals(Duration.ZERO, bind(Map.of("sm.dp.fleet.no-validation.settle", "0")).noValidation().settle());
    }

    @Test
    void settingOnlyTheScalesKeepsTheDefaultSettle() {
        FleetProperties properties = bind(Map.of("sm.dp.fleet.advance-interval", "200ms"));

        assertEquals(Duration.ofMillis(200), properties.advanceInterval());
        assertEquals(Duration.ofSeconds(30), properties.noValidation().settle());
    }

    @Test
    void aNegativeSettleFailsStartupInsteadOfBeingClamped() {
        assertThrows(BindException.class, () -> bind(Map.of("sm.dp.fleet.no-validation.settle", "-1s")));
    }
}
