package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.EnrollmentCertificateIssuer;
import com.solace.sm.dp.api.agw.EnrollmentIssuance;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.GatewayPortException;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.EnrollmentResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The data plane's half of an enrolment (plan 015 T2.1b; {@code BE-D71}): the issuer (the control plane, across the launcher's
 * adapter) consumes the token, signs the certificate and creates its target; THIS class then records, in ONE data-plane
 * transaction, the agent's {@code target_connection} (transport {@code agent}, the issuer's envelope), its
 * {@code agent_identity} and one {@code enrolled} {@code target_connection_event} (actor kind {@code agent}).
 *
 * <p>Idempotent by the request: the issuer replays the same token and CSR to the same certificate and ids, and every insert here
 * is {@code ON CONFLICT DO NOTHING} on its primary key, so a retry after a crash between the two planes completes the missing
 * half and a plain replay changes nothing. The issuer is called FIRST, so a refused enrolment writes nothing here. The
 * organization comes from the issuance (the control plane's), never from the request, and the thumbprint is the SHA-256 of the issued
 * leaf certificate (a public identifier).
 *
 * <p>This is the only other class (besides {@link JdbcAgentSessionRepository}) that creates the identity the one named unbound
 * read resolves; it reads nothing by thumbprint itself.
 */
public final class JdbcAgentEnrollmentRepository implements AgentEnrollmentPort {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Supplier<EnrollmentCertificateIssuer> issuer;

    public JdbcAgentEnrollmentRepository(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, Clock clock,
            Supplier<EnrollmentCertificateIssuer> issuer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
        this.issuer = issuer;
    }

    @Override
    public EnrollmentResult enroll(EnrollmentRequest request) {
        EnrollmentIssuance issued = issuer.get().issue(request);
        if (issued.certificateChainDer().isEmpty()) {
            throw new GatewayPortException(GatewayCode.INTERNAL_UNAVAILABLE, "the issuer returned no certificate");
        }
        String thumbprint = AgentSql.sha256Hex(issued.certificateChainDer().get(0));
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("tenant", issued.tenantId())
                .addValue("agent", issued.agentId()).addValue("target", issued.targetId())
                .addValue("environment", issued.environmentId()).addValue("catalog", issued.catalogSolutionId())
                .addValue("solution", issued.solution()).addValue("version", issued.declaredPackageVersion())
                .addValue("thumbprint", thumbprint).addValue("agentEpoch", issued.agentRevocationEpoch())
                .addValue("fleetEpoch", issued.fleetRevocationEpoch())
                .addValue("caps", AgentSql.capabilityArray(issued.capabilities()))
                .addValue("root", issued.envelope().allowedConfigRoot())
                .addValue("prefix", issued.envelope().allowedContainerPrefix()).addValue("createdBy", issued.issuingPrincipalId())
                .addValue("now", AgentSql.at(clock.instant()))
                .addValue("event", UUID.nameUUIDFromBytes(("enrolled|" + issued.agentId()).getBytes(StandardCharsets.UTF_8)))
                .addValue("correlation", request.origin().correlationId())
                .addValue("address", request.origin().clientAddress());
        tx.executeWithoutResult(status -> {
            AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, agent_id,"
                    + " allowed_config_root, allowed_container_prefix, created_by, created_at)"
                    + " VALUES (:tenant, :target, 'agent', :agent, :root, :prefix, :createdBy, :now)"
                    + " ON CONFLICT (tenant_id, target_id) DO NOTHING", p));
            AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.agent_identity (tenant_id, agent_id, environment_id,"
                    + " catalog_solution_id, solution, declared_package_version, target_id, certificate_thumbprint,"
                    + " agent_epoch, fleet_epoch, capabilities, created_at) VALUES (:tenant, :agent, :environment, :catalog,"
                    + " :solution, :version, :target, :thumbprint, :agentEpoch, :fleetEpoch, CAST(:caps AS text[]), :now)"
                    + " ON CONFLICT (tenant_id, agent_id) DO NOTHING", p));
            AgentSql.write(() -> jdbc.update("INSERT INTO sm_data.target_connection_event (tenant_id, target_id, event_id,"
                    + " kind, actor_kind, actor, correlation_id, client_address, recorded_at)"
                    + " VALUES (:tenant, :target, :event, 'enrolled', 'agent', :agent, :correlation, :address, :now)"
                    + " ON CONFLICT (tenant_id, target_id, event_id) DO NOTHING", p));
        });
        return new EnrollmentResult(issued.agentId(), issued.certificateChainDer(), issued.trustBundlePem(), issued.validFrom(),
                issued.validTo(), issued.connectEndpoint(), issued.environmentId(), issued.targetId(), issued.envelope(),
                issued.capabilities(), issued.agentRevocationEpoch(), issued.fleetRevocationEpoch());
    }
}
