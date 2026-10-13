package com.solace.sm.cp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.cp.config.AgentEnrolmentProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Plan 015 {@code OR-136} (1): the enrolment-token lifetime is operator configuration. The property names, their defaults, the
 * binding of Spring's duration syntax, and the bean factory that fails startup on an invalid pair.
 */
class AgentEnrolmentPropertiesTest {

    private static AgentEnrolmentProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source)).bind("sm.control.agent-gateway.enrolment",
                AgentEnrolmentProperties.class).orElseGet(AgentEnrolmentProperties::new);
    }

    @Test
    void theDefaultsAre24HoursAndThirtyDays() {
        AgentEnrolmentProperties defaults = bind(Map.of());
        assertEquals(Duration.ofHours(24), defaults.getTokenDefaultValidity());
        assertEquals(Duration.ofDays(30), defaults.getTokenMaxValidity());
    }

    @Test
    void aConfiguredPairIsBoundUnderTheNamedProperties() {
        AgentEnrolmentProperties bound = bind(Map.of(
                "sm.control.agent-gateway.enrolment.token-default-validity", "2h",
                "sm.control.agent-gateway.enrolment.token-max-validity", "3d"));
        assertEquals(Duration.ofHours(2), bound.getTokenDefaultValidity());
        assertEquals(Duration.ofDays(3), bound.getTokenMaxValidity());
    }

    private static void create(Duration defaultValidity, Duration maxValidity) {
        AgentEnrolmentProperties properties = new AgentEnrolmentProperties();
        properties.setTokenDefaultValidity(defaultValidity);
        properties.setTokenMaxValidity(maxValidity);
        // the bean factory the context calls: every collaborator is irrelevant to the validity check, which runs first
        new AgentConfiguration().agentEnrolment(null, null, null, null, null, null, TransactionOperations.withoutTransaction(),
                Clock.systemUTC(), properties);
    }

    @Test
    void theContextFailsToStartWhenTheDefaultExceedsTheMaximumOrEitherIsNotPositive() {
        assertThrows(IllegalArgumentException.class, () -> create(Duration.ofDays(31), Duration.ofDays(30)));
        assertThrows(IllegalArgumentException.class, () -> create(Duration.ZERO, Duration.ofDays(30)));
        assertThrows(IllegalArgumentException.class, () -> create(Duration.ofHours(1), Duration.ofSeconds(-1)));
    }
}
