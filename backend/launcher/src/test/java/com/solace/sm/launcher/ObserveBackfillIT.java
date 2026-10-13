package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.ApplyItSupport.Declared;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T6.3, Review Focus 1, end to end: {@code observeApply?sinceStateVersion=} is a BACKFILL, so it is only
 * correct if a row can never commit with a stamp LOWER than one a reader has already seen. Two requests of one
 * instance change state concurrently: the one that drew its stamp first holds the instance row lock until it
 * commits, so the other cannot draw (let alone commit) first - the interleaving this test would otherwise have to
 * tolerate cannot be produced, and a client that saw the higher stamp has therefore seen every lower one.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApplyItSupport.ExecutorConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ObserveBackfillIT {

    private static final ObjectMapper MAPPER = DeclareFixtures.MAPPER;

    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static Declared declared;
    private static UUID requestOne;
    private static UUID requestTwo;
    private static long stampOne;
    private static long stampTwo;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandaloneWithOwnExecutor();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        ownerJdbc = provisioned.db().ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        world = DeclareFixtures.seed(ownerJdbc);
        EntitlementMain.run(provisioned.db().jdbcUrl, "sm_cp_runtime",
                provisioned.properties().get("sm.control.runtime.password"), world.organization(), "cache", 5,
                DeclareFixtures.ADMIN);
    }

    private Client client() {
        return new Client(port);
    }

    private String observe() {
        return "/api/dp/instances/" + declared.instance() + "/apply/observe";
    }

    private JsonNode items(String query, Login login) throws Exception {
        var response = client().get(observe() + query, login);
        assertEquals(200, response.statusCode(), response.body());
        return MAPPER.readTree(response.body()).get("items");
    }

    private void insertRunning(UUID applyId, UUID placementId) {
        ownerJdbc.getJdbcTemplate().update("INSERT INTO sm_data.apply_scope (tenant_id, instance_id) VALUES (?, ?)"
                + " ON CONFLICT DO NOTHING", world.organization(), declared.instance());
        // plan 008 S4 (V024, BE-D48): a request names a placement the registry holds for its instance
        DeclaredInstances.registerPlacement(ownerJdbc, world.organization(), declared.instance(), placementId);
        ownerJdbc.getJdbcTemplate().update("INSERT INTO sm_data.apply_request (apply_id, tenant_id, instance_id,"
                        + " placement_id, operation, generation, strategy, requested_by, requested_at, state)"
                        + " VALUES (?, ?, ?, ?, 'apply', 1, 'manual', 'operator-1', ?, 'RUNNING')",
                applyId, world.organization(), declared.instance(), placementId, Timestamp.from(Instant.now()));
    }

    private long stamp(UUID applyId) {
        return ownerJdbc.queryForObject("SELECT state_version FROM sm_data.apply_request WHERE apply_id = :a",
                Map.of("a", applyId), Long.class);
    }

    @Test
    @Order(1)
    void anInstanceHasTwoRunningRequestsOnTwoPlacements() throws Exception {
        declared = ApplyItSupport.declare(client(), world, "orders-cache", "sm-orders", 9101);
        requestOne = UUID.randomUUID();
        requestTwo = UUID.randomUUID();
        insertRunning(requestOne, UUID.randomUUID());
        insertRunning(requestTwo, UUID.randomUUID());
        assertTrue(stamp(requestOne) < stamp(requestTwo), "stamped in insertion order");
    }

    @Test
    @Order(2)
    void backfillNeverSkipsARowCommittedAfterAHigherStamp() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection first = ownerJdbc.getJdbcTemplate().getDataSource().getConnection();
                Connection second = ownerJdbc.getJdbcTemplate().getDataSource().getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            String land = "UPDATE sm_data.apply_request SET state = 'STAGED', terminal_at = now() WHERE apply_id = ?";

            // A lands request one: it draws a stamp and holds the instance row lock, uncommitted.
            try (PreparedStatement a = first.prepareStatement(land)) {
                a.setObject(1, requestOne);
                assertEquals(1, a.executeUpdate());
            }
            // B lands request two, concurrently. It must queue behind A's lock - it can neither draw a stamp nor
            // commit before A does. This is the interleaving "11 commits before 10" that a bare sequence allows.
            Future<?> landing = pool.submit(() -> {
                try (PreparedStatement b = second.prepareStatement(land)) {
                    b.setObject(1, requestTwo);
                    b.executeUpdate();
                    second.commit();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(700);
            assertFalse(landing.isDone(), "B must be blocked while A holds the instance lock and has not committed");

            first.commit();
            landing.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        stampOne = stamp(requestOne);
        stampTwo = stamp(requestTwo);
        assertTrue(stampOne < stampTwo, "stamp order is commit order: " + stampOne + " then " + stampTwo);

        // A reader that saw A's stamp backfills from it and receives B - and never misses either.
        Login viewer = client().login(DeclareFixtures.VIEWER);
        JsonNode after = items("?sinceStateVersion=" + stampOne, viewer);
        List<String> ids = new ArrayList<>();
        after.forEach(item -> ids.add(item.get("applyId").asText()));
        assertEquals(List.of(requestTwo.toString()), ids, "B is delivered; A (the watermark itself) is not repeated");
        assertEquals(stampTwo, after.get(0).get("stateVersion").asLong());
        assertEquals("STAGED", after.get(0).get("state").asText());
    }

    @Test
    @Order(3)
    void aClientThatMissedEveryEventReachesTheSameStateByObservingAlone() throws Exception {
        Login viewer = client().login(DeclareFixtures.VIEWER);

        JsonNode everything = items("", viewer);

        assertEquals(2, everything.size());
        assertEquals(requestOne.toString(), everything.get(0).get("applyId").asText());
        assertEquals(requestTwo.toString(), everything.get(1).get("applyId").asText());
        assertTrue(everything.get(0).get("terminal").asBoolean() && everything.get(1).get("terminal").asBoolean());
        assertEquals(stampOne, everything.get(0).get("stateVersion").asLong());
    }

    @Test
    @Order(4)
    void aBoundedPageKeepsTheOldestRowsSoRepeatedBackfillAdvancesWithoutSkipping() throws Exception {
        Login viewer = client().login(DeclareFixtures.VIEWER);

        JsonNode page = items("?limit=1", viewer);
        assertEquals(1, page.size());
        assertEquals(requestOne.toString(), page.get(0).get("applyId").asText());
        JsonNode next = items("?limit=1&sinceStateVersion=" + page.get(0).get("stateVersion").asLong(), viewer);
        assertEquals(1, next.size());
        assertEquals(requestTwo.toString(), next.get(0).get("applyId").asText());
        assertEquals(0, items("?sinceStateVersion=" + stampTwo, viewer).size(), "nothing newer than the newest");
    }

    @Test
    @Order(5)
    void observingIsIdempotentAndWritesNothing() throws Exception {
        Login viewer = client().login(DeclareFixtures.VIEWER);
        long before = stamp(requestOne) + stamp(requestTwo);

        JsonNode one = items("", viewer);
        JsonNode two = items("", viewer);

        assertEquals(one, two);
        assertEquals(before, stamp(requestOne) + stamp(requestTwo));
    }
}
