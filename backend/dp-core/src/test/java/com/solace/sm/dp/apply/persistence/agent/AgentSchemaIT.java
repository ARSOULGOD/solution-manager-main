package com.solace.sm.dp.apply.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.solace.sm.dp.apply.persistence.AgentItSupport;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 015 T1.1, {@code sm_data} V028 (the agent gateway's state, over OUR {@code placement} and {@code target_connection})
 * and V029 (command retention), against a real PostgreSQL 14. Every refusal is asserted by SQLSTATE and constraint name.
 *
 * <p>The package-private harness of {@code ...apply.persistence} is reached through {@link AgentItSupport}.
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentSchemaIT {

    private static final String RUNTIME_DENIED = "42501";

    private static NamedParameterJdbcTemplate owner;
    private static NamedParameterJdbcTemplate runtime;
    private static TransactionTemplate tx;
    private static long generation = 0;

    @BeforeAll
    static void wire() {
        AgentItSupport.bootstrap();
        // A database of its own (fix round, finding 12): the REQUESTED apply rows and agent targets these tests leave behind
        // must not reach another IT's counts, and the retention test's exact count must not see another IT's old commands.
        AgentItSupport.AgentDb db = AgentItSupport.freshDatabase("agent_schema");
        owner = db.owner();
        runtime = db.runtime();
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.ownerDataSource()));
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private static String thumbprint() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
    }

    private static MapSqlParameterSource p(Object... kv) {
        MapSqlParameterSource m = new MapSqlParameterSource();
        for (int i = 0; i < kv.length; i += 2) {
            m.addValue((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** An enrolled agent: its target row and its identity, in ONE transaction (the reference is deferred). */
    private record Agent(UUID tenant, UUID agent, UUID target) {
    }

    private static void insertAgentTarget(UUID tenant, UUID target, UUID agent) {
        owner.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, agent_id,"
                        + " allowed_config_root, allowed_container_prefix, created_by, created_at)"
                        + " VALUES (:t, :x, 'agent', :a, '/srv/sm', 'sm-', :u, :now)",
                p("t", tenant, "x", target, "a", agent, "u", UUID.randomUUID(), "now", OffsetDateTime.now()));
    }

    private static void insertIdentity(UUID tenant, UUID agent, UUID target, String thumbprint) {
        owner.update("INSERT INTO sm_data.agent_identity (tenant_id, agent_id, environment_id, catalog_solution_id,"
                        + " solution, declared_package_version, target_id, certificate_thumbprint, agent_epoch,"
                        + " fleet_epoch, created_at) VALUES (:t, :a, :e, :c, 'cache', '2.4', :x, :th, 1, 1, :now)",
                p("t", tenant, "a", agent, "e", UUID.randomUUID(), "c", UUID.randomUUID(), "x", target,
                        "th", thumbprint, "now", OffsetDateTime.now()));
    }

    private static Agent enrol(UUID tenant) {
        UUID agent = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            insertAgentTarget(tenant, target, agent);
            insertIdentity(tenant, agent, target, thumbprint());
        });
        return new Agent(tenant, agent, target);
    }

    /** A placement on the agent's own target, with an apply request and one APPLY-capable intent for it. */
    private record Work(Agent agent, UUID instance, UUID placement, UUID apply) {
    }

    private static Work work(Agent a) {
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        AgentItSupport.declareInstance(owner, a.tenant(), instance);
        AgentItSupport.placement(owner, a.tenant(), instance, a.target(), placement);
        UUID apply = AgentItSupport.request(owner, a.tenant(), instance, placement);
        return new Work(a, instance, placement, apply);
    }

    private static UUID intent(Work w, String step, String key, String hash) {
        return insertIntent(owner, w.agent().tenant(), w.apply(), w.placement(), step, key, hash);
    }

    private static UUID insertIntent(NamedParameterJdbcTemplate jdbc, UUID tenant, UUID apply, UUID placement,
            String step, String key, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO sm_data.apply_step_intent (intent_id, tenant_id, apply_id, placement_id, replica_ref,"
                        + " step, idempotency_key, config_hash, created_at)"
                        + " VALUES (:id, :t, :a, :p, :r, :s, :k, :h, :now)",
                p("id", id, "t", tenant, "a", apply, "p", placement,
                        "r", step.equals("PREFLIGHT") ? "@placement" : "r0", "s", step, "k", key, "h", hash,
                        "now", OffsetDateTime.now()));
        return id;
    }

    private static final String HMAC = "hmacsha256v1:" + "a".repeat(64);

    private static UUID command(Work w, UUID intentId, String intentKey, String operation, String state,
            String hash, OffsetDateTime acked) {
        return command(w, intentId, intentKey, operation, state, hash, acked, acked == null ? OffsetDateTime.now() : acked);
    }

    /** As above, with {@code created_at} named apart from {@code acked_at} (a QUEUED command can be old). */
    private static UUID command(Work w, UUID intentId, String intentKey, String operation, String state,
            String hash, OffsetDateTime acked, OffsetDateTime created) {
        UUID id = UUID.randomUUID();
        String outcome = state.equals("ACKED") || state.equals("REFUSED") ? "APPLIED" : null;
        long gen;
        synchronized (AgentSchemaIT.class) {
            gen = ++generation;
        }
        owner.update("INSERT INTO sm_data.agent_command (tenant_id, command_id, agent_id, placement_id,"
                        + " apply_id, intent_id, intent_key, operation, wire_generation, config_hash, state, outcome,"
                        + " deadline, created_at, acked_at)"
                        + " VALUES (:t, :c, :a, :p, :ap, :i, :k, :op, :g, :h, :s, :o, :dl, :cr, :ak)",
                p("t", w.agent().tenant(), "c", id, "a", w.agent().agent(), "p", w.placement(), "ap", w.apply(),
                        "i", intentId, "k", intentKey, "op", operation, "g", gen, "h", hash, "s", state, "o", outcome,
                        "dl", OffsetDateTime.now().plusMinutes(5),
                        "cr", created, "ak", acked));
        return id;
    }

    private static String key(Work w, String tail) {
        return w.apply() + ":" + tail;
    }

    private static int count(String sql, MapSqlParameterSource args) {
        return owner.queryForObject(sql, args, Integer.class);
    }

    // ---- the plan's Step 1 list ---------------------------------------------------------------------------------

    /** The exact column set of the eight agent tables (finding 6): a column added to one is a deliberate change to this list. */
    private static final Map<String, String> AGENT_COLUMNS = Map.of(
            "agent_identity", "tenant_id agent_id environment_id catalog_solution_id solution declared_package_version target_id"
                    + " certificate_thumbprint agent_epoch fleet_epoch revoked_at capabilities created_at",
            "agent_session", "tenant_id agent_id fencing_epoch protocol_version agent_version opened_at last_seen_at closed_at"
                    + " features clock_offset_ms",
            "agent_lease", "tenant_id agent_id lease_id certificate_thumbprint agent_epoch fleet_epoch issued_at expires_at audience"
                    + " renewal_nonce_digest capabilities placement_ids verb_ceilings admission_margin_ms server_clock state",
            "agent_command", "tenant_id command_id agent_id placement_id apply_id intent_id intent_key attempt_id operation"
                    + " wire_generation config_hash state outcome detail_code offered_epoch received_request_id ack_request_id"
                    + " deadline created_at notified_at received_at acked_at",
            "agent_report_watermark", "tenant_id agent_id placement_id last_sequence last_item_id updated_at",
            "agent_telemetry_watermark", "tenant_id agent_id stream_id last_batch_seq updated_at",
            "agent_discovery", "tenant_id agent_id instance_label container_name container_id state instance_id placement_id"
                    + " refusal_code first_seen_at settled_at",
            "agent_event", "tenant_id event_id agent_id request_id code actor_kind actor_id correlation_id client_address"
                    + " detail_code occurred_at");

    /**
     * Every column of the agent tables that is a hash, a digest, a thumbprint or a nonce, and what it is. None of them is
     * a hash of rendered configuration: {@code config_hash} is the KEYED hmacsha256v spelling (CHECK), the other three are
     * a lease-nonce digest and two certificate thumbprints (public identifiers, not config hashes).
     */
    private static final Set<String> HASH_LIKE_COLUMNS = Set.of("agent_command.config_hash",
            "agent_lease.renewal_nonce_digest", "agent_lease.certificate_thumbprint", "agent_identity.certificate_thumbprint");

    @Test
    void noAgentTableCanHoldRenderedBytesOrAPlainDigest() {
        List<Map<String, Object>> columns = owner.queryForList("SELECT table_name, column_name, data_type"
                + " FROM information_schema.columns WHERE table_schema = 'sm_data' AND table_name LIKE 'agent\\_%'", Map.of());
        assertFalse(columns.isEmpty(), "precondition: the agent tables exist");
        Map<String, Set<String>> actual = new LinkedHashMap<>();
        Set<String> hashLike = new TreeSet<>();
        Set<String> jsonish = new TreeSet<>();
        for (Map<String, Object> c : columns) {
            String table = (String) c.get("table_name");
            String name = (String) c.get("column_name");
            String type = (String) c.get("data_type");
            actual.computeIfAbsent(table, k -> new TreeSet<>()).add(name);
            assertFalse("bytea".equals(type), c + " is bytea");
            assertFalse(name.matches(".*(payload|rendered|sha256|content|body|blob).*"), c + " is named for rendered bytes");
            if (name.matches(".*(hash|digest|thumbprint|nonce|checksum|hmac|fingerprint|sha).*")) {
                hashLike.add(table + "." + name);
            }
            if (type.equals("jsonb") || type.equals("json")) {
                jsonish.add(table + "." + name);
            }
        }
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        AGENT_COLUMNS.forEach((t, cols) -> expected.put(t, new TreeSet<>(List.of(cols.split(" ")))));
        assertEquals(new TreeMap<>(expected), new TreeMap<>(actual), "the exact column set of the eight agent tables");
        assertEquals(new TreeSet<>(HASH_LIKE_COLUMNS), hashLike, "a new hash-like column needs a named reason (I6, OR-32)");
        assertEquals(Set.of("agent_lease.verb_ceilings"), jsonish, "the only free-shape column, and it is shape-checked");
    }

    @Test
    void verbCeilingsIsAnObjectOfBoundedSize() {
        Agent a = enrol(UUID.randomUUID());
        String insert = "INSERT INTO sm_data.agent_lease (tenant_id, agent_id, lease_id, certificate_thumbprint, agent_epoch,"
                + " fleet_epoch, issued_at, expires_at, audience, renewal_nonce_digest, verb_ceilings, admission_margin_ms,"
                + " server_clock, state) VALUES (:t, :a, :l, :th, 1, 1, :iss, :exp, 'gw', :nd, CAST(:v AS jsonb), 0, :iss, 'EXPIRED')";
        OffsetDateTime now = OffsetDateTime.now();
        for (String bad : List.of("[]", "\"rendered config bytes\"", "1", "null")) {
            AgentItSupport.assertRefused("23514", "agent_lease_verb_ceilings", () -> owner.update(insert,
                    p("t", a.tenant(), "a", a.agent(), "l", UUID.randomUUID(), "th", thumbprint(), "iss", now.minusHours(1),
                            "exp", now, "nd", thumbprint(), "v", bad)));
        }
        StringBuilder big = new StringBuilder("{\"k\":\"");
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 6000; i++) {
            big.append((char) ('a' + random.nextInt(26)));
        }
        String oversize = big.append("\"}").toString();
        AgentItSupport.assertRefused("23514", "agent_lease_verb_ceilings", () -> owner.update(insert,
                p("t", a.tenant(), "a", a.agent(), "l", UUID.randomUUID(), "th", thumbprint(), "iss", now.minusHours(1),
                        "exp", now, "nd", thumbprint(), "v", oversize)));
        assertEquals(1, owner.update(insert, p("t", a.tenant(), "a", a.agent(), "l", UUID.randomUUID(), "th", thumbprint(),
                "iss", now.minusHours(1), "exp", now, "nd", thumbprint(), "v", "{\"APPLY\":{\"maxPerMinute\":4}}")),
                "the positive control: a small object");
    }

    @Test
    void anSshTargetRowStillNeedsEverySshField() {
        UUID tenant = UUID.randomUUID();
        AgentItSupport.assertRefused("23514", "target_connection_transport_shape", () -> owner.update(
                "INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :x, 'ssh-docker', NULL, 22, 'sm', :fp, :c, '/srv/sm', 'sm-', :u, :now)",
                p("t", tenant, "x", UUID.randomUUID(), "fp", "SHA256:" + "A".repeat(43), "c", UUID.randomUUID(),
                        "u", UUID.randomUUID(), "now", OffsetDateTime.now())));
    }

    @Test
    void anAgentTargetRowRefusesSshFields() {
        UUID tenant = UUID.randomUUID();
        AgentItSupport.assertRefused("23514", "target_connection_transport_shape", () -> owner.update(
                "INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, agent_id, host,"
                        + " allowed_config_root, allowed_container_prefix, created_by, created_at)"
                        + " VALUES (:t, :x, 'agent', :a, 'x.example', '/srv/sm', 'sm-', :u, :now)",
                p("t", tenant, "x", UUID.randomUUID(), "a", UUID.randomUUID(), "u", UUID.randomUUID(),
                        "now", OffsetDateTime.now())));
    }

    @Test
    void anAgentTargetStillNeedsItsEnvelope() {
        UUID tenant = UUID.randomUUID();
        AgentItSupport.assertRefused("23502", null, () -> owner.update(
                "INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, agent_id,"
                        + " allowed_config_root, allowed_container_prefix, created_by, created_at)"
                        + " VALUES (:t, :x, 'agent', :a, NULL, 'sm-', :u, :now)",
                p("t", tenant, "x", UUID.randomUUID(), "a", UUID.randomUUID(), "u", UUID.randomUUID(),
                        "now", OffsetDateTime.now())));
    }

    @Test
    void anAgentPlacementOutsideTheEnvelopeIsRefusedByTheTrigger() {
        Agent a = enrol(UUID.randomUUID());
        UUID instance = UUID.randomUUID();
        AgentItSupport.declareInstance(owner, a.tenant(), instance);
        PSQLException refusal = AgentItSupport.assertRefused("23514", null, () -> owner.update(
                "INSERT INTO sm_data.placement (tenant_id, placement_id, instance_id, target_id, container_name,"
                        + " remote_config_directory, container_config_path, file_name, mechanism, retained_generations,"
                        + " created_at) VALUES (:t, :p, :i, :x, 'sm-x', '/etc/elsewhere/x', '/app/external/spring/config',"
                        + " 'application.yml', 'ADDITIONAL_LOCATION', 10, :now)",
                p("t", a.tenant(), "p", UUID.randomUUID(), "i", instance, "x", a.target(), "now", OffsetDateTime.now())));
        assertEquals("remote_config_directory", refusal.getServerErrorMessage().getColumn(), "refused for the envelope, not another reason");
    }

    private static final String EVENT_INSERT = "INSERT INTO sm_data.target_connection_event (tenant_id, target_id, event_id,"
            + " kind, actor, correlation_id, client_address, public_key_fingerprint, recorded_at, actor_kind)"
            + " VALUES (:t, :x, :e, :k, :actor, :c, '198.51.100.7', :fp, :now, :ak)";

    private static int event(Agent a, String kind, String actorKind, String fingerprint) {
        return owner.update(EVENT_INSERT, p("t", a.tenant(), "x", a.target(), "e", UUID.randomUUID(), "k", kind,
                "actor", a.agent(), "c", UUID.randomUUID(), "fp", fingerprint, "now", OffsetDateTime.now(), "ak", actorKind));
    }

    @Test
    void anEnrolledEventCarriesAnAgentActorAndNoFingerprint() {
        Agent a = enrol(UUID.randomUUID());
        String fp = "SHA256:" + "F".repeat(43);
        AgentItSupport.assertRefused("23514", "target_connection_event_agent_iff_enrolled",
                () -> event(a, "enrolled", "principal", null));
        // the iff's other direction (finding 3): an agent actor on a kind that is not 'enrolled'
        AgentItSupport.assertRefused("23514", "target_connection_event_agent_iff_enrolled",
                () -> event(a, "registered", "agent", fp));
        AgentItSupport.assertRefused("23514", "target_connection_event_agent_iff_enrolled",
                () -> event(a, "retired", "agent", null));
        // V022's fp_iff_key_set still holds for the new kind: 'enrolled' carries no fingerprint
        AgentItSupport.assertRefused("23514", "target_connection_event_fp_iff_key_set",
                () -> event(a, "enrolled", "agent", fp));
        assertEquals(1, event(a, "enrolled", "agent", null), "the positive control: enrolled by an agent, no fingerprint");
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.target_connection_event WHERE tenant_id = :t AND target_id = :x"
                        + " AND kind = 'enrolled' AND actor_kind = 'agent' AND public_key_fingerprint IS NULL",
                p("t", a.tenant(), "x", a.target())), "…and it stored no fingerprint");
    }

    @Test
    void aPlainHashIsRefusedInAgentCommand() {
        Work w = work(enrol(UUID.randomUUID()));
        String k = key(w, "MATERIALIZE:r0");
        UUID i = intent(w, "MATERIALIZE", k, HMAC);
        AgentItSupport.assertRefused("23514", "agent_command_config_hash",
                () -> command(w, i, k, "APPLY", "QUEUED", "sha256:" + "a".repeat(64), null));
        command(w, i, k, "APPLY", "QUEUED", HMAC, null);
    }

    @Test
    void oneLiveCommandPerIntentKeyAndOperation() {
        Work w = work(enrol(UUID.randomUUID()));
        String k = key(w, "MATERIALIZE:r0");
        UUID i = intent(w, "MATERIALIZE", k, HMAC);
        command(w, i, k, "APPLY", "QUEUED", HMAC, null);

        AgentItSupport.assertRefused("23505", "agent_command_one_live_per_intent",
                () -> command(w, i, k, "APPLY", "QUEUED", HMAC, null));
        command(w, i, k, "REMATERIALIZE", "QUEUED", HMAC, null);   // beside a live APPLY: allowed (R-6)

        owner.update("UPDATE sm_data.agent_command SET state = 'ACKED', outcome = 'APPLIED', acked_at = :now"
                + " WHERE tenant_id = :t AND intent_key = :k AND operation = 'APPLY'",
                p("now", OffsetDateTime.now(), "t", w.agent().tenant(), "k", k));
        command(w, i, k, "APPLY", "QUEUED", HMAC, null);           // after the first is final: allowed
    }

    @Test
    void aCommandMustAddressAPlacementOnTheAgentsOwnTarget() {
        Work w = work(enrol(UUID.randomUUID()));
        Agent other = enrol(w.agent().tenant());
        String k = key(w, "MATERIALIZE:r0");
        UUID i = intent(w, "MATERIALIZE", k, HMAC);
        PSQLException refusal = AgentItSupport.assertRefused("23514", null, () -> owner.update(
                "INSERT INTO sm_data.agent_command (tenant_id, command_id, agent_id, placement_id, apply_id, intent_id,"
                        + " intent_key, operation, wire_generation, config_hash, state, deadline, created_at)"
                        + " VALUES (:t, :c, :a, :p, :ap, :i, :k, 'APPLY', 999999, :h, 'QUEUED', :dl, :now)",
                p("t", w.agent().tenant(), "c", UUID.randomUUID(), "a", other.agent(), "p", w.placement(),
                        "ap", w.apply(), "i", i, "k", k, "h", HMAC, "dl", OffsetDateTime.now().plusMinutes(1),
                        "now", OffsetDateTime.now())));
        assertEquals("placement_id", refusal.getServerErrorMessage().getColumn(), "refused by the own-target trigger");
    }

    @Test
    void aLegacySshRowWithANullHostKeyAlgorithmSurvivesVda() {
        AgentItSupport.bootstrap();
        String url = AgentItSupport.freshUnmigratedDatabase("v028_legacy");
        DataSource migrator = AgentItSupport.dataSource(url, "sm_dp_migrator", AgentItSupport.MIGRATOR_PASSWORD);
        NamedParameterJdbcTemplate su = new NamedParameterJdbcTemplate(AgentItSupport.superuserOf(url));
        flyway(migrator, "27").migrate();
        UUID tenant = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        String fp = "SHA256:" + "L".repeat(43);
        su.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, host, port, ssh_user,"
                        + " host_key_fingerprint, credential_ref, allowed_config_root, allowed_container_prefix,"
                        + " created_by, created_at) VALUES (:t, :x, 'ssh-docker', 'vm.corp.example', 22, 'sm', :fp, :c,"
                        + " '/srv/sm', 'sm-', :u, :now)",
                p("t", tenant, "x", target, "fp", fp, "c", UUID.randomUUID(), "u", UUID.randomUUID(),
                        "now", OffsetDateTime.now()));
        assertNull(su.queryForObject("SELECT host_key_algorithm FROM sm_data.target_connection WHERE target_id = :x",
                p("x", target), String.class), "precondition: a pre-V027-style row, algorithm unknown");
        // the rest of a populated pre-V028 install (finding 7): the journal V028 indexes and the change audit it widens
        UUID instance = UUID.randomUUID();
        UUID placement = UUID.randomUUID();
        AgentItSupport.declareInstance(su, tenant, instance);
        AgentItSupport.placement(su, tenant, instance, target, placement);
        UUID apply = AgentItSupport.request(su, tenant, instance, placement);
        UUID intent = insertIntent(su, tenant, apply, placement, "MATERIALIZE", apply + ":MATERIALIZE:r0", HMAC);
        UUID event = UUID.randomUUID();
        su.update("INSERT INTO sm_data.target_connection_event (tenant_id, target_id, event_id, kind, actor, correlation_id,"
                        + " client_address, public_key_fingerprint, recorded_at) VALUES (:t, :x, :e, 'registered', :u, :c,"
                        + " '198.51.100.7', :fp, :now)",
                p("t", tenant, "x", target, "e", event, "u", UUID.randomUUID(), "c", UUID.randomUUID(),
                        "fp", "SHA256:" + "K".repeat(43), "now", OffsetDateTime.now()));

        flyway(migrator, null).migrate();

        MapSqlParameterSource key = p("t", tenant, "x", target);
        assertNull(su.queryForObject("SELECT host_key_algorithm FROM sm_data.target_connection"
                + " WHERE tenant_id = :t AND target_id = :x", key, String.class), "the row is intact, algorithm still NULL");
        assertEquals("ssh-docker", su.queryForObject("SELECT transport FROM sm_data.target_connection"
                + " WHERE tenant_id = :t AND target_id = :x", key, String.class));
        assertEquals(1, su.queryForObject("SELECT count(*)::int FROM sm_data.target_host_key"
                + " WHERE tenant_id = :t AND target_id = :x", key, Integer.class), "V012's recorded pin is untouched");
        assertEquals(1, su.update("UPDATE sm_data.target_connection SET host_key_algorithm = NULL"
                + " WHERE tenant_id = :t AND target_id = :x", key), "V027's fallback stays legal after VDA");
        assertEquals("principal", su.queryForObject("SELECT actor_kind FROM sm_data.target_connection_event"
                + " WHERE tenant_id = :t AND event_id = :e", p("t", tenant, "e", event), String.class),
                "an existing audit row takes the DEFAULT, and the new iff CHECK holds for it");
        assertEquals("via-ssh", su.queryForObject("SELECT acquisition_mode FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", p("t", tenant, "i", instance), String.class));
        assertEquals(1, su.queryForObject("SELECT count(*)::int FROM sm_data.apply_step_intent WHERE intent_id = :i",
                p("i", intent), Integer.class), "the journal row survives the new 5-column unique index");
        assertEquals(1, su.queryForObject("SELECT count(*)::int FROM sm_data.apply_request WHERE apply_id = :a",
                p("a", apply), Integer.class));
    }

    @Test
    void anAgentRowWithAHostKeyAlgorithmIsRefused() {
        UUID tenant = UUID.randomUUID();
        AgentItSupport.assertRefused("23514", "target_connection_transport_shape", () -> owner.update(
                "INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, agent_id, host_key_algorithm,"
                        + " allowed_config_root, allowed_container_prefix, created_by, created_at)"
                        + " VALUES (:t, :x, 'agent', :a, 'ssh-ed25519', '/srv/sm', 'sm-', :u, :now)",
                p("t", tenant, "x", UUID.randomUUID(), "a", UUID.randomUUID(), "u", UUID.randomUUID(),
                        "now", OffsetDateTime.now())));
    }

    /** A valid target_connection row of one transport, as column to value; a test overrides one column. */
    private static Map<String, Object> targetRow(String transport) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", UUID.randomUUID());
        row.put("target_id", UUID.randomUUID());
        row.put("transport", transport);
        if (transport.equals("agent")) {
            row.put("agent_id", UUID.randomUUID());
        } else {
            row.put("host", "vm.corp.example");
            row.put("port", 22);
            row.put("ssh_user", "sm");
            row.put("host_key_fingerprint", "SHA256:" + "A".repeat(43));
            row.put("credential_ref", UUID.randomUUID());
        }
        row.put("allowed_config_root", "/srv/sm");
        row.put("allowed_container_prefix", "sm-");
        row.put("created_by", UUID.randomUUID());
        row.put("created_at", OffsetDateTime.now());
        return row;
    }

    private static void insertRow(Map<String, Object> row) {
        owner.update("INSERT INTO sm_data.target_connection (" + String.join(", ", row.keySet()) + ") VALUES (:"
                + String.join(", :", row.keySet()) + ")", new MapSqlParameterSource(row));
    }

    private static void refusedByShape(String because, Map<String, Object> row) {
        AgentItSupport.assertRefused("23514", "target_connection_transport_shape", () -> insertRow(row), because);
    }

    /** Finding 3 / lesson L20: the shape CHECK is a disjunction of conjunctions, so EVERY conjunct is pinned on its own. */
    @Test
    void everyLegOfTheTransportShapeIsRefusedOnItsOwn() {
        insertRow(targetRow("ssh-docker"));                    // the positive control: the ssh leg, whole
        for (String column : List.of("host", "port", "ssh_user", "host_key_fingerprint", "credential_ref")) {
            Map<String, Object> row = targetRow("ssh-docker");
            row.put(column, null);
            refusedByShape("ssh-docker with " + column + " NULL", row);
        }
        Map<String, Object> sshWithAgent = targetRow("ssh-docker");
        sshWithAgent.put("agent_id", UUID.randomUUID());
        refusedByShape("ssh-docker with an agent_id", sshWithAgent);

        Map<String, Object> agentWithoutId = targetRow("agent");
        agentWithoutId.put("agent_id", null);
        refusedByShape("agent with agent_id NULL", agentWithoutId);
        Map<String, Object> forbidden = new LinkedHashMap<>();
        forbidden.put("host", "x.example");
        forbidden.put("port", 22);
        forbidden.put("ssh_user", "sm");
        forbidden.put("host_key_fingerprint", "SHA256:" + "A".repeat(43));
        forbidden.put("host_key_algorithm", "ssh-ed25519");
        forbidden.put("credential_ref", UUID.randomUUID());
        forbidden.put("credential_public_fingerprint", "SHA256:" + "C".repeat(43));
        forbidden.forEach((column, value) -> {
            Map<String, Object> row = targetRow("agent");
            row.put(column, value);
            refusedByShape("agent with " + column + " set", row);
        });
    }

    @Test
    void anAgentIdentityMustNameItsOwnAgentTarget() {
        UUID tenant = UUID.randomUUID();
        // (1) an SSH target: agent_id NULL on that row, so no (tenant, target, agent) row exists to reference
        Map<String, Object> ssh = targetRow("ssh-docker");
        ssh.put("tenant_id", tenant);
        insertRow(ssh);
        AgentItSupport.assertRefused("23503", "agent_identity_has_target",
                () -> insertIdentity(tenant, UUID.randomUUID(), (UUID) ssh.get("target_id"), thumbprint()),
                "an identity naming an SSH target");
        // (2) another agent's target: the row exists but carries a different agent_id
        Agent other = enrol(tenant);
        AgentItSupport.assertRefused("23503", "agent_identity_has_target",
                () -> insertIdentity(tenant, UUID.randomUUID(), other.target(), thumbprint()),
                "a second identity naming an agent target that is another agent's");
        // (3) the legal pair is untouched
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.agent_identity WHERE tenant_id = :t AND agent_id = :a",
                p("t", other.tenant(), "a", other.agent())));
    }

    @Test
    void theConfigHashSpellingIsTheKeyedOnesExactly() {
        Work w = work(enrol(UUID.randomUUID()));
        String k = key(w, "MATERIALIZE:r0");
        UUID i = intent(w, "MATERIALIZE", k, HMAC);
        // HmacConfigHasher.SPELLING is ^hmacsha256v([1-9][0-9]{0,8}):([0-9a-f]{64})$
        for (String bad : List.of("hmacsha256v0:", "hmacsha256v01:", "hmacsha256v1234567890:", "hmacsha256v:")) {
            AgentItSupport.assertRefused("23514", "agent_command_config_hash",
                    () -> command(w, i, k, "APPLY", "QUEUED", bad + "a".repeat(64), null), bad);
        }
        command(w, i, k, "APPLY", "ACKED", "hmacsha256v123456789:" + "a".repeat(64), OffsetDateTime.now());
    }

    @Test
    void anAgentTargetWritesNoTargetHostKeyRow() {
        Agent a = enrol(UUID.randomUUID());
        assertEquals(0, count("SELECT count(*)::int FROM sm_data.target_host_key WHERE tenant_id = :t AND target_id = :x",
                p("t", a.tenant(), "x", a.target())), "G-1: V012's trigger copies a pin only when there is one");
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.target_connection WHERE tenant_id = :t AND target_id = :x",
                p("t", a.tenant(), "x", a.target())));
    }

    @Test
    void agentIdAndThumbprintAreGloballyUnique() {
        Agent a = enrol(UUID.randomUUID());
        UUID otherTenant = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        AgentItSupport.assertRefused("23505", "agent_identity_agent_id_global", () -> tx.executeWithoutResult(s -> {
            owner.update("INSERT INTO sm_data.target_connection (tenant_id, target_id, transport, agent_id,"
                            + " allowed_config_root, allowed_container_prefix, created_by, created_at)"
                            + " VALUES (:t, :x, 'agent', :a, '/srv/sm', 'sm-', :u, :now)",
                    p("t", otherTenant, "x", target, "a", UUID.randomUUID(), "u", UUID.randomUUID(), "now", OffsetDateTime.now()));
            insertIdentity(otherTenant, a.agent(), target, thumbprint());
        }));
        String existing = owner.queryForObject("SELECT certificate_thumbprint FROM sm_data.agent_identity WHERE agent_id = :a",
                p("a", a.agent()), String.class);
        UUID agent2 = UUID.randomUUID();
        UUID target2 = UUID.randomUUID();
        AgentItSupport.assertRefused("23505", "agent_identity_thumbprint_global", () -> tx.executeWithoutResult(s -> {
            insertAgentTarget(otherTenant, target2, agent2);
            insertIdentity(otherTenant, agent2, target2, existing);
        }));
    }

    @Test
    void agentEventIsAppendOnlyForTheRuntimeRole() {
        UUID tenant = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        assertEquals(1, runtime.update("INSERT INTO sm_data.agent_event (tenant_id, event_id, request_id, code, actor_kind,"
                        + " correlation_id, client_address, occurred_at) VALUES (:t, :e, 'r1', 'gateway.refusal', 'SYSTEM',"
                        + " :c, '198.51.100.7', :now)",
                p("t", tenant, "e", event, "c", UUID.randomUUID(), "now", OffsetDateTime.now())));
        AgentItSupport.assertRefused(RUNTIME_DENIED, null, () -> runtime.update(
                "UPDATE sm_data.agent_event SET detail_code = 'x' WHERE tenant_id = :t", p("t", tenant)));
        AgentItSupport.assertRefused(RUNTIME_DENIED, null, () -> runtime.update(
                "DELETE FROM sm_data.agent_event WHERE tenant_id = :t", p("t", tenant)));
    }

    @Test
    void everyAgentTableLeadsWithTenantId() {
        List<Map<String, Object>> rows = owner.queryForList(
                "SELECT c.relname, a.attname FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = i.indkey[0]"
                        + " WHERE n.nspname = 'sm_data' AND c.relname LIKE 'agent\\_%' AND i.indisprimary", Map.of());
        assertEquals(8, rows.size(), "eight agent tables, each with a primary key: " + rows);
        for (Map<String, Object> r : rows) {
            assertEquals("tenant_id", r.get("attname"), r.get("relname") + " must lead with tenant_id (I5)");
        }
    }

    @Test
    void oneLiveAgentTargetPerAgentPerTenant() {
        Agent a = enrol(UUID.randomUUID());
        AgentItSupport.assertRefused("23505", "target_connection_agent_once", () -> insertAgentTarget(
                a.tenant(), UUID.randomUUID(), a.agent()));
    }

    // ---- finding 4/5: the triggers, the discovery CHECKs, the runtime role's column grants -------------------------------

    private static Map<String, Object> discoveryRow(Agent a, String state) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", a.tenant());
        row.put("agent_id", a.agent());
        row.put("instance_label", "cache-" + UUID.randomUUID().toString().substring(0, 8));
        row.put("container_name", "sm-" + UUID.randomUUID().toString().substring(0, 8));
        row.put("container_id", "a".repeat(12));
        row.put("state", state);
        row.put("first_seen_at", OffsetDateTime.now());
        if (!state.equals("PENDING")) {
            row.put("settled_at", OffsetDateTime.now());
        }
        if (state.equals("REFUSED")) {
            row.put("refusal_code", "not-allowed");
        }
        return row;
    }

    private static void insertDiscovery(Map<String, Object> row) {
        owner.update("INSERT INTO sm_data.agent_discovery (" + String.join(", ", row.keySet()) + ") VALUES (:"
                + String.join(", :", row.keySet()) + ")", new MapSqlParameterSource(row));
    }

    private static void discoveryRefused(String constraint, String because, Map<String, Object> row) {
        AgentItSupport.assertRefused("23514", constraint, () -> insertDiscovery(row), because);
    }

    @Test
    void everyAgentDiscoveryCheckIsPinnedOnItsOwnLeg() {
        Agent a = enrol(UUID.randomUUID());
        insertDiscovery(discoveryRow(a, "PENDING"));           // the positive controls
        insertDiscovery(discoveryRow(a, "ABANDONED"));
        insertDiscovery(discoveryRow(a, "REFUSED"));

        Map<String, Object> r = discoveryRow(a, "PENDING");
        r.put("instance_label", "");
        discoveryRefused("agent_discovery_label", "empty label", r);
        r = discoveryRow(a, "PENDING");
        r.put("instance_label", "l".repeat(129));
        discoveryRefused("agent_discovery_label", "label over 128", r);
        r = discoveryRow(a, "PENDING");
        r.put("container_name", "-leading-dash");
        discoveryRefused("agent_discovery_container_name", "container name grammar", r);
        r = discoveryRow(a, "PENDING");
        r.put("container_id", "XYZ");
        discoveryRefused("agent_discovery_container_id", "container id is 12-64 lowercase hex", r);
        r = discoveryRow(a, "ABANDONED");
        r.put("state", "BOGUS");
        discoveryRefused("agent_discovery_state", "closed state set", r);
        r = discoveryRow(a, "REFUSED");
        r.put("refusal_code", "Not Allowed");
        discoveryRefused("agent_discovery_refusal", "refusal code grammar", r);
        for (String state : List.of("REGISTERED", "UNDECLARED", "RETIRED")) {
            r = discoveryRow(a, state);
            discoveryRefused("agent_discovery_registered_has_ids", state + " without instance and placement", r);
            r.put("instance_id", UUID.randomUUID());
            discoveryRefused("agent_discovery_registered_has_ids", state + " with an instance but no placement", r);
        }
        r = discoveryRow(a, "REFUSED");
        r.remove("refusal_code");
        discoveryRefused("agent_discovery_refused_has_code", "REFUSED without a code", r);
        r = discoveryRow(a, "ABANDONED");
        r.put("refusal_code", "not-allowed");
        discoveryRefused("agent_discovery_refused_has_code", "a code on a state that is not REFUSED", r);
        r = discoveryRow(a, "PENDING");
        r.put("settled_at", OffsetDateTime.now());
        discoveryRefused("agent_discovery_settled", "PENDING with a settled_at", r);
        r = discoveryRow(a, "ABANDONED");
        r.remove("settled_at");
        discoveryRefused("agent_discovery_settled", "a settled state without a settled_at", r);
    }

    @Test
    void aTerminalDiscoveryStateIsFinalEvenForTheRuntimeRole() {
        Agent a = enrol(UUID.randomUUID());
        Work w = work(a);
        Map<String, String> moves = new LinkedHashMap<>();    // terminal state -> an UPDATE that is CHECK-valid and moves the state
        moves.put("REFUSED", "state = 'ABANDONED', refusal_code = NULL");
        moves.put("ABANDONED", "state = 'REFUSED', refusal_code = 'not-allowed'");
        moves.put("UNDECLARED", "state = 'RETIRED'");
        moves.put("RETIRED", "state = 'REGISTERED'");
        moves.forEach((terminal, set) -> {
            Map<String, Object> row = discoveryRow(a, terminal);
            if (terminal.equals("UNDECLARED") || terminal.equals("RETIRED")) {
                row.put("instance_id", w.instance());
                row.put("placement_id", w.placement());
            }
            insertDiscovery(row);
            MapSqlParameterSource key = p("t", a.tenant(), "a", a.agent(), "l", row.get("instance_label"), "c", row.get("container_name"));
            String where = " WHERE tenant_id = :t AND agent_id = :a AND instance_label = :l AND container_name = :c";
            PSQLException refusal = AgentItSupport.assertRefused("23514", null,
                    () -> runtime.update("UPDATE sm_data.agent_discovery SET " + set + where, key), terminal + " is terminal");
            assertEquals("state", refusal.getServerErrorMessage().getColumn(), terminal);
            assertEquals(terminal, owner.queryForObject("SELECT state FROM sm_data.agent_discovery" + where, key, String.class));
            assertEquals(1, runtime.update("UPDATE sm_data.agent_discovery SET container_id = '" + "b".repeat(12) + "'" + where, key),
                    terminal + ": a fact other than the state may still be recorded");
        });
        // the live moves stay legal: PENDING -> REGISTERED -> UNDECLARED
        Map<String, Object> pending = discoveryRow(a, "PENDING");
        insertDiscovery(pending);
        MapSqlParameterSource key = p("t", a.tenant(), "a", a.agent(), "l", pending.get("instance_label"),
                "c", pending.get("container_name"), "i", w.instance(), "p", w.placement(), "now", OffsetDateTime.now());
        String where = " WHERE tenant_id = :t AND agent_id = :a AND instance_label = :l AND container_name = :c";
        assertEquals(1, runtime.update("UPDATE sm_data.agent_discovery SET state = 'REGISTERED', instance_id = :i,"
                + " placement_id = :p, settled_at = :now" + where, key));
        assertEquals(1, runtime.update("UPDATE sm_data.agent_discovery SET state = 'UNDECLARED'" + where, key));
    }

    /**
     * V031 (plan 015 T4.1): the ONE move out of a terminal state is the release of a REFUSED or ABANDONED key whose container was
     * re-created, to PENDING, in the statement that records the new container id. Every other move stays refused (the test above).
     */
    @Test
    void v031ReleasesARefusedOrAbandonedKeyOnlyWhenTheContainerIsRecreated() {
        Agent a = enrol(UUID.randomUUID());
        Work w = work(a);
        String where = " WHERE tenant_id = :t AND agent_id = :a AND instance_label = :l AND container_name = :c";
        String release = "UPDATE sm_data.agent_discovery SET state = 'PENDING', refusal_code = NULL, settled_at = NULL, instance_id = :i,"
                + " placement_id = :p, container_id = :id" + where;
        for (String terminal : List.of("REFUSED", "ABANDONED")) {
            Map<String, Object> row = discoveryRow(a, terminal);
            insertDiscovery(row);
            MapSqlParameterSource key = p("t", a.tenant(), "a", a.agent(), "l", row.get("instance_label"),
                    "c", row.get("container_name"), "i", w.instance(), "p", w.placement());
            AgentItSupport.assertRefused("23514", null, () -> runtime.update(release,
                    key.addValue("id", row.get("container_id"))), terminal + ": the same container is not a release");
            assertEquals(terminal, owner.queryForObject("SELECT state FROM sm_data.agent_discovery" + where, key, String.class));
            assertEquals(1, runtime.update(release, key.addValue("id", "c".repeat(12))), terminal + ": a re-created container releases it");
            assertEquals("PENDING", owner.queryForObject("SELECT state FROM sm_data.agent_discovery" + where, key, String.class));
        }
        for (String terminal : List.of("UNDECLARED", "RETIRED")) {
            Map<String, Object> row = discoveryRow(a, terminal);
            row.put("instance_id", w.instance());
            row.put("placement_id", w.placement());
            insertDiscovery(row);
            MapSqlParameterSource key = p("t", a.tenant(), "a", a.agent(), "l", row.get("instance_label"),
                    "c", row.get("container_name"), "i", w.instance(), "p", w.placement(), "id", "d".repeat(12));
            AgentItSupport.assertRefused("23514", null, () -> runtime.update(release, key),
                    terminal + ": only a changed label (a different key) releases it, never a re-created container");
        }
    }

    @Test
    void aRevocationIsStickyEvenForTheRuntimeRole() {
        Agent a = enrol(UUID.randomUUID());
        MapSqlParameterSource key = p("t", a.tenant(), "a", a.agent(), "now", OffsetDateTime.now());
        String where = " WHERE tenant_id = :t AND agent_id = :a";
        assertEquals(1, runtime.update("UPDATE sm_data.agent_identity SET revoked_at = :now" + where, key), "the first revocation");
        assertEquals(1, runtime.update("UPDATE sm_data.agent_identity SET revoked_at = :now" + where, key),
                "restating the same instant is not a move");
        PSQLException cleared = AgentItSupport.assertRefused("23514", null,
                () -> runtime.update("UPDATE sm_data.agent_identity SET revoked_at = NULL" + where, key), "clearing");
        assertEquals("revoked_at", cleared.getServerErrorMessage().getColumn());
        AgentItSupport.assertRefused("23514", null, () -> runtime.update(
                "UPDATE sm_data.agent_identity SET revoked_at = :now + interval '1 hour'" + where, key), "moving");
        // before any revocation, nothing is guarded
        Agent fresh = enrol(UUID.randomUUID());
        assertEquals(1, runtime.update("UPDATE sm_data.agent_identity SET revoked_at = NULL WHERE agent_id = :a",
                p("a", fresh.agent())));
    }

    @Test
    void theFencingEpochOnlyRisesEvenForTheRuntimeRole() {
        Agent a = enrol(UUID.randomUUID());
        owner.update("INSERT INTO sm_data.agent_session (tenant_id, agent_id, fencing_epoch, protocol_version, agent_version,"
                        + " opened_at, last_seen_at) VALUES (:t, :a, 5, 1, '1.0', :now, :now)",
                p("t", a.tenant(), "a", a.agent(), "now", OffsetDateTime.now()));
        MapSqlParameterSource key = p("t", a.tenant(), "a", a.agent());
        String where = " WHERE tenant_id = :t AND agent_id = :a";
        PSQLException lowered = AgentItSupport.assertRefused("23514", null,
                () -> runtime.update("UPDATE sm_data.agent_session SET fencing_epoch = 4" + where, key), "lowering");
        assertEquals("fencing_epoch", lowered.getServerErrorMessage().getColumn());
        assertEquals(1, runtime.update("UPDATE sm_data.agent_session SET fencing_epoch = 5" + where, key), "restating is not lowering");
        assertEquals(1, runtime.update("UPDATE sm_data.agent_session SET fencing_epoch = 6" + where, key), "raising");
        assertEquals(6, count("SELECT fencing_epoch::int FROM sm_data.agent_session" + where, key));
    }

    @Test
    void theRuntimeRoleCanDeleteNothingAndUpdateOnlyTheMutableColumnsOfTheAgentTables() {
        assertEquals(List.of(), owner.queryForList("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = 'sm_data' AND c.relkind = 'r' AND c.relname LIKE 'agent\\_%'"
                + " AND (has_table_privilege('sm_dp_runtime', c.oid, 'DELETE') OR has_table_privilege('sm_dp_runtime', c.oid, 'TRUNCATE'))",
                Map.of(), String.class), "OR-77: only a named SECURITY DEFINER function deletes an agent row");
        Map<String, Set<String>> actual = new TreeMap<>();
        for (Map<String, Object> r : owner.queryForList("SELECT c.relname, a.attname FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid"
                + " AND a.attnum > 0 AND NOT a.attisdropped WHERE n.nspname = 'sm_data' AND c.relkind = 'r'"
                + " AND c.relname LIKE 'agent\\_%' AND has_column_privilege('sm_dp_runtime', c.oid, a.attnum, 'UPDATE')", Map.of())) {
            actual.computeIfAbsent((String) r.get("relname"), k -> new TreeSet<>()).add((String) r.get("attname"));
        }
        Map<String, Set<String>> expected = new TreeMap<>();
        expected.put("agent_identity", new TreeSet<>(List.of("agent_epoch", "fleet_epoch", "revoked_at", "capabilities")));
        expected.put("agent_session", new TreeSet<>(List.of("fencing_epoch", "protocol_version", "agent_version", "opened_at",
                "last_seen_at", "closed_at", "features", "clock_offset_ms")));
        expected.put("agent_lease", new TreeSet<>(List.of("state")));
        expected.put("agent_command", new TreeSet<>(List.of("attempt_id", "state", "outcome", "detail_code", "offered_epoch",
                "received_request_id", "ack_request_id", "deadline", "notified_at", "received_at", "acked_at")));
        expected.put("agent_report_watermark", new TreeSet<>(List.of("last_sequence", "last_item_id", "updated_at")));
        expected.put("agent_telemetry_watermark", new TreeSet<>(List.of("last_batch_seq", "updated_at")));
        expected.put("agent_discovery", new TreeSet<>(List.of("container_id", "state", "instance_id", "placement_id",
                "refusal_code", "settled_at", "first_seen_at")));    // first_seen_at: V031, the release restamps the window
        assertEquals(expected, actual, "an identity column (tenant, agent, thumbprint, target, placement, intent, generation,"
                + " hash, operation) appears in no UPDATE list; agent_event has none at all");
    }

    // ---- VDB: command retention only ----------------------------------------------------------------------------

    @Test
    void retentionDeletesOldFinalCommandsButNeverTheHighestGenerationOfAnAgentPlacement() {
        // Each predicate of the DELETE is pinned by a row ONLY that predicate keeps (fix round, finding 2):
        //   state     -> oldQueued   (old, a higher generation follows, but not final)
        //   age       -> recentAcked (final, a higher generation follows, but 1 day old)
        //   higher gen-> top         (final and old, but the highest of its (agent, placement))
        //   placement -> top again, against otherPlacement (same agent, a HIGHER generation, another placement)
        flushRetention();
        OffsetDateTime old = OffsetDateTime.now().minusDays(45);
        Agent agent = enrol(UUID.randomUUID());
        Work w = work(agent);
        String k1 = key(w, "MATERIALIZE:r0");
        UUID i1 = intent(w, "MATERIALIZE", k1, HMAC);
        String k2 = key(w, "LIFECYCLE:r1");
        UUID i2 = intent(w, "LIFECYCLE", k2, null);
        UUID oldAcked = command(w, i1, k1, "APPLY", "ACKED", HMAC, old);
        UUID oldSuperseded = command(w, i1, k1, "REMATERIALIZE", "SUPERSEDED", HMAC, old);
        UUID oldQueued = command(w, i2, k2, "RESTART", "QUEUED", null, null, old);
        UUID recentAcked = command(w, i2, k2, "STOP", "ACKED", null, OffsetDateTime.now().minusDays(1));
        UUID top = command(w, i1, k1, "APPLY", "REFUSED", HMAC, old);           // old, final, and the HIGHEST generation of w's placement
        Work w2 = work(agent);                                                   // a second placement of the SAME agent
        String k3 = key(w2, "MATERIALIZE:r0");
        UUID i3 = intent(w2, "MATERIALIZE", k3, HMAC);
        UUID otherPlacement = command(w2, i3, k3, "APPLY", "ACKED", HMAC, old);  // its generation is above top's

        Map<String, Object> out = runtime.queryForMap("SELECT commands_deleted, leases_deleted FROM sm_data.agent_gateway_maintenance()",
                Map.of());

        assertEquals(2L, out.get("commands_deleted"), "exactly the two superseded old final generations went");
        assertEquals(0L, out.get("leases_deleted"), "no lease is old in this scenario");
        assertEquals(0, exists(oldAcked), "an old final command with a newer sibling is deleted");
        assertEquals(0, exists(oldSuperseded), "…so is a SUPERSEDED one");
        assertEquals(1, exists(top), "R-1: the highest wire_generation of an (agent, placement) is never deleted, however old,"
                + " and another placement's higher generation does not shield it from that rule");
        assertEquals(1, exists(otherPlacement), "the highest of the other placement");
        assertEquals(1, exists(oldQueued), "an old command that is not final is never deleted");
        assertEquals(1, exists(recentAcked), "a recent final command is never deleted");
    }

    private static int exists(UUID command) {
        return count("SELECT count(*)::int FROM sm_data.agent_command WHERE command_id = :c", p("c", command));
    }

    private static UUID lease(Agent a, String state, OffsetDateTime expires) {
        UUID id = UUID.randomUUID();
        owner.update("INSERT INTO sm_data.agent_lease (tenant_id, agent_id, lease_id, certificate_thumbprint, agent_epoch,"
                        + " fleet_epoch, issued_at, expires_at, audience, renewal_nonce_digest, admission_margin_ms,"
                        + " server_clock, state) VALUES (:t, :a, :l, :th, 1, 1, :iss, :exp, 'gw', :nd, 0, :iss, :s)",
                p("t", a.tenant(), "a", a.agent(), "l", id, "th", thumbprint(), "iss", expires.minusHours(1),
                        "exp", expires, "nd", thumbprint(), "s", state));
        return id;
    }

    private static int leaseExists(UUID lease) {
        return count("SELECT count(*)::int FROM sm_data.agent_lease WHERE lease_id = :l", p("l", lease));
    }

    /** Runs the function once to flush what other tests left, so the next call's counts are this test's alone. */
    private static void flushRetention() {
        runtime.queryForObject("SELECT commands_deleted FROM sm_data.agent_gateway_maintenance()", Map.of(), Long.class);
    }

    @Test
    void retentionDeletesExpiredNonActiveLeasesOlderThanThirtyDaysAndNothingElse() {
        flushRetention();
        Agent a = enrol(UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        UUID expired = lease(a, "EXPIRED", now.minusDays(45));
        UUID revoked = lease(a, "REVOKED", now.minusDays(31));
        UUID active = lease(a, "ACTIVE", now.minusDays(90));          // however old, an ACTIVE lease is kept
        UUID recent = lease(a, "SUPERSEDED", now.minusDays(29));      // expired 29 days ago: inside the window
        owner.update("INSERT INTO sm_data.agent_session (tenant_id, agent_id, fencing_epoch, protocol_version, agent_version,"
                        + " opened_at, last_seen_at, closed_at) VALUES (:t, :a, 7, 1, '1.0', :o, :o, :o)",
                p("t", a.tenant(), "a", a.agent(), "o", now.minusDays(90)));

        Map<String, Object> out = runtime.queryForMap("SELECT commands_deleted, leases_deleted FROM sm_data.agent_gateway_maintenance()",
                Map.of());

        assertEquals(0L, out.get("commands_deleted"));
        assertEquals(2L, out.get("leases_deleted"), "OR-134: the expired and the revoked lease, no more");
        assertEquals(0, leaseExists(expired), "a non-ACTIVE lease expired 45 days ago is deleted");
        assertEquals(0, leaseExists(revoked), "…so is one revoked and expired 31 days ago");
        assertEquals(1, leaseExists(active), "an ACTIVE lease is never deleted, however old");
        assertEquals(1, leaseExists(recent), "a lease that expired 29 days ago is kept");
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.agent_session WHERE tenant_id = :t AND fencing_epoch = 7",
                p("t", a.tenant())), "OR-134: an old closed agent_session row survives (it keeps the fencing epoch monotonic)");
    }

    @Test
    void retentionDeletesNoActiveLeaseNoSessionNoEventAndNoDiscoveryRow() {
        Agent a = enrol(UUID.randomUUID());
        OffsetDateTime old = OffsetDateTime.now().minusDays(90);
        lease(a, "ACTIVE", old);
        owner.update("INSERT INTO sm_data.agent_session (tenant_id, agent_id, fencing_epoch, protocol_version, agent_version,"
                        + " opened_at, last_seen_at, closed_at) VALUES (:t, :a, 1, 1, '1.0', :o, :o, :o)",
                p("t", a.tenant(), "a", a.agent(), "o", old));
        owner.update("INSERT INTO sm_data.agent_event (tenant_id, event_id, agent_id, request_id, code, actor_kind,"
                        + " correlation_id, client_address, occurred_at) VALUES (:t, :e, :a, 'old', 'gateway.drain', 'SYSTEM',"
                        + " :c, '198.51.100.7', :o)",
                p("t", a.tenant(), "e", UUID.randomUUID(), "a", a.agent(), "c", UUID.randomUUID(), "o", old));
        Map<String, Object> tombstone = discoveryRow(a, "ABANDONED");
        tombstone.put("first_seen_at", old);
        tombstone.put("settled_at", old);
        insertDiscovery(tombstone);

        flushRetention();

        MapSqlParameterSource t = p("t", a.tenant());
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.agent_lease WHERE tenant_id = :t", t), "an ACTIVE lease");
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.agent_session WHERE tenant_id = :t", t), "OR-134: sessions are kept");
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.agent_event WHERE tenant_id = :t", t), "agent_event is never deleted");
        assertEquals(1, count("SELECT count(*)::int FROM sm_data.agent_discovery WHERE tenant_id = :t", t),
                "a tombstone is the contract that stops re-registration: retention never deletes it");
    }

    @Test
    void theMaintenanceFunctionIsDefinerOwnedPinnedAndCallableOnlyByTheRuntimeRole() {
        Map<String, Object> f = owner.queryForMap("SELECT pg_get_userbyid(p.proowner) AS owner, p.prosecdef AS definer,"
                + " p.proconfig::text AS cfg, p.pronargs AS args FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                + " WHERE n.nspname = 'sm_data' AND p.proname = 'agent_gateway_maintenance'", Map.of());
        assertEquals("sm_dp_owner", f.get("owner"));
        assertEquals(Boolean.TRUE, f.get("definer"));
        assertEquals(0, ((Number) f.get("args")).intValue(), "no argument reaches a DELETE (I5)");
        assertTrue(((String) f.get("cfg")).contains("search_path=sm_data, pg_temp"), "R-22. Was: " + f.get("cfg"));
        assertEquals(Boolean.FALSE, owner.queryForObject(
                "SELECT has_function_privilege('public', 'sm_data.agent_gateway_maintenance()', 'EXECUTE')", Map.of(), Boolean.class));
        assertEquals(Boolean.TRUE, owner.queryForObject(
                "SELECT has_function_privilege('sm_dp_runtime', 'sm_data.agent_gateway_maintenance()', 'EXECUTE')", Map.of(), Boolean.class));
    }


    // ---- V030: what the agent declared at SessionOpen (plan 015 T2.1b fix round) ----------------------------------------

    @Test
    void sessionFeaturesDefaultEmptyAreAClosedVocabularyAndTheClockOffsetMayBeNull() {
        Agent a = enrol(UUID.randomUUID());
        owner.update("INSERT INTO sm_data.agent_session (tenant_id, agent_id, fencing_epoch, protocol_version, agent_version,"
                        + " opened_at, last_seen_at) VALUES (:t, :a, 1, 1, '1.0', :now, :now)",
                p("t", a.tenant(), "a", a.agent(), "now", OffsetDateTime.now()));
        MapSqlParameterSource key = p("t", a.tenant(), "a", a.agent());
        String where = " WHERE tenant_id = :t AND agent_id = :a";
        assertEquals("{}", owner.queryForObject("SELECT features::text FROM sm_data.agent_session" + where, key, String.class));
        assertNull(owner.queryForObject("SELECT clock_offset_ms FROM sm_data.agent_session" + where, key, Long.class));
        assertEquals(1, runtime.update("UPDATE sm_data.agent_session SET features = ARRAY['discovery', 'rematerialize'],"
                + " clock_offset_ms = -1500" + where, key), "the runtime role may write both columns");
        AgentItSupport.assertRefused("23514", "agent_session_features",
                () -> runtime.update("UPDATE sm_data.agent_session SET features = ARRAY['telepathy']" + where, key));
        AgentItSupport.assertRefused("42501", null,
                () -> runtime.update("UPDATE sm_data.agent_session SET agent_id = gen_random_uuid()" + where, key));
    }


    private static Flyway flyway(DataSource migrator, String target) {
        return AgentItSupport.flyway(migrator, target);
    }
}
