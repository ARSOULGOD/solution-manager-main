package com.solace.sm.cp.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long an enrolment token lives (plan 015 {@code OR-136} (1)), as operator configuration rather than constants:
 * {@code sm.control.agent-gateway.enrolment.token-default-validity} (default 24 hours) applies when the issuer names none, and
 * {@code token-max-validity} (default 30 days) is the longest an issuer may ask for. The service refuses to START when the
 * default exceeds the maximum or either is not positive ({@code AgentEnrolmentService}).
 */
@ConfigurationProperties(prefix = "sm.control.agent-gateway.enrolment")
public class AgentEnrolmentProperties {
    /** The one source of the defaults ({@code OR-136} (1)). */
    public static final Duration DEFAULT_TOKEN_VALIDITY = Duration.ofHours(24);
    public static final Duration MAX_TOKEN_VALIDITY = Duration.ofDays(30);

    private Duration tokenDefaultValidity = DEFAULT_TOKEN_VALIDITY;
    private Duration tokenMaxValidity = MAX_TOKEN_VALIDITY;

    public Duration getTokenDefaultValidity() { return tokenDefaultValidity; }
    public void setTokenDefaultValidity(Duration tokenDefaultValidity) { this.tokenDefaultValidity = tokenDefaultValidity; }
    public Duration getTokenMaxValidity() { return tokenMaxValidity; }
    public void setTokenMaxValidity(Duration tokenMaxValidity) { this.tokenMaxValidity = tokenMaxValidity; }
}
