package com.solace.sm.dp.apply.persistence.agent;

import com.solace.sm.dp.metrics.rollup.PartitionRunway;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Agent retention on the {@code OR-77} pattern (plan 015 T4.5; {@code AGW-D31}; {@code OR-130} F-5; {@code OR-134}): calls the
 * owner-owned {@code sm_data.agent_gateway_maintenance()} (V029) and nothing else. The function takes no argument and holds the
 * retention constants itself, so no text or value of this class's reaches a DELETE ({@code I5}). It rides the daily loop that keeps
 * the metrics partition runway ({@link PartitionRunway.DailyJob}).
 *
 * <p>The runtime role cannot delete from {@code agent_command} or {@code agent_lease} except through the function; this class
 * therefore holds no tenant, no id and no time.
 */
public class JdbcAgentRetentionRepository implements PartitionRunway.DailyJob {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAgentRetentionRepository.class);

    /** What one run deleted (both zero when a concurrent run held the function's advisory lock). */
    public record Deleted(long commands, long leases) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /** Longest the function may run: it shares the runway's only periodic task, which must not be held up by a stuck DELETE. */
    static final int STATEMENT_TIMEOUT_SECONDS = 300;

    public JdbcAgentRetentionRepository(NamedParameterJdbcTemplate jdbc) {
        // The JDBC query timeout cancels the statement server-side (the driver sends a cancel request); a template of its own
        // so the shared one's behaviour is untouched. The function sets lock_timeout only, so this bounds the DELETEs themselves.
        org.springframework.jdbc.core.JdbcTemplate bounded = new org.springframework.jdbc.core.JdbcTemplate(
                jdbc.getJdbcTemplate().getDataSource());
        bounded.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
        this.jdbc = new NamedParameterJdbcTemplate(bounded);
    }

    /** One call of the function. */
    public Deleted runOnce() {
        return jdbc.queryForObject("SELECT commands_deleted, leases_deleted FROM sm_data.agent_gateway_maintenance()", Map.of(),
                (rs, i) -> new Deleted(rs.getLong(1), rs.getLong(2)));
    }

    @Override
    public void run() {
        Deleted d = runOnce();
        if (d.commands() > 0 || d.leases() > 0) {
            LOG.info("agent retention removed {} command row(s) and {} lease row(s)", d.commands(), d.leases());
        }
    }
}
