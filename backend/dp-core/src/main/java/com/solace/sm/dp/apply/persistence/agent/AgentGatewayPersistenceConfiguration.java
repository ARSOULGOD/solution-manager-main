package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.agent.AgentCommandPayloadSource;
import com.solace.sm.dp.api.agw.AgentRegistrationSource;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.EnrollmentCertificateIssuer;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.LeaseIssuer;
import java.time.Clock;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The JDBC-backed agent-gateway ports over {@code sm_data} (plan 015 T2.1b, HF4), imported by
 * {@link com.solace.sm.dp.agent.AgentGatewayPortsConfiguration}. It lives in the persistence package because the architecture
 * confines {@code org.springframework.jdbc} to {@code ..persistence..}. Each port is its own bean and none is an aggregate (the
 * branch's {@code AgentGatewayPorts} is dropped).
 *
 * <p>Collaborators that only the launcher's adapters supply (the enrolment and lease issuers, the control plane's registration
 * source, the payload source) are resolved when a call needs them, through {@link ObjectProvider}, so a
 * context that imports the data plane without the gateway still starts; a call that needs a missing one fails with a coded
 * {@code INTERNAL_UNAVAILABLE}, never a null. The report and telemetry watermarks are plain helpers, NOT port beans: T3.3 owns the
 * {@code ActualStatePort} bean and T3.4 the {@code AgentTelemetryPort} bean, and no main-source bean here is either.
 *
 * <p>Property location, when a switch is needed: {@code dp.apply.persistence.agent}; there is no global agent switch (Global
 * constraint 5).
 */
@Configuration(proxyBeanMethods = false)
public class AgentGatewayPersistenceConfiguration {

    /** The collaborator, resolved at call time; absent or AMBIGUOUS is a coded {@code INTERNAL_UNAVAILABLE}, never a raw exception. */
    private static <T> T resolved(ObjectProvider<T> provider, String what) {
        T value;
        try {
            value = provider.getIfAvailable();
        } catch (BeansException ambiguous) {
            throw new GatewayPortException(GatewayCode.INTERNAL_UNAVAILABLE, what + " is not uniquely wired");
        }
        if (value == null) {
            throw new GatewayPortException(GatewayCode.INTERNAL_UNAVAILABLE, what + " is not wired");
        }
        return value;
    }

    private static <T> java.util.function.Supplier<T> required(ObjectProvider<T> provider, String what) {
        return () -> resolved(provider, what);
    }

    /** T4.5: the daily agent retention, run by {@code PartitionRunway}'s loop. D adds this bean (HF4, additive). */
    @Bean
    JdbcAgentRetentionRepository agentRetention(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcAgentRetentionRepository(smDataJdbc);
    }

    @Bean
    AgentAuthority agentAuthority(NamedParameterJdbcTemplate smDataJdbc, @Qualifier("smDataClock") Clock smDataClock,
            TransactionTemplate smDataTransactions) {
        return new AgentAuthority(smDataJdbc, smDataClock, smDataTransactions);
    }

    /** The bean IS the {@code AgentIdentityPort}: there is no second alias bean of it. */
    @Bean
    JdbcAgentIdentityRepository agentIdentityRepository(NamedParameterJdbcTemplate smDataJdbc,
            TransactionTemplate smDataTransactions, @Qualifier("smDataClock") Clock smDataClock, AgentAuthority agentAuthority,
            ObjectProvider<AgentRegistrationSource> registrations) {
        return new JdbcAgentIdentityRepository(smDataJdbc, smDataTransactions, smDataClock, agentAuthority,
                required(registrations, "the control plane's agent registration source"));
    }

    @Bean
    AgentSessionPort agentSessionPort(NamedParameterJdbcTemplate smDataJdbc, @Qualifier("smDataClock") Clock smDataClock,
            AgentAuthority agentAuthority, JdbcAgentIdentityRepository agentIdentityRepository,
            @org.springframework.beans.factory.annotation.Value("${sm.dp.agent.session-live-window:PT2M}")
            java.time.Duration sessionLiveWindow) {
        return new JdbcAgentSessionRepository(smDataJdbc, smDataClock, agentAuthority, agentIdentityRepository, sessionLiveWindow);
    }

    /** The operator surface's read of an agent's connection (plan 015 T4.3): read-only, no secret column. */
    @Bean
    com.solace.sm.dp.api.agent.AgentSessionFacts agentSessionFacts(NamedParameterJdbcTemplate smDataJdbc,
            @Qualifier("smDataClock") Clock smDataClock,
            @org.springframework.beans.factory.annotation.Value("${sm.dp.agent.session-live-window:PT2M}")
            java.time.Duration sessionLiveWindow) {
        return new JdbcAgentSessionFacts(smDataJdbc, smDataClock, sessionLiveWindow);
    }

    @Bean
    AgentLeasePort agentLeasePort(NamedParameterJdbcTemplate smDataJdbc, TransactionTemplate smDataTransactions,
            @Qualifier("smDataClock") Clock smDataClock, AgentAuthority agentAuthority,
            JdbcAgentIdentityRepository agentIdentityRepository, ObjectProvider<LeaseIssuer> leaseIssuer) {
        return new JdbcAgentLeaseRepository(smDataJdbc, smDataTransactions, smDataClock, agentAuthority,
                agentIdentityRepository, required(leaseIssuer, "the lease issuer"));
    }

    @Bean
    AgentEnrollmentPort agentEnrollmentPort(NamedParameterJdbcTemplate smDataJdbc, TransactionTemplate smDataTransactions,
            @Qualifier("smDataClock") Clock smDataClock, ObjectProvider<EnrollmentCertificateIssuer> issuer) {
        return new JdbcAgentEnrollmentRepository(smDataJdbc, smDataTransactions, smDataClock,
                required(issuer, "the enrolment certificate issuer"));
    }

    @Bean
    AgentCommandPort agentCommandPort(NamedParameterJdbcTemplate smDataJdbc, TransactionTemplate smDataTransactions,
            @Qualifier("smDataClock") Clock smDataClock, AgentAuthority agentAuthority,
            ObjectProvider<AgentCommandPayloadSource> payloads, ObjectProvider<AckStartSettler> ackStartSettler) {
        // T3.1 supplies the real source. Until then a pull answers INTERNAL_UNAVAILABLE: the repository stores no byte and has none.
        AgentCommandPayloadSource source = new AgentCommandPayloadSource() {
            @Override
            public com.solace.sm.dp.api.agw.CommandPayload payloadFor(Command command) {
                return resolved(payloads, "the command payload source").payloadFor(command);
            }

            @Override
            public java.util.Optional<String> plainDigestFor(Command command) {
                try {
                    return resolved(payloads, "the command payload source").plainDigestFor(command);
                } catch (GatewayPortException unwired) {
                    return java.util.Optional.empty();       // a notice without a digest; the pull is what refuses
                }
            }
        };
        return new JdbcAgentCommandRepository(smDataJdbc, smDataTransactions, smDataClock, agentAuthority, source,
                ackStartSettler.getIfAvailable(() -> AckStartSettler.NONE));
    }

    /**
     * T3.1: which transport reaches a placement's target, and which agent owns an agent placement. One object answers both
     * ({@code PlacementTransports} for the router and the pollers, {@code AgentPlacementCatalog} for the adapter).
     */
    @Bean
    JdbcAgentPlacementCatalog agentPlacementCatalog(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcAgentPlacementCatalog(smDataJdbc);
    }

    /**
     * Plan 016 T9: the stored per-placement log caps. The bean is both the persistence port of the cap service and the
     * {@code AgentLogRetentionSource} the gateway reads when a session opens.
     */
    @Bean
    com.solace.sm.dp.apply.persistence.JdbcPlacementLogRetentionRepository placementLogRetention(NamedParameterJdbcTemplate smDataJdbc) {
        return new com.solace.sm.dp.apply.persistence.JdbcPlacementLogRetentionRepository(smDataJdbc);
    }

    /** T4.1: the discovery tombstones and the reads beside them; the tombstones an instance's END causes are V031's trigger, not a call. */
    @Bean
    JdbcAgentDiscoveryRepository agentDiscoveryRepository(NamedParameterJdbcTemplate smDataJdbc) {
        return new JdbcAgentDiscoveryRepository(smDataJdbc);
    }

    @Bean
    AgentAuditPort agentAuditPort(NamedParameterJdbcTemplate smDataJdbc, AgentAuthority agentAuthority) {
        return new JdbcAgentAuditRepository(smDataJdbc, agentAuthority);
    }

    // ---- persistence HELPERS, not ports: T3.3 owns the ActualStatePort bean and T3.4 the AgentTelemetryPort bean ----------

    @Bean
    JdbcAgentReportRepository agentReportWatermarks(NamedParameterJdbcTemplate smDataJdbc, TransactionTemplate smDataTransactions,
            @Qualifier("smDataClock") Clock smDataClock, AgentAuthority agentAuthority) {
        return new JdbcAgentReportRepository(smDataJdbc, smDataTransactions, smDataClock, agentAuthority);
    }

    @Bean
    JdbcAgentTelemetryWatermarkRepository agentTelemetryWatermarks(NamedParameterJdbcTemplate smDataJdbc,
            TransactionTemplate smDataTransactions, @Qualifier("smDataClock") Clock smDataClock, AgentAuthority agentAuthority) {
        return new JdbcAgentTelemetryWatermarkRepository(smDataJdbc, smDataTransactions, smDataClock, agentAuthority);
    }
}
