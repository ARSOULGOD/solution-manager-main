package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.config.GenerationOrigin;
import com.solace.sm.dp.api.config.RenderedEntryValue;
import com.solace.sm.dp.api.config.StageCommand;
import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.PlacementId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.apply.production.ConfigGenerationRenderedConfigSource;
import com.solace.sm.dp.apply.runtime.ConfigHasher;
import com.solace.sm.dp.apply.runtime.PlacementRef;
import com.solace.sm.dp.apply.runtime.RenderedConfig;
import com.solace.sm.dp.apply.runtime.TestHashers;
import com.solace.sm.dp.config.ConfigGenerationRows;
import com.solace.sm.dp.config.ConfigRecordServiceImpl;
import com.solace.sm.dp.config.Retention;
import com.solace.sm.dp.secret.EnvelopeSecretStore;
import com.solace.sm.dp.secret.SecretException;
import com.solace.sm.dp.secret.SecretMaterial;
import com.solace.sm.dp.secret.SecretPurpose;
import com.solace.sm.dp.secret.SecretResolver;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Plan 010 T6.2 against a real PostgreSQL 14 and the real secret store, as {@code sm_dp_runtime}: the worker's renderer
 * reads one generation row and the secret store, resolves a secret only in memory, and renders the same bytes for the
 * same generation from a fresh instance every time. (In the persistence test package: the shared harness is
 * package-private.)
 */
@Testcontainers(disabledWithoutDocker = true)
class ConfigGenerationRenderedConfigSourceIT {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final String SECRET = "BROKER-PASSWORD-SENTINEL";
    private static final String SELECTION = "{\"instances\":[{\"instanceKey\":\"cache-1\",\"type\":\"cache\"}]}";

    private static NamedParameterJdbcTemplate runtime;
    private static EnvelopeSecretStore secrets;
    private static ConfigHasher hasher;

    @BeforeAll
    static void wire() {
        SmDataPostgres.bootstrap();
        runtime = SmDataPostgres.runtimeJdbc();
        secrets = SecretFixtures.store(SecretFixtures.ring(SecretFixtures.key(SecretFixtures.freshKeyId(), 31), null));
        hasher = TestHashers.keyed();
    }

    private static final class Inst {
        final UUID tenant = SmDataPostgres.freshTenant();
        final UUID instance = UUID.randomUUID();
        final UUID placement = UUID.randomUUID();

        Inst() {
            InventoryFixtures.declare(tenant, instance);
        }

        PlacementRef ref() {
            return new PlacementRef(new TenantId(tenant), new InstanceId(instance), new PlacementId(placement));
        }

        UUID secret(String value) {
            return secrets.put(tenant, SecretPurpose.CONFIG_FIELD, value.toCharArray(), ACTOR);
        }

        /** Stages {@code entries} as the next generation and returns its number. */
        long stage(List<RenderedEntryValue> entries) {
            long base = runtime.queryForObject("SELECT state_version FROM sm_data.instance_state"
                    + " WHERE tenant_id = :t AND instance_id = :i", key(), Long.class);
            ConfigRecordServiceImpl service = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                    new JdbcConfigGenerationRepository(runtime), hasher, new Retention(10), Clock.systemUTC(),
                    new JdbcRollbackTargetRepository(runtime));
            return service.stage(tenant, instance, new StageCommand(base, "cache", "2.4", SELECTION,
                    "{\"cache-1\":{\"n\":" + entries.size() + "}}", entries, GenerationOrigin.EDIT, null, ACTOR))
                    .generation().generation();
        }

        MapSqlParameterSource key() {
            return new MapSqlParameterSource().addValue("t", tenant).addValue("i", instance);
        }
    }

    /** A source built the way production builds it, from collaborators this call just made: nothing shared. */
    private static ConfigGenerationRenderedConfigSource freshSource() {
        return new ConfigGenerationRenderedConfigSource(new JdbcConfigGenerationRepository(runtime), secrets, hasher);
    }

    private static List<RenderedEntryValue> entries(UUID secretRef) {
        return new ArrayList<>(List.of(
                RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"5m\""),
                RenderedEntryValue.plain("cache-1", "cache-engine.mode", "\"READ\""),
                RenderedEntryValue.plain("cache-1", "cache-engine.solace.queues", "[\"q2\",\"q1\"]"),
                RenderedEntryValue.secret("cache-1", "cache-engine.solace.password", secretRef),
                RenderedEntryValue.plain("cache-1", "cache-engine.cache-hit-response.template-mappings",
                        "{\"zeta\":1,\"alpha\":2}")));
    }

    // ---- OR-102: the declared non-secret paths the rendered config carries ------------------------------------

    @Test
    void theRenderedConfigNamesEveryPlainPathAndNeverASecretFlaggedOne() {
        Inst a = new Inst();
        long generation = a.stage(entries(a.secret(SECRET)));

        RenderedConfig config = freshSource().render(a.ref(), generation);

        assertEquals(java.util.Set.of("cache-engine.ttl", "cache-engine.mode", "cache-engine.solace.queues",
                "cache-engine.cache-hit-response.template-mappings"), config.nonSecretPaths());
        assertFalse(config.nonSecretPaths().contains("cache-engine.solace.password"),
                "a secret-flagged path is never listed, so it is never digested on its own, by name (I6; the remainder digest covers its value, OR-113 (5))");
    }

    // ---- resume determinism ---------------------------------------------------------------------------------------

    @Test
    void renderIsByteIdenticalAcrossTwoFreshProcessesForTheSameGeneration() {
        Inst a = new Inst();
        long generation = a.stage(entries(a.secret(SECRET)));
        // a second instance of the same tenant whose staged entries arrive in the opposite order: same content
        Inst b = new Inst();
        List<RenderedEntryValue> reversed = entries(b.secret(SECRET));
        Collections.reverse(reversed);
        long otherGeneration = b.stage(reversed);

        RenderedConfig first = freshSource().render(a.ref(), generation);
        RenderedConfig again = freshSource().render(a.ref(), generation);
        RenderedConfig other = freshSource().render(b.ref(), otherGeneration);

        assertArrayEquals(first.bytes(), again.bytes(), "a fresh source renders the same bytes for the same generation");
        assertEquals(first.hash(), again.hash());
        assertArrayEquals(first.bytes(), other.bytes(), "the order entries were staged in does not reach the bytes");
        String yaml = new String(first.bytes(), StandardCharsets.UTF_8);
        assertTrue(yaml.contains(SECRET), "the secret reference was resolved into the rendered bytes: " + yaml.length());
        assertFalse(yaml.contains("secretRef"), "a reference is never written to the host");
        assertTrue(yaml.indexOf("cache-hit-response") < yaml.indexOf("solace"), "sorted keys");
        assertTrue(yaml.indexOf("alpha") < yaml.indexOf("zeta"), "sorted inside a value too");
    }

    // ---- OR-103 / BE-D37: the overlay survives the database --------------------------------------------------------

    @Test
    void theOverlayAStageWroteIsRenderedFromTheStoredRowAndAnUndeclaringStageRendersNone() {
        Inst x = new Inst();
        long base = runtime.queryForObject("SELECT state_version FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", x.key(), Long.class);
        ConfigRecordServiceImpl service = new ConfigRecordServiceImpl(ApplyEngineHarness.transactions(),
                new JdbcConfigGenerationRepository(runtime), hasher, new Retention(10), Clock.systemUTC(),
                    new JdbcRollbackTargetRepository(runtime));
        List<RenderedEntryValue> fields = List.of(RenderedEntryValue.plain("cache-1", "cache-engine.ttl", "\"5m\""));

        long declared = service.stage(x.tenant, x.instance, new StageCommand(base, "cache", "2.4", SELECTION, "{}",
                fields, GenerationOrigin.EDIT, null, ACTOR, null,
                new com.solace.sm.dp.api.config.LoggerSupport(List.of("metrics", "health", "info"))))
                .generation().generation();
        long next = runtime.queryForObject("SELECT state_version FROM sm_data.instance_state"
                + " WHERE tenant_id = :t AND instance_id = :i", x.key(), Long.class);
        long undeclared = service.stage(x.tenant, x.instance, new StageCommand(next, "cache", "2.4", SELECTION,
                "{\"x\":1}", fields, GenerationOrigin.EDIT, null, ACTOR)).generation().generation();

        String withOverlay = new String(freshSource().render(x.ref(), declared).bytes(), StandardCharsets.UTF_8);
        String without = new String(freshSource().render(x.ref(), undeclared).bytes(), StandardCharsets.UTF_8);

        assertTrue(withOverlay.contains("include: \"health,info,loggers,metrics\""), withOverlay);
        assertTrue(withOverlay.contains("access: \"read-only\""), withOverlay);
        assertFalse(withOverlay.contains("server:"), "no management port or address: the actuator does not move");
        assertTrue(withOverlay.contains("ttl: \"5m\""), withOverlay);
        assertFalse(without.contains("management"), without);
    }

    // ---- exactly two collaborators --------------------------------------------------------------------------------

    @Test
    void renderReadsOnlyTheGenerationRowAndTheSecretStore() {
        Inst x = new Inst();
        UUID ref = x.secret(SECRET);
        long generation = x.stage(entries(ref));
        List<String> rowCalls = new ArrayList<>();
        List<String> secretCalls = new ArrayList<>();
        ConfigGenerationRows real = new JdbcConfigGenerationRepository(runtime);
        ConfigGenerationRows spiedRows = (ConfigGenerationRows) Proxy.newProxyInstance(
                ConfigGenerationRows.class.getClassLoader(), new Class<?>[] {ConfigGenerationRows.class},
                (proxy, method, args) -> {
                    rowCalls.add(method.getName());
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        SecretResolver spiedSecrets = (tenantId, secretId, purpose) -> {
            secretCalls.add(tenantId.equals(x.tenant) + ":" + secretId.equals(ref) + ":" + purpose);
            return secrets.resolve(tenantId, secretId, purpose);
        };

        new ConfigGenerationRenderedConfigSource(spiedRows, spiedSecrets, hasher).render(x.ref(), generation);

        assertEquals(List.of("renderedEntries"), rowCalls, "the generation row is read, once, and nothing else of the DP");
        assertEquals(List.of("true:true:CONFIG_FIELD"), secretCalls,
                "one secret, for the placement's own tenant, as a config field");
    }

    @Test
    void theRendererIsBuiltFromExactlyTwoReadersAndAHasherSoItCannotReachThePlacementTable() {
        assertEquals(List.of(ConfigGenerationRows.class, SecretResolver.class, ConfigHasher.class),
                List.of(ConfigGenerationRenderedConfigSource.class.getConstructors()[0].getParameterTypes()));
    }

    // ---- I6 -------------------------------------------------------------------------------------------------------

    @Test
    void aSecretRefIsResolvedOnlyInMemoryAndNeverLogged() {
        Inst x = new Inst();
        UUID ref = x.secret(SECRET);
        // a leaf beneath the secret's own path: the render fails AFTER the secret was resolved
        long poisoned = x.stage(List.of(
                RenderedEntryValue.secret("cache-1", "cache-engine.solace.password", ref),
                RenderedEntryValue.plain("cache-1", "cache-engine.solace.password.extra", "\"x\"")));
        long good = x.stage(entries(ref));
        List<SecretMaterial> opened = new ArrayList<>();
        SecretResolver recording = (tenantId, secretId, purpose) -> {
            SecretMaterial material = secrets.resolve(tenantId, secretId, purpose);
            opened.add(material);
            return material;
        };
        ConfigGenerationRenderedConfigSource source = new ConfigGenerationRenderedConfigSource(
                new JdbcConfigGenerationRepository(runtime), recording, hasher);

        LogCapture.Captured<Throwable> failed = LogCapture.during(() -> {
            try {
                source.render(x.ref(), poisoned);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        LogCapture.Captured<RenderedConfig> succeeded = LogCapture.during(() -> source.render(x.ref(), good));

        assertNotNull(failed.value(), "a path claimed twice fails the render");
        assertNoSecret(failed.value());
        assertFalse(failed.text().contains(SECRET), "nothing the JVM logged carries the resolved value");
        assertFalse(succeeded.text().contains(SECRET));
        assertFalse(succeeded.value().toString().contains(SECRET));
        assertEquals(2, opened.size());
        assertTrue(opened.stream().allMatch(SecretMaterial::isClosed),
                "every resolved secret was closed, which zeroes its buffer, on the failing path and the passing one");
    }

    private static void assertNoSecret(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            assertFalse(String.valueOf(c.getMessage()).contains(SECRET), c.getClass().getName());
            assertFalse(c.toString().contains(SECRET));
        }
        assertNull(t.getCause(), "no cause chain for a logger to print");
    }

    // ---- failure --------------------------------------------------------------------------------------------------

    @Test
    void aMissingSecretFailsTheRender() {
        Inst x = new Inst();
        long generation = x.stage(entries(UUID.randomUUID()));

        assertThrows(SecretException.class, () -> freshSource().render(x.ref(), generation));
    }

    @Test
    void anotherTenantsSecretIsNotResolvedForThisTenant() {
        Inst owner = new Inst();
        UUID foreign = owner.secret(SECRET);
        Inst x = new Inst();
        long generation = x.stage(entries(foreign));

        SecretException e = assertThrows(SecretException.class, () -> freshSource().render(x.ref(), generation));
        assertNoSecret(e);
    }

    @Test
    void aGenerationTheInstanceDoesNotHaveFailsTheRender() {
        Inst x = new Inst();
        x.stage(entries(x.secret(SECRET)));
        Inst other = new Inst();
        long others = other.stage(entries(other.secret(SECRET)));

        assertThrows(IllegalStateException.class, () -> freshSource().render(x.ref(), 99));
        // the same generation NUMBER of another tenant's instance is not this placement's generation
        assertThrows(IllegalStateException.class, () -> freshSource().render(
                new PlacementRef(new TenantId(x.tenant), new InstanceId(other.instance), new PlacementId(x.placement)),
                others));
    }
}
