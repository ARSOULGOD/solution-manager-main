package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.dp.api.config.ConfigDraft;
import com.solace.sm.dp.api.config.ConfigRecordService;
import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.GenerationView;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.api.config.StageResult;
import com.solace.sm.dp.api.config.StaleStateVersionException;
import com.solace.sm.dp.apply.engine.AdmissionOutcome;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.ScopeKey;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigRecordConfiguration;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T5.2 / OR-33 against a real PostgreSQL 14, as {@code sm_dp_runtime}: immutable, bounded generations,
 * the stale-base conflict, guard 1, and the two things the database refuses to delete whatever the configuration
 * says. Retention's N is configured through the real configuration class, so a hard-coded N is a red test.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConfigRecordServiceIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SENTINEL = "SENTINEL-literal-secret-value";
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";

    private static NamedParameterJdbcTemplate runtime;
    private static NamedParameterJdbcTemplate superuser;

    @BeforeAll
    static void bootstrap() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        superuser = SmDataPostgres.superuserJdbc();
    }

    // ---- the service, built the way production builds it ---------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    @Import({ConfigRecordConfiguration.class, ConfigPersistenceConfiguration.class,
            MetricDefinitionPersistenceConfiguration.class})
    static class Wiring {

        @Bean
        DataSource smDataDataSource() {
            return SmDataPostgres.runtimeDataSource();
        }

        @Bean
        NamedParameterJdbcTemplate smDataJdbc() {
            return SmDataPostgres.runtimeJdbc();
        }

        @Bean
        TransactionTemplate smDataTransactions() {
            return ApplyEngineHarness.transactions();
        }

        @Bean
        ConfigHasher configHasher() {
            return TestHashers.keyed();
        }
    }

    /** Runs {@code body} against the service from a context configured with {@code retained} generations. */
    private static void withService(Integer retained, java.util.function.Consumer<ConfigRecordService> body) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Wiring.class);
        if (retained != null) {
            runner = runner.withPropertyValues("sm.dp.config.retained-generations=" + retained);
        }
        runner.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            body.accept(ctx.getBean(ConfigRecordService.class));
        });
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

        long lastGeneration() {
            return runtime.queryForObject("SELECT last_generation FROM sm_data.instance_state WHERE tenant_id = :t"
                    + " AND instance_id = :i", key(), Long.class);
        }

        List<Long> generations() {
            return runtime.queryForList("SELECT generation FROM sm_data.config_generation WHERE tenant_id = :t"
                    + " AND instance_id = :i ORDER BY generation", key(), Long.class);
        }

        MapSqlParameterSource key() {
            return new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance);
        }

        StageCommand edit(long base, String ttl) {
            return command(base, "{\"cache-1\":{\"ttl\":\"" + ttl + "\"}}", GenerationOrigin.EDIT, null);
        }

        StageCommand command(long base, String draftJson, GenerationOrigin origin, Long derived) {
            return new StageCommand(base, "cache", "2.4", SELECTION, draftJson,
                    List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"x\"")), origin, derived, ACTOR);
        }

        StageResult stage(ConfigRecordService s, String ttl) {
            return s.stage(tenant, instance, edit(stateVersion(), ttl));
        }
    }

    // ---- staging --------------------------------------------------------------------------------------------------

    @Test
    void stagingAssignsTheNextGenerationUnderTheInstanceLock() {
        Inst x = new Inst();
        withService(null, s -> {
            StageResult first = x.stage(s, "10m");
            StageResult second = x.stage(s, "20m");

            assertEquals(1, first.generation().generation());
            assertFalse(first.unchanged());
            assertEquals(2, second.generation().generation());
            assertEquals(List.of(1L, 2L), x.generations());
            assertEquals(2, x.lastGeneration());
            assertEquals(x.stateVersion(), second.stateVersion(), "the result carries the instance's new version");
            assertTrue(second.stateVersion() > first.stateVersion(), "and it advances");
            assertEquals(GenerationOrigin.EDIT, second.generation().origin());
            assertNull(second.generation().derivedFromGeneration());
            assertEquals(ACTOR, second.generation().createdBy());
            assertTrue(second.generation().stateVersion() < second.stateVersion(),
                    "the generation row is stamped before the head moves, so the instance stamp is the highest");
        });
    }

    /** Review Focus 4: two operators stage from one base; the second loses and the first is not overwritten. */
    @Test
    void aStaleBaseStateVersionIsRefusedAndWritesNothing() {
        Inst x = new Inst();
        withService(null, s -> {
            long base = x.stateVersion();
            StageResult winner = s.stage(x.tenant, x.instance, x.edit(base, "10m"));

            StaleStateVersionException e = assertThrows(StaleStateVersionException.class,
                    () -> s.stage(x.tenant, x.instance, x.edit(base, "99m")));

            assertEquals(winner.stateVersion(), e.currentStateVersion());
            assertEquals(List.of(1L), x.generations(), "nothing was written for the loser");
            assertEquals(1, x.lastGeneration());
            assertEquals(winner.stateVersion(), x.stateVersion(), "and the instance did not move");
            assertTrue(s.latestDraft(x.tenant, x.instance).orElseThrow().draftValuesJson().contains("10m"),
                    "the first operator's generation is the one on record");
        });
    }

    @Test
    void twoOperatorsStagingAtTheSameInstantFromOneBaseCannotBothWin() throws Exception {
        Inst x = new Inst();
        withService(null, s -> {
            long base = x.stateVersion();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Object>> results = new ArrayList<>();
                for (String ttl : List.of("11m", "22m")) {
                    results.add(pool.submit(() -> {
                        go.await();
                        try {
                            return s.stage(x.tenant, x.instance, x.edit(base, ttl));
                        } catch (StaleStateVersionException e) {
                            return e;
                        }
                    }));
                }
                go.countDown();
                long winners = 0;
                long losers = 0;
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
            } catch (Exception e) {
                throw new AssertionError(e);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(List.of(1L), x.generations());
        });
    }

    /** OR-33 guard 1. */
    @Test
    void identicalContentWritesNoRowAndReturnsTheLatestWithUnchangedTrue() {
        Inst x = new Inst();
        withService(null, s -> {
            StageResult first = s.stage(x.tenant, x.instance, x.command(x.stateVersion(),
                    "{\"cache-1\":{\"ttl\":\"10m\",\"namespace\":\"orders\"}}", GenerationOrigin.EDIT, null));
            long versionBefore = x.stateVersion();

            // the same content with its keys in another order, from the instance's current version
            StageResult again = s.stage(x.tenant, x.instance, x.command(versionBefore,
                    "{\"cache-1\":{\"namespace\":\"orders\",\"ttl\":\"10m\"}}", GenerationOrigin.EDIT, null));

            assertTrue(again.unchanged());
            assertEquals(first.generation().generation(), again.generation().generation());
            assertEquals(first.generation().contentDigest(), again.generation().contentDigest());
            assertEquals(List.of(1L), x.generations(), "no row");
            assertEquals(versionBefore, x.stateVersion(), "and no stamp was drawn: nothing observable changed");
            assertEquals(versionBefore, again.stateVersion());

            StageResult changed = s.stage(x.tenant, x.instance, x.command(versionBefore,
                    "{\"cache-1\":{\"ttl\":\"11m\",\"namespace\":\"orders\"}}", GenerationOrigin.EDIT, null));
            assertFalse(changed.unchanged());
            assertEquals(2, changed.generation().generation());
        });
    }

    // ---- OR-96: presentation layout lives outside the config of record ------------------------------------------

    private static final String L1 = "{\"cache-1\":{\"x\":1,\"y\":2}}";
    private static final String L2 = "{\"cache-1\":{\"x\":400.5,\"y\":-250}}";

    private static StageCommand withLayout(Inst x, long base, String ttl, String layoutJson) {
        return new StageCommand(base, "cache", "2.4", SELECTION, "{\"cache-1\":{\"ttl\":\"" + ttl + "\"}}",
                List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"x\"")), GenerationOrigin.EDIT,
                null, ACTOR, layoutJson);
    }

    private static String layoutRecord(Inst x) {
        return runtime.query("SELECT layout::text FROM sm_data.designer_layout WHERE tenant_id = :t AND instance_id = :i",
                x.key(), (rs, n) -> rs.getString(1)).stream().findFirst().orElse(null);
    }

    private static JsonNode tree(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** OR-96 (UI-D20): a node drag is not a config change, and it is not lost either. */
    @Test
    void aLayoutOnlySaveIsPersistedInThePresentationRecordAndMintsNoGenerationOrStamp() {
        Inst x = new Inst();
        withService(null, s -> {
            StageResult first = s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "10m", L1));
            long versionBefore = x.stateVersion();
            String digestBefore = first.generation().contentDigest();
            assertEquals(tree(L1), tree(s.latestDraft(x.tenant, x.instance).orElseThrow().layoutJson()));

            StageResult dragged = s.stage(x.tenant, x.instance, withLayout(x, versionBefore, "10m", L2));

            assertTrue(dragged.unchanged(), "layout is not content: guard 1 still answers unchanged");
            assertEquals(first.generation().generation(), dragged.generation().generation());
            assertEquals(digestBefore, dragged.generation().contentDigest(), "and the hash did not move");
            assertEquals(List.of(1L), x.generations(), "no generation row");
            assertEquals(versionBefore, x.stateVersion(), "and no stamp was drawn");
            assertEquals(versionBefore, dragged.stateVersion());
            assertEquals(tree(L2), tree(s.latestDraft(x.tenant, x.instance).orElseThrow().layoutJson()),
                    "but the new layout is what the designer reads back");
            assertEquals(tree(L2), tree(layoutRecord(x)), "from the presentation record");
        });
    }

    @Test
    void theGenerationRowHoldsNoLayoutWhateverTheCommandCarried() {
        Inst x = new Inst();
        withService(null, s -> {
            String selectionWithLayout = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\","
                    + "\"layout\":{\"x\":7,\"y\":8}}]}";
            s.stage(x.tenant, x.instance, new StageCommand(x.stateVersion(), "cache", "2.4", selectionWithLayout,
                    "{}", List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"x\"")),
                    GenerationOrigin.EDIT, null, ACTOR, L1));

            String stored = runtime.queryForObject("SELECT component_selection::text FROM sm_data.config_generation"
                    + " WHERE tenant_id = :t AND instance_id = :i AND generation = 1", x.key(), String.class);
            assertFalse(stored.contains("layout"), "OR-96: the config of record holds no presentation state: " + stored);
            assertEquals(tree(L1), tree(layoutRecord(x)), "the record holds what the command's layout said");
        });
    }

    @Test
    void aStageWithoutALayoutLeavesTheRecordUntouchedAndAChangedStageWithOneUpdatesBoth() {
        Inst x = new Inst();
        withService(null, s -> {
            s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "10m", L1));

            // a copy-forward (rollback, restore, accept) carries no layout: the operator's boxes stay where they are
            StageResult noLayout = s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "20m", null));
            assertEquals(2, noLayout.generation().generation());
            assertEquals(tree(L1), tree(layoutRecord(x)));

            StageResult both = s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "30m", L2));
            assertEquals(3, both.generation().generation());
            assertFalse(both.unchanged());
            assertEquals(tree(L2), tree(layoutRecord(x)));
            assertEquals(tree(L2), tree(s.latestDraft(x.tenant, x.instance).orElseThrow().layoutJson()));
        });
    }

    @Test
    void anEmptyLayoutClearsTheRecordAndCreatesNoRowWhereThereWasNone() {
        Inst x = new Inst();
        withService(null, s -> {
            s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "10m", "{}"));
            assertNull(layoutRecord(x), "nothing to remember, nothing written");
            assertEquals("{}", s.latestDraft(x.tenant, x.instance).orElseThrow().layoutJson(),
                    "and the designer reads an empty layout");

            s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "10m", L1));
            s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "10m", "{}"));
            assertEquals(tree("{}"), tree(layoutRecord(x)));
        });
    }

    @Test
    void anIdenticalLayoutWritesNothing() {
        Inst x = new Inst();
        withService(null, s -> {
            s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "10m", L1));
            superuser.update("UPDATE sm_data.designer_layout SET updated_at = TIMESTAMPTZ '2001-01-01 00:00:00+00'"
                    + " WHERE tenant_id = :t AND instance_id = :i", x.key());

            // same positions, keys in another order, an equal number spelt differently
            s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "10m", "{\"cache-1\":{\"y\":2.0,\"x\":1}}"));

            assertEquals("2001", runtime.queryForObject("SELECT to_char(updated_at, 'YYYY') FROM sm_data.designer_layout"
                    + " WHERE tenant_id = :t AND instance_id = :i", x.key(), String.class),
                    "the record was not rewritten for an equal layout");
        });
    }

    @Test
    void aStaleBaseRefusesALayoutOnlySaveAndStoresNothing() {
        Inst x = new Inst();
        withService(null, s -> {
            long base = x.stateVersion();
            s.stage(x.tenant, x.instance, withLayout(x, base, "10m", L1));

            assertThrows(StaleStateVersionException.class,
                    () -> s.stage(x.tenant, x.instance, withLayout(x, base, "10m", L2)),
                    "a layout save from a stale view is refused like any other save");
            assertEquals(tree(L1), tree(layoutRecord(x)));
        });
    }

    @Test
    void theRecordIsTenantScopedAndAnUndeclaredInstanceStoresNothing() {
        Inst mine = new Inst();
        withService(null, s -> {
            s.stage(mine.tenant, mine.instance, withLayout(mine, mine.stateVersion(), "10m", L1));

            assertTrue(s.latestDraft(UUID.randomUUID(), mine.instance).isEmpty(),
                    "another tenant reads nothing of this instance, layout included");
            UUID stranger = UUID.randomUUID();
            assertThrows(NoSuchElementException.class, () -> s.stage(mine.tenant, stranger,
                    withLayout(mine, 1, "10m", L1)));
            assertEquals(0, runtime.queryForObject("SELECT count(*)::int FROM sm_data.designer_layout"
                    + " WHERE tenant_id = :t AND instance_id = :s", mine.key().addValue("s", stranger), Integer.class));
        });
    }

    @Test
    void aMalformedLayoutIsRefusedBeforeAnyLockAndStoresNothing() {
        Inst x = new Inst();
        withService(null, s -> {
            assertThrows(IllegalArgumentException.class,
                    () -> s.stage(x.tenant, x.instance, withLayout(x, x.stateVersion(), "10m", "{\"cache-1\":1}")));
            assertEquals(List.of(), x.generations());
            assertNull(layoutRecord(x));
        });
    }

    @Test
    void aStaleBaseWinsOverIdenticalContent() {
        Inst x = new Inst();
        withService(null, s -> {
            long base = x.stateVersion();
            s.stage(x.tenant, x.instance, x.edit(base, "10m"));

            assertThrows(StaleStateVersionException.class,
                    () -> s.stage(x.tenant, x.instance, x.edit(base, "10m")),
                    "the operator is looking at a stale view; saying 'unchanged' would hide that");
        });
    }

    @Test
    void anUndeclaredInstanceAndAnotherTenantsInstanceAreNotFound() {
        Inst x = new Inst();
        withService(null, s -> {
            assertThrows(NoSuchElementException.class,
                    () -> s.stage(x.tenant, UUID.randomUUID(), x.edit(0, "10m")));
            // I5: the organization is the tenant; another tenant cannot reach this instance by naming its id
            UUID other = SmDataPostgres.freshTenant();
            assertThrows(NoSuchElementException.class, () -> s.stage(other, x.instance, x.edit(x.stateVersion(), "1m")));
            x.stage(s, "10m");
            assertTrue(s.history(other, x.instance, 10).isEmpty());
            assertTrue(s.latestDraft(other, x.instance).isEmpty());
            assertTrue(s.generation(other, x.instance, 1).isEmpty());
        });
    }

    @Test
    void aMalformedBodyIsRefusedBeforeTheInstanceIsLockedAndNeverEchoed() {
        Inst x = new Inst();
        withService(null, s -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> s.stage(x.tenant,
                    x.instance, x.command(x.stateVersion(), "{\"password\": " + SENTINEL, GenerationOrigin.EDIT, null)));
            assertFalse(String.valueOf(e.getMessage()).contains(SENTINEL), "the parser's text is not repeated");
            assertThrows(IllegalArgumentException.class, () -> s.stage(x.tenant, x.instance,
                    x.command(x.stateVersion(), "[1,2]", GenerationOrigin.EDIT, null)));
            assertTrue(x.generations().isEmpty());
        });
    }

    @Test
    void aCopyForwardRecordsItsOriginAndWhereItCameFrom() {
        Inst x = new Inst();
        withService(null, s -> {
            x.stage(s, "10m");
            StageResult rolled = s.stage(x.tenant, x.instance,
                    x.command(x.stateVersion(), "{\"cache-1\":{\"ttl\":\"5m\"}}", GenerationOrigin.ROLLBACK, 1L));

            assertEquals(GenerationOrigin.ROLLBACK, rolled.generation().origin());
            assertEquals(1L, rolled.generation().derivedFromGeneration());
        });
    }

    // ---- retention ------------------------------------------------------------------------------------------------

    /** N comes from the configuration, not a literal: this context is configured with 3. */
    @Test
    void retentionKeepsTheNewestNPlusEveryProtectedGeneration() {
        Inst x = new Inst();
        withService(3, s -> {
            x.stage(s, "1m");
            x.stage(s, "2m");
            // an apply names generation 2: the journal is the DP audit record, so 2 is kept for good
            ScopeKey wide = new ScopeKey(new TenantId(x.tenant), new InstanceId(x.instance), PlacementId.INSTANCE_WIDE);
            assertInstanceOf(AdmissionOutcome.Admitted.class,
                    ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(wide, 2)));
            for (int g = 3; g <= 6; g++) {
                x.stage(s, g + "m");
            }

            assertEquals(List.of(2L, 4L, 5L, 6L), x.generations(),
                    "the newest 3, plus generation 2, which an apply request names");
            assertEquals(6, x.lastGeneration());
            assertEquals(4, s.history(x.tenant, x.instance, 100).size());
        });
    }

    @Test
    void aRetentionBelowOneRefusesTheContextBecauseTheLatestGenerationIsNeverDeleted() {
        new ApplicationContextRunner().withUserConfiguration(Wiring.class)
                .withPropertyValues("sm.dp.config.retained-generations=0")
                .run(ctx -> assertNotNull(ctx.getStartupFailure()));
    }

    @Test
    void theDefaultKeepsTheNewestTen() {
        Inst x = new Inst();
        withService(null, s -> {
            for (int g = 1; g <= 12; g++) {
                x.stage(s, g + "m");
            }
            assertEquals(10, x.generations().size());
            assertEquals(3L, x.generations().get(0));
        });
    }

    @Test
    void retentionRunsInsideStageForThatInstanceOnly() {
        Inst bystander = new Inst();
        // a service that keeps everything fills the bystander instance beyond 3...
        withService(100, s -> {
            for (int g = 1; g <= 6; g++) {
                bystander.stage(s, g + "m");
            }
        });
        assertEquals(6, bystander.generations().size());

        // ...then a service that keeps 3 stages on ANOTHER instance of the SAME tenant
        UUID sibling = UUID.randomUUID();
        InventoryFixtures.declare(bystander.tenant, sibling);
        withService(3, s -> {
            for (int g = 1; g <= 5; g++) {
                s.stage(bystander.tenant, sibling, new StageCommand(currentVersion(bystander.tenant, sibling), "cache",
                        "2.4", SELECTION, "{\"cache-1\":{\"ttl\":\"" + g + "m\"}}",
                        List.of(RenderedEntryValue.plain("cache-1", "p", "\"x\"")), GenerationOrigin.EDIT, null, ACTOR));
            }
        });
        assertEquals(6, bystander.generations().size(), "another instance's rows were not touched");
        assertEquals(3, runtime.queryForObject("SELECT count(*) FROM sm_data.config_generation WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", bystander.tenant)
                .addValue("i", sibling), Integer.class));
    }

    private static long currentVersion(UUID tenant, UUID instance) {
        return runtime.queryForObject("SELECT state_version FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance),
                Long.class);
    }

    // ---- what the database refuses to delete, whatever the configuration says --------------------------------------

    @Test
    void aProtectedGenerationCannotBeDeletedEvenOutsideTheNewestN() {
        Inst x = new Inst();
        withService(100, s -> {
            x.stage(s, "1m");
            x.stage(s, "2m");
            x.stage(s, "3m");
            ScopeKey wide = new ScopeKey(new TenantId(x.tenant), new InstanceId(x.instance), PlacementId.INSTANCE_WIDE);
            assertInstanceOf(AdmissionOutcome.Admitted.class,
                    ApplyEngineHarness.admission().admit(ApplyEngineHarness.apply(wide, 1)));

            var e = Refusals.assertRefused(Refusals.RAISE_EXCEPTION, null, () -> runtime.update(
                    "DELETE FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i AND generation = 1",
                    x.key()));
            assertTrue(e.getMessage().contains("named by an apply request"), e.getMessage());

            // positive control: the same statement for an UNNAMED, non-latest generation succeeds
            assertEquals(1, runtime.update(
                    "DELETE FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i AND generation = 2",
                    x.key()));
            assertEquals(List.of(1L, 3L), x.generations());
        });
    }

    @Test
    void theLatestGenerationCanNeverBeDeleted() {
        Inst x = new Inst();
        withService(null, s -> {
            x.stage(s, "1m");

            var e = Refusals.assertRefused(Refusals.RAISE_EXCEPTION, null, () -> runtime.update(
                    "DELETE FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i", x.key()));
            assertTrue(e.getMessage().contains("latest generation"), e.getMessage());
            assertEquals(List.of(1L), x.generations(), "the floor of one: the Designer can always rebuild");
        });
    }

    @Test
    void theRowIsImmutable() {
        Inst x = new Inst();
        withService(null, s -> {
            x.stage(s, "1m");
            for (String column : List.of("draft_values = '{}'::jsonb", "content_digest = 'sha256:00000000000000000000'",
                    "package_version = 'x'", "state_version = 1")) {
                Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                        "UPDATE sm_data.config_generation SET " + column + " WHERE tenant_id = :t AND instance_id = :i",
                        x.key()), column);
            }
        });
    }

    // ---- I6 and the keyed digest ------------------------------------------------------------------------------------

    @Test
    void aSecretFieldIsStoredAsAReferenceNeverAValue() {
        Inst x = new Inst();
        UUID secret = UUID.randomUUID();
        withService(null, s -> {
            s.stage(x.tenant, x.instance, new StageCommand(x.stateVersion(), "cache", "2.4", SELECTION,
                    "{\"cache-1\":{\"solace.password\":{\"secretRef\":\"" + secret + "\"}}}",
                    List.of(RenderedEntryValue.secret("cache-1", "cache-engine.solace.password", secret),
                            RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"10m\"")),
                    GenerationOrigin.EDIT, null, ACTOR));

            Map<String, Object> row = superuser.queryForMap("SELECT rendered_entries::text AS r, draft_values::text AS d"
                    + " FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i", x.key());
            String rendered = (String) row.get("r");
            assertTrue(rendered.contains(secret.toString()));
            assertTrue(rendered.contains("\"secretRef\""));
            assertFalse(((String) row.get("d")).contains(SENTINEL));
            // entries are sorted by (instance, path): index 0 is the secret, and it has a reference and NO value
            assertEquals(Boolean.TRUE, superuser.queryForObject("SELECT jsonb_exists(rendered_entries -> 0, 'secretRef')"
                    + " FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i", x.key(), Boolean.class));
            assertEquals(Boolean.FALSE, superuser.queryForObject("SELECT jsonb_exists(rendered_entries -> 0, 'value')"
                    + " FROM sm_data.config_generation WHERE tenant_id = :t AND instance_id = :i", x.key(), Boolean.class));
        });
        // the type cannot carry both
        assertThrows(IllegalArgumentException.class, () -> new RenderedEntryValue("c", "p", "\"v\"", secret));
        assertThrows(IllegalArgumentException.class, () -> new RenderedEntryValue("c", "p", null, null));
        assertFalse(RenderedEntryValue.secret("c", "p", secret).toString().contains(secret.toString()));
    }

    @Test
    void theDatabaseRefusesARenderedEntryThatCarriesBothAValueAndAReferenceOrAnImpostorReference() {
        Inst x = new Inst();
        withService(null, s -> x.stage(s, "1m"));
        String insert = "INSERT INTO sm_data.config_generation (tenant_id, instance_id, generation, package_solution,"
                + " package_version, component_selection, draft_values, rendered_entries, content_digest, origin,"
                + " created_by, created_at) VALUES (:t, :i, 99, 'cache', '2.4', '{}', '{}', CAST(:r AS jsonb),"
                + " 'hmacsha256v1:00000000000000000000', 'edit', :u, now())";
        MapSqlParameterSource p = x.key().addValue("u", ACTOR);

        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "rendered_entry_value_xor_secret", () -> runtime.update(insert,
                p.addValue("r", "[{\"instanceKey\":\"a\",\"renderedPath\":\"b\",\"value\":\"" + SENTINEL
                        + "\",\"secretRef\":\"" + UUID.randomUUID() + "\"}]")));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "rendered_secret_ref_is_uuid", () -> runtime.update(insert,
                p.addValue("r", "[{\"instanceKey\":\"a\",\"renderedPath\":\"b\",\"secretRef\":\"" + SENTINEL + "\"}]")));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "rendered_secret_ref_is_uuid", () -> runtime.update(insert,
                p.addValue("r", "[{\"instanceKey\":\"a\",\"renderedPath\":\"b\",\"secretRef\":42}]")));
        // positive control: a reference-only entry and a value-only entry are fine
        assertEquals(1, runtime.update(insert, p.addValue("r", "[{\"instanceKey\":\"a\",\"renderedPath\":\"b\","
                + "\"secretRef\":\"" + UUID.randomUUID() + "\"},{\"instanceKey\":\"a\",\"renderedPath\":\"c\","
                + "\"value\":1}]")));
    }

    @Test
    void historyCarriesNoValueAndAKeyedDigest() {
        Inst x = new Inst();
        withService(null, s -> {
            x.stage(s, "10m");
            StageResult second = s.stage(x.tenant, x.instance, x.command(x.stateVersion(),
                    "{\"cache-1\":{\"solace.host\":\"" + SENTINEL + "\"}}", GenerationOrigin.EDIT, null));

            List<GenerationView> history = s.history(x.tenant, x.instance, 10);
            assertEquals(List.of(2L, 1L), history.stream().map(GenerationView::generation).toList(), "newest first");
            for (GenerationView v : history) {
                assertTrue(v.contentDigest().matches("hmacsha256v1:[0-9a-f]{64}"), v.contentDigest());
                assertFalse(v.toString().contains(SENTINEL));
            }
            assertNotEquals(history.get(0).contentDigest(), history.get(1).contentDigest());
            assertEquals(second.generation().contentDigest(), history.get(0).contentDigest());

            // a keyed digest is not the SHA-256 of anything a holder of it could recompute
            String unkeyed = TestHashers.SHA256.hash(new TenantId(x.tenant),
                    ("{\"cache-1\":{\"solace.host\":\"" + SENTINEL + "\"}}").getBytes());
            assertNotEquals(unkeyed, history.get(0).contentDigest());

            ConfigDraft draft = s.latestDraft(x.tenant, x.instance).orElseThrow();
            assertEquals(2, draft.generation());
            assertTrue(draft.draftValuesJson().contains(SENTINEL), "the Designer re-projects from the stored draft");
            assertFalse(draft.toString().contains(SENTINEL), "but it never renders into a log line");
            assertTrue(s.latestDraft(new Inst().tenant, new Inst().instance).isEmpty());
            assertEquals(1, s.generation(x.tenant, x.instance, 1).orElseThrow().generation());
            assertTrue(s.generation(x.tenant, x.instance, 7).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> s.history(x.tenant, x.instance, 0));
            assertThrows(IllegalArgumentException.class, () -> s.history(x.tenant, x.instance, 501));
        });
    }

    @Test
    void theStateVersionIsStampedByTheDatabaseAndCannotBeSetByTheRuntimeRole() {
        Inst x = new Inst();
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "INSERT INTO sm_data.config_generation (tenant_id, instance_id, generation, package_solution,"
                        + " package_version, component_selection, draft_values, rendered_entries, content_digest,"
                        + " origin, created_by, created_at, state_version) VALUES (:t, :i, 1, 'cache', '2.4', '{}',"
                        + " '{}', '[]', 'hmacsha256v1:00000000000000000000', 'edit', :u, now(), 1)",
                x.key().addValue("u", ACTOR)));
    }

    @Test
    void aGenerationForAnUndeclaredInstanceIsRefusedByName() {
        SmDataPostgres.bootstrap();
        Refusals.assertRefused(Refusals.FOREIGN_KEY_VIOLATION, "config_generation_has_instance", () -> runtime.update(
                "INSERT INTO sm_data.config_generation (tenant_id, instance_id, generation, package_solution,"
                        + " package_version, component_selection, draft_values, rendered_entries, content_digest,"
                        + " origin, created_by, created_at) VALUES (:t, :i, 1, 'cache', '2.4', '{}', '{}', '[]',"
                        + " 'hmacsha256v1:00000000000000000000', 'edit', :u, now())",
                new MapSqlParameterSource().addValue("t", SmDataPostgres.freshTenant())
                        .addValue("i", UUID.randomUUID()).addValue("u", ACTOR)));
    }

    // ---- the head columns and the readiness check -------------------------------------------------------------------

    @Test
    void theHeadColumnsAreRuntimeWritableButTheRestOfInstanceStateIsNot() {
        Inst x = new Inst();
        assertEquals(0, x.lastGeneration());
        assertNull(runtime.queryForObject("SELECT reached_rung FROM sm_data.instance_state WHERE tenant_id = :t"
                + " AND instance_id = :i", x.key(), String.class));
        long before = x.stateVersion();
        assertEquals(1, runtime.update("UPDATE sm_data.instance_state SET reached_rung = 'rendered'"
                + " WHERE tenant_id = :t AND instance_id = :i", x.key()));
        assertTrue(x.stateVersion() > before, "writing the rung stamps instance_state: it is what config-state carries");
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "reached_rung_known", () -> runtime.update(
                "UPDATE sm_data.instance_state SET reached_rung = 'sideways' WHERE tenant_id = :t AND instance_id = :i",
                x.key()));
        Refusals.assertRefused(Refusals.CHECK_VIOLATION, "last_generation_nonneg", () -> runtime.update(
                "UPDATE sm_data.instance_state SET last_generation = -1 WHERE tenant_id = :t AND instance_id = :i",
                x.key()));
        Refusals.assertRefused(Refusals.INSUFFICIENT_PRIVILEGE, null, () -> runtime.update(
                "UPDATE sm_data.instance_state SET declared_package_version = '9' WHERE tenant_id = :t"
                        + " AND instance_id = :i", x.key()));
    }

    @Test
    void theReadinessQueryFindsBothTablesAndBothColumnsAsTheRuntimeRole() {
        assertEquals(4, ConfigPersistenceConfiguration.EXPECTED,
                "config_generation, the two instance_state columns, and V013's designer_layout (OR-96)");
        assertEquals(ConfigPersistenceConfiguration.EXPECTED,
                runtime.getJdbcOperations().queryForObject(ConfigPersistenceConfiguration.READINESS_QUERY, Integer.class));
    }
}
