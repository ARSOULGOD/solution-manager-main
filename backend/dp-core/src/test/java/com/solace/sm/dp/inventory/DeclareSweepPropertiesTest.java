package com.solace.sm.dp.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Plan 010 T4.2: {@code sm.dp.declare.abandon-after}, default ten minutes. */
class DeclareSweepPropertiesTest {

    @Test
    void theDefaultIsTenMinutes() {
        assertEquals(Duration.ofMinutes(10), new DeclareSweepProperties(null).abandonAfter());
    }

    @Test
    void aZeroOrNegativeWindowIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new DeclareSweepProperties(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new DeclareSweepProperties(Duration.ofSeconds(-1)));
    }

    @Test
    void theKebabCasePropertyBindsAndAbsenceDefaults() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(Enable.class);
        runner.withPropertyValues("sm.dp.declare.abandon-after=45s").run(context -> assertEquals(
                Duration.ofSeconds(45), context.getBean(DeclareSweepProperties.class).abandonAfter()));
        runner.run(context -> assertEquals(Duration.ofMinutes(10),
                context.getBean(DeclareSweepProperties.class).abandonAfter()));
    }

    @EnableConfigurationProperties(DeclareSweepProperties.class)
    static class Enable {
    }
}
