package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.ConfigSecretService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.SecretValueRejectedException;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.api.config.StaleStateVersionException;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.production.ConfigGenerationRenderedConfigSource;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.ConfigSecretServiceImpl;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.SecretPurpose;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T10.1 / OR-30 / OR-45 / OR-60 against a real PostgreSQL 14 as {@code sm_dp_runtime}: the secret row and the
 * generation that names it are ONE transaction, the value is in no column of any table other than as ciphertext, and
 * the staged reference is what the renderer resolves at apply time.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConfigSecretServiceIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SENTINEL = "SECRET-SENTINEL-t101-9d41c";
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";
    private static final String PATH = "cache-engine.solace.password";

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;
    private static EnvelopeSecretStore secrets;
    private static ConfigHasher hasher;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
        secrets = SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 41), null));
        hasher = TestHashers.keyed();
    }

    private static ConfigSecretService service(int retained) {
        return new ConfigSecretServiceImpl(ApplyEngineHarness.transactions(), new JdbcConfigGenerationRepository(runtime),
                secrets, hasher, new Retention(retained), Clock.systemUTC());
    }

    private static ConfigSecretService service() {
        return service(10);
    }

    private static final class Inst {
        final UUID tenant = SmDataPostgres.freshTenant();
        final UUID instance = UUID.randomUUID();

        Inst() {
            InventoryFixtures.declare(tenant, instance);
        }

        long stateVersion() {
            return runtime.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                    + " AND instance_id = :i", key(), Long.class);
        }

        List<Long> generations() {
            return runtime.queryForList("SELECT generation FROM sm_data.config_generation WHERE tenant_id = :t"
                    + " AND instance_id = :i ORDER BY generation", key(), Long.class);
        }

        long secretRows() {
            return secretRowsOf(tenant);
        }

        MapSqlParameterSource key() {
            return new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance);
        }

        /** The builder a gateway would supply: a copy of the draft with the field replaced by the new reference. */
        ConfigSecretService.StageBuilder builder(long base) {
            return ref -> new StageCommand(base, "cache", "2.4", SELECTION,
                    "{\"cache-1\":{\"solace.password\":\"secretRef://" + ref + "\"}}",
                    List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"5m\""),
                            RenderedEntryValue.secret("cache-1", PATH, ref)),
                    GenerationOrigin.EDIT, null, ACTOR);
        }

        StageResult set(String value) {
            return service().storeAndStage(tenant, instance, stateVersion(), value.toCharArray(), ACTOR,
                    builder(stateVersion()));
        }
    }

    private static long secretRowsOf(UUID tenant) {
        return superuser.queryForObject("SELECT count(*) FROM sm_secret.secret WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class);
    }

    // ---- one transaction ------------------------------------------------------------------------------------------

    @Test
    void theSecretRowAndTheGenerationCommitTogether() {
        Inst x = new Inst();
        long base = x.stateVersion();
        AtomicLong secretsSeenFromAnotherConnection = new AtomicLong(-1);
        AtomicLong generationsSeenFromAnotherConnection = new AtomicLong(-1);

        StageResult result = service().storeAndStage(x.tenant, x.instance, base, SENTINEL.toCharArray(), ACTOR, ref -> {
            // Inside the transaction, after the secret insert: another connection must see neither row yet.
            secretsSeenFromAnotherConnection.set(secretRowsOf(x.tenant));
            generationsSeenFromAnotherConnection.set(superuser.queryForObject(
                    "SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t", x.key(), Long.class));
            return x.builder(base).build(ref);
        });

        assertEquals(0, secretsSeenFromAnotherConnection.get(),
                "the secret was not committed on its own before the generation");
        assertEquals(0, generationsSeenFromAnotherConnection.get());
        assertEquals(1, x.secretRows(), "and afterwards both are there");
        assertEquals(List.of(1L), x.generations());
        assertEquals(1, result.generation().generation());
        assertFalse(result.unchanged());
        assertEquals(x.stateVersion(), result.stateVersion());
        assertTrue(result.stateVersion() > base, "the instance's version advanced");
    }

    @Test
    void aStaleBaseStateVersionIsRefusedStoresNoSecretAndNeverAsksForTheStage() {
        Inst x = new Inst();
        long base = x.stateVersion();
        x.set("first-operators-value");   // moves the version
        long secretsBefore = x.secretRows();
        AtomicInteger asked = new AtomicInteger();

        StaleStateVersionException stale = assertThrows(StaleStateVersionException.class,
                () -> service().storeAndStage(x.tenant, x.instance, base, SENTINEL.toCharArray(), ACTOR, ref -> {
                    asked.incrementAndGet();
                    return x.builder(base).build(ref);
                }));

        assertEquals(x.stateVersion(), stale.currentStateVersion());
        assertEquals(secretsBefore, x.secretRows(), "the loser stored no secret");
        assertEquals(0, asked.get(), "and the stage was not even built");
        assertEquals(List.of(1L), x.generations());
    }

    @Test
    void aStageThatIsRefusedAfterTheSecretWasStoredLeavesNoSecretAndNoGeneration() {
        Inst x = new Inst();
        long base = x.stateVersion();

        assertThrows(IllegalStateException.class, () -> service().storeAndStage(x.tenant, x.instance, base,
                SENTINEL.toCharArray(), ACTOR, ref -> {
                    throw new IllegalStateException("the gateway refused the re-projection");
                }));

        assertEquals(0, x.secretRows(), "the secret insert was rolled back with the refused stage");
        assertEquals(List.of(), x.generations());
        assertEquals(base, x.stateVersion());
    }

    @Test
    void aMalformedCommandRollsTheSecretBackToo() {
        Inst x = new Inst();
        long base = x.stateVersion();

        assertThrows(IllegalArgumentException.class, () -> service().storeAndStage(x.tenant, x.instance, base,
                SENTINEL.toCharArray(), ACTOR, ref -> new StageCommand(base, "cache", "2.4", "not json", "{}", List.of(),
                        GenerationOrigin.EDIT, null, ACTOR)));

        assertEquals(0, x.secretRows());
        assertEquals(List.of(), x.generations());
    }

    @Test
    void aCommandWhoseBaseIsNotTheCallsIsRefusedAndRolledBack() {
        Inst x = new Inst();
        long base = x.stateVersion();

        assertThrows(IllegalStateException.class, () -> service().storeAndStage(x.tenant, x.instance, base,
                SENTINEL.toCharArray(), ACTOR, ref -> x.builder(base + 1).build(ref)));

        assertEquals(0, x.secretRows());
        assertEquals(List.of(), x.generations());
    }

    @Test
    void aCommandThatIsNotAnEditIsRefusedAndRolledBack() {
        Inst x = new Inst();
        long base = x.stateVersion();

        assertThrows(IllegalStateException.class, () -> service().storeAndStage(x.tenant, x.instance, base,
                SENTINEL.toCharArray(), ACTOR, ref -> new StageCommand(base, "cache", "2.4", SELECTION, "{}",
                        List.of(), GenerationOrigin.ROLLBACK, 1L, ACTOR)));

        assertEquals(0, x.secretRows());
        assertEquals(List.of(), x.generations());
    }

    @Test
    void anEmptyOrOversizedValueIsRejectedAndStoresNothing() {
        Inst x = new Inst();
        long base = x.stateVersion();
        char[] oversized = new char[EnvelopeSecretStore.MAX_VALUE_BYTES + 1];
        Arrays.fill(oversized, 'x');

        for (char[] value : List.of(new char[0], oversized)) {
            assertThrows(SecretValueRejectedException.class,
                    () -> service().storeAndStage(x.tenant, x.instance, base, value, ACTOR, x.builder(base)));
        }

        assertEquals(0, x.secretRows());
        assertEquals(List.of(), x.generations());
        assertEquals(base, x.stateVersion());
    }

    @Test
    void anInstanceThatIsNotDeclaredInThisOrganizationIsNotFoundAndStoresNothing() {
        Inst x = new Inst();
        UUID otherTenant = SmDataPostgres.freshTenant();
        long base = x.stateVersion();

        assertThrows(NoSuchElementException.class, () -> service().storeAndStage(otherTenant, x.instance, base,
                SENTINEL.toCharArray(), ACTOR, x.builder(base)));
        assertThrows(NoSuchElementException.class, () -> service().storeAndStage(x.tenant, UUID.randomUUID(), base,
                SENTINEL.toCharArray(), ACTOR, x.builder(base)));

        assertEquals(0, x.secretRows());
        assertEquals(0, secretRowsOf(otherTenant));
        assertEquals(List.of(), x.generations());
    }

    // ---- the value never reaches a row, a response or a log ---------------------------------------------------------

    @Test
    void theValueNeverAppearsInAnyRowOfAnySmDataOrSmSecretColumnOrInTheLog() {
        Inst x = new Inst();

        LogCapture.Captured<StageResult> run = LogCapture.during(() -> x.set(SENTINEL));

        assertFalse(run.value().toString().contains(SENTINEL));
        assertFalse(run.text().contains(SENTINEL), "even at TRACE (Spring prints bind values there): " + run.text());
        List<String> hits = new ArrayList<>();
        for (Map<String, Object> column : superuser.queryForList(
                "SELECT table_schema, table_name, column_name, data_type FROM information_schema.columns"
                        + " WHERE table_schema IN ('sm_data', 'sm_secret')", Map.of())) {
            String qualified = column.get("table_schema") + "." + column.get("table_name");
            String name = "\"" + column.get("column_name") + "\"";
            String expression = "bytea".equals(column.get("data_type"))
                    ? "encode(" + name + ", 'escape')" : name + "::text";
            Long found = superuser.queryForObject("SELECT count(*) FROM " + qualified + " WHERE " + expression
                    + " LIKE :needle", new MapSqlParameterSource("needle", "%" + SENTINEL + "%"), Long.class);
            if (found != null && found > 0) {
                hits.add(qualified + "." + column.get("column_name"));
            }
        }
        assertEquals(List.of(), hits, "the plaintext is in no column of any table");
        assertEquals(1, x.secretRows());
        assertEquals("CONFIG_FIELD", superuser.queryForObject(
                "SELECT purpose FROM sm_secret.secret WHERE tenant_id = :t", x.key(), String.class));
    }

    @Test
    void theStagedGenerationCarriesTheReferenceAndTheRendererResolvesItWhileTheHistoryShowsNoValue() {
        Inst x = new Inst();

        StageResult result = x.set(SENTINEL);

        UUID ref = UUID.fromString(runtime.queryForObject("SELECT secret_id::text FROM sm_secret.secret"
                + " WHERE tenant_id = :t", x.key(), String.class));
        String draft = runtime.queryForObject("SELECT draft_values::text FROM sm_data.config_generation WHERE"
                + " tenant_id = :t AND instance_id = :i AND generation = 1", x.key(), String.class);
        String rendered = runtime.queryForObject("SELECT rendered_entries::text FROM sm_data.config_generation WHERE"
                + " tenant_id = :t AND instance_id = :i AND generation = 1", x.key(), String.class);
        assertTrue(draft.contains("secretRef://" + ref), draft);
        assertTrue(rendered.contains(ref.toString()), rendered);
        assertEquals(GenerationOrigin.EDIT, result.generation().origin());
        assertEquals(ACTOR, result.generation().createdBy());
        assertTrue(result.generation().contentDigest().startsWith("hmacsha256v"),
                "keyed digest: " + result.generation().contentDigest());

        PlacementRef placement = new PlacementRef(new TenantId(x.tenant), new InstanceId(x.instance),
                new PlacementId(UUID.randomUUID()));
        RenderedConfig file = new ConfigGenerationRenderedConfigSource(new JdbcConfigGenerationRepository(runtime),
                secrets, hasher).render(placement, 1);
        String yaml = new String(file.bytes(), StandardCharsets.UTF_8);
        assertTrue(yaml.contains(SENTINEL), "the reference was resolved into the rendered bytes at apply time");
        assertFalse(yaml.contains("secretRef"), "a reference is never written to a host");
        assertNotNull(result.generation().createdAt());
    }

    // ---- the generation it writes is a normal one -------------------------------------------------------------------

    @Test
    void theGenerationIsTheNextOneAdvancesTheHeadAndPrunesBeyondTheRetentionBound() {
        Inst x = new Inst();
        ConfigSecretService service = service(2);
        List<Long> numbers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            long base = x.stateVersion();
            StageResult r = service.storeAndStage(x.tenant, x.instance, base, ("value-" + i).toCharArray(), ACTOR,
                    x.builder(base));
            numbers.add(r.generation().generation());
            assertEquals(x.stateVersion(), r.stateVersion());
        }

        assertEquals(List.of(1L, 2L, 3L, 4L), numbers);
        assertEquals(List.of(3L, 4L), x.generations(), "only the newest two are kept");
        assertEquals(4, runtime.queryForObject("SELECT last_generation FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", x.key(), Long.class));
        assertEquals(4, x.secretRows(), "superseded secret rows are kept (OR-85 OQ-4); the runtime role cannot delete them");
    }

    @Test
    void aSecretSetAfterAnOrdinaryStageSitsBesideItsGenerationNumbering() {
        Inst x = new Inst();
        ConfigRecordServiceImpl records = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(runtime), hasher, new Retention(10), Clock.systemUTC(),
                new JdbcRollbackTargetRepository(runtime));
        records.stage(x.tenant, x.instance, new StageCommand(x.stateVersion(), "cache", "2.4", SELECTION,
                "{\"cache-1\":{\"ttl\":\"5m\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"5m\"")), GenerationOrigin.EDIT, null,
                ACTOR));

        StageResult result = x.set(SENTINEL);

        assertEquals(2, result.generation().generation());
        assertEquals(List.of(1L, 2L), x.generations());
    }

    @Test
    void twoOperatorsSettingASecretFromOneBaseCannotBothWinAndTheLoserStoresNothing() throws Exception {
        Inst x = new Inst();
        long base = x.stateVersion();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (String value : List.of("operator-one-value", "operator-two-value")) {
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        return service().storeAndStage(x.tenant, x.instance, base, value.toCharArray(), ACTOR,
                                x.builder(base));
                    } catch (StaleStateVersionException e) {
                        return e;
                    }
                }));
            }
            go.countDown();
            int winners = 0;
            int losers = 0;
            for (Future<Object> f : results) {
                Object r = f.get(30, TimeUnit.SECONDS);
                if (r instanceof StageResult) {
                    winners++;
                } else {
                    assertInstanceOf(StaleStateVersionException.class, r);
                    losers++;
                }
            }
            assertEquals(1, winners);
            assertEquals(1, losers);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(List.of(1L), x.generations());
        assertEquals(1, x.secretRows(), "the loser's secret was never stored");
    }

    @Test
    void thePurposeStoredIsConfigFieldAndNothingElseCanBeReferencedByIt() {
        Inst x = new Inst();
        x.set(SENTINEL);

        UUID ref = UUID.fromString(runtime.queryForObject("SELECT secret_id::text FROM sm_secret.secret"
                + " WHERE tenant_id = :t", x.key(), String.class));
        try (var material = secrets.resolve(x.tenant, ref, SecretPurpose.CONFIG_FIELD)) {
            assertEquals(SENTINEL, new String(material.chars()));
        }
    }
}
