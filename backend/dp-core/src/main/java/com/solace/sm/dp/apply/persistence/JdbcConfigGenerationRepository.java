package com.solace.sm.dp.apply.persistence;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.config.GenerationRecord;
import com.solace.sm.dp.config.InstanceHead;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * {@code sm_data.config_generation} and the head columns of {@code instance_state}, over the runtime credential
 * (plan 010 T5.2). It lives in this package because JDBC is confined here; the service in {@code dp.config}
 * reaches it only through {@link ConfigGenerationRows}.
 *
 * <p>{@code I5}: every statement binds its parameters and names {@code tenant_id}. The three JSON columns are bound
 * redacted ({@link Sql#redacted}) so Spring's TRACE bind-value line prints a length, never the draft.
 */
public final class JdbcConfigGenerationRepository implements ConfigGenerationRows {

    private static final String FULL_COLUMNS = "g.tenant_id, g.instance_id, g.generation, g.package_solution,"
            + " g.package_version, g.component_selection::text AS component_selection,"
            + " g.draft_values::text AS draft_values, g.rendered_entries::text AS rendered_entries,"
            + " g.content_digest, g.origin, g.derived_from_generation, g.created_by, g.created_at, g.state_version";

    private static final String VIEW_COLUMNS = "g.generation, g.package_solution, g.package_version,"
            + " g.content_digest, g.origin, g.derived_from_generation, g.created_by, g.created_at, g.state_version";

    private static final RowMapper<GenerationRecord> FULL = JdbcConfigGenerationRepository::full;
    private static final RowMapper<GenerationView> VIEW = JdbcConfigGenerationRepository::view;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcConfigGenerationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<InstanceHead> lockInstance(TenantId tenant, InstanceId instance) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT state_version, last_generation FROM sm_data.instance_state"
                            + " WHERE tenant_id = :t AND instance_id = :i FOR UPDATE",
                    key(tenant, instance),
                    (rs, n) -> new InstanceHead(rs.getLong("state_version"), rs.getLong("last_generation"))));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<GenerationRecord> latest(TenantId tenant, InstanceId instance) {
        return jdbc.query(
                "SELECT " + FULL_COLUMNS + " FROM sm_data.config_generation g"
                        + " JOIN sm_data.instance_state s ON s.tenant_id = g.tenant_id"
                        + " AND s.instance_id = g.instance_id AND s.last_generation = g.generation"
                        + " WHERE g.tenant_id = :t AND g.instance_id = :i",
                key(tenant, instance), FULL).stream().findFirst();
    }

    @Override
    public Optional<String> layout(TenantId tenant, InstanceId instance) {
        return jdbc.query(
                "SELECT l.layout::text AS layout FROM sm_data.designer_layout l"
                        + " WHERE l.tenant_id = :t AND l.instance_id = :i",
                key(tenant, instance), (rs, n) -> rs.getString("layout")).stream().findFirst();
    }

    @Override
    public void saveLayout(TenantId tenant, InstanceId instance, String layoutJson, java.util.UUID actor,
            java.time.Instant at) {
        MapSqlParameterSource params = key(tenant, instance).addValue("layout", layoutJson)
                .addValue("by", actor).addValue("at", Sql.at(at));
        // The caller holds the instance row lock, so nothing else writes this record between the two statements.
        int changed = Sql.write(() -> jdbc.update(
                "UPDATE sm_data.designer_layout SET layout = CAST(:layout AS jsonb), updated_by = :by,"
                        + " updated_at = :at WHERE tenant_id = :t AND instance_id = :i"
                        + " AND layout IS DISTINCT FROM CAST(:layout AS jsonb)", params));
        if (changed == 0 && !"{}".equals(layoutJson)) {
            Sql.write(() -> jdbc.update(
                    "INSERT INTO sm_data.designer_layout (tenant_id, instance_id, layout, updated_by, updated_at)"
                            + " VALUES (:t, :i, CAST(:layout AS jsonb), :by, :at)"
                            + " ON CONFLICT (tenant_id, instance_id) DO NOTHING", params));
        }
    }

    @Override
    public long insert(GenerationRecord row) {
        return Sql.writeReturning(() -> jdbc.queryForObject(
                "INSERT INTO sm_data.config_generation (tenant_id, instance_id, generation, package_solution,"
                        + " package_version, component_selection, draft_values, rendered_entries, content_digest,"
                        + " origin, derived_from_generation, created_by, created_at)"
                        + " VALUES (:t, :i, :g, :solution, :version, CAST(:selection AS jsonb),"
                        + " CAST(:draft AS jsonb), CAST(:rendered AS jsonb), :digest, :origin, :derived,"
                        + " :createdBy, :createdAt) RETURNING state_version",
                new MapSqlParameterSource()
                        .addValue("t", row.tenant().value()).addValue("i", row.instance().value())
                        .addValue("g", row.generation()).addValue("solution", row.packageSolution())
                        .addValue("version", row.packageVersion())
                        .addValue("selection", Sql.redacted(row.componentSelectionJson()))
                        .addValue("draft", Sql.redacted(row.draftValuesJson()))
                        .addValue("rendered", Sql.redacted(row.renderedEntriesJson()))
                        .addValue("digest", row.contentDigest()).addValue("origin", row.origin().wire())
                        .addValue("derived", row.derivedFromGeneration(), Types.BIGINT)
                        .addValue("createdBy", row.createdBy()).addValue("createdAt", Sql.at(row.createdAt())),
                Long.class));
    }

    @Override
    public long advanceHead(TenantId tenant, InstanceId instance, long lastGeneration) {
        return Sql.writeReturning(() -> jdbc.queryForObject(
                "UPDATE sm_data.instance_state SET last_generation = :g WHERE tenant_id = :t AND instance_id = :i"
                        + " RETURNING state_version",
                key(tenant, instance).addValue("g", lastGeneration), Long.class));
    }

    @Override
    public int pruneBeyondNewest(TenantId tenant, InstanceId instance, int keep) {
        return Sql.write(() -> jdbc.update(
                "DELETE FROM sm_data.config_generation g"
                        + " WHERE g.tenant_id = :t AND g.instance_id = :i"
                        + " AND g.generation < (SELECT min(n.generation) FROM ("
                        + "     SELECT generation FROM sm_data.config_generation"
                        + "     WHERE tenant_id = :t AND instance_id = :i ORDER BY generation DESC LIMIT :keep) n)"
                        + " AND g.generation <> (SELECT s.last_generation FROM sm_data.instance_state s"
                        + "     WHERE s.tenant_id = :t AND s.instance_id = :i)"
                        + " AND NOT EXISTS (SELECT 1 FROM sm_data.apply_request r WHERE r.tenant_id = g.tenant_id"
                        + "     AND r.instance_id = g.instance_id AND r.generation = g.generation)",
                key(tenant, instance).addValue("keep", keep)));
    }

    @Override
    public Optional<String> renderedEntries(TenantId tenant, InstanceId instance, long generation) {
        return jdbc.query(
                "SELECT g.rendered_entries::text AS rendered_entries FROM sm_data.config_generation g"
                        + " WHERE g.tenant_id = :t AND g.instance_id = :i AND g.generation = :g",
                key(tenant, instance).addValue("g", generation), (rs, n) -> rs.getString("rendered_entries"))
                .stream().findFirst();
    }

    @Override
    public boolean namedByAnApplyRequest(TenantId tenant, InstanceId instance, long generation) {
        Boolean named = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sm_data.apply_request r WHERE r.tenant_id = :t AND r.instance_id = :i"
                        + " AND r.generation = :g)",
                key(tenant, instance).addValue("g", generation), Boolean.class);
        return Boolean.TRUE.equals(named);
    }

    @Override
    public Optional<GenerationRecord> find(TenantId tenant, InstanceId instance, long generation) {
        return jdbc.query(
                "SELECT " + FULL_COLUMNS + " FROM sm_data.config_generation g"
                        + " WHERE g.tenant_id = :t AND g.instance_id = :i AND g.generation = :g",
                key(tenant, instance).addValue("g", generation), FULL).stream().findFirst();
    }

    @Override
    public Optional<GenerationView> view(TenantId tenant, InstanceId instance, long generation) {
        return jdbc.query(
                "SELECT " + VIEW_COLUMNS + " FROM sm_data.config_generation g"
                        + " WHERE g.tenant_id = :t AND g.instance_id = :i AND g.generation = :g",
                key(tenant, instance).addValue("g", generation), VIEW).stream().findFirst();
    }

    @Override
    public List<GenerationView> history(TenantId tenant, InstanceId instance, int limit) {
        return jdbc.query(
                "SELECT " + VIEW_COLUMNS + " FROM sm_data.config_generation g"
                        + " WHERE g.tenant_id = :t AND g.instance_id = :i ORDER BY g.generation DESC LIMIT :limit",
                key(tenant, instance).addValue("limit", limit), VIEW);
    }

    private static MapSqlParameterSource key(TenantId tenant, InstanceId instance) {
        return new MapSqlParameterSource().addValue("t", tenant.value()).addValue("i", instance.value());
    }

    private static GenerationView view(ResultSet rs, int n) throws SQLException {
        return new GenerationView(rs.getLong("generation"), rs.getString("package_solution"),
                rs.getString("package_version"), rs.getString("content_digest"),
                GenerationOrigin.fromWire(rs.getString("origin")), Sql.nullableLong(rs, "derived_from_generation"),
                rs.getObject("created_by", java.util.UUID.class), Sql.instant(rs, "created_at"),
                rs.getLong("state_version"));
    }

    private static GenerationRecord full(ResultSet rs, int n) throws SQLException {
        return new GenerationRecord(new TenantId(rs.getObject("tenant_id", java.util.UUID.class)),
                new InstanceId(rs.getObject("instance_id", java.util.UUID.class)), rs.getLong("generation"),
                rs.getString("package_solution"), rs.getString("package_version"),
                rs.getString("component_selection"), rs.getString("draft_values"),
                rs.getString("rendered_entries"), rs.getString("content_digest"),
                GenerationOrigin.fromWire(rs.getString("origin")), Sql.nullableLong(rs, "derived_from_generation"),
                rs.getObject("created_by", java.util.UUID.class), Sql.instant(rs, "created_at"),
                rs.getLong("state_version"));
    }
}
