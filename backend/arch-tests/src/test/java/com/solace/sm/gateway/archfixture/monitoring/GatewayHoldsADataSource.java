package com.solace.sm.gateway.archfixture.monitoring;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Planted violation (plan 011 P11-7.2, M-Q11): the gateway, which now hosts the monitoring routes, reading the metrics store
 * itself instead of through {@code dp-api}. {@code console-gateway} holds NO datasource and NO database role; the
 * data plane's reads reach it only as {@code MetricsQueryService} and {@code MetricsExportService}, and
 * {@code ApplyPathBoundaryTest.theGatewayHoldsNoDatasource} must report this.
 *
 * <p>Never executed. <b>Do not copy this pattern into production code.</b>
 */
public final class GatewayHoldsADataSource {

    private final DataSource dataSource;

    public GatewayHoldsADataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Long seriesCount(java.util.UUID tenant) {
        return new NamedParameterJdbcTemplate(dataSource).queryForObject(
                "SELECT count(*) FROM sm_metrics.series WHERE tenant_id = :t", Map.of("t", tenant), Long.class);
    }
}
