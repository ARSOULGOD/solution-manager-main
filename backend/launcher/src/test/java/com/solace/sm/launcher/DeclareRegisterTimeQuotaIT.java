package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.governance.GovernanceRegistration;
import com.solace.sm.cp.api.governance.InstanceGovernance;
import com.solace.sm.cp.persistence.EntitlementMain;
import com.solace.sm.launcher.DeclareFixtures.Client;
import com.solace.sm.launcher.DeclareFixtures.Login;
import com.solace.sm.launcher.DeclareFixtures.World;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T4.4 / BE-D32 rule 3, the half {@code InstanceEndpointsIT} cannot reach: the BINDING quota inside
 * {@code register}, and the synchronous abandon that follows its refusal.
 *
 * <p>{@code InstanceEndpointsIT}'s over-quota declare is refused by the step-2 pre-check ({@code withinQuota}), so
 * nothing is reserved and the synchronous abandon never runs; deleting {@code abandonQuietly} left that IT green
 * (measured in the S4 review). Here the real {@code GovernanceService} is wrapped so the pre-check always answers
 * {@code true} - exactly what a racing declare that lost the last slot sees - while {@code register} stays real and
 * runs against an exhausted quota. The DP row is therefore reserved first and must be abandoned by the orchestrator
 * itself, not by a later sweep.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DeclareRegisterTimeQuotaIT.PreCheckAlwaysSaysRoom.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeclareRegisterTimeQuotaIT {

    private static TwoSchemaTestDatabase db;
    private static NamedParameterJdbcTemplate ownerJdbc;
    private static World world;
    private static String cpRuntimePassword;
    private static UUID solutionId;

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void provisionAndSeed(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        db = provisioned.db();
        ownerJdbc = db.ownerJdbc;
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
        world = DeclareFixtures.seed(ownerJdbc);
        cpRuntimePassword = provisioned.properties().get("sm.control.runtime.password");
        EntitlementMain.run(db.jdbcUrl, "sm_cp_runtime", cpRuntimePassword, world.organization(), "cache", 1,
                DeclareFixtures.ADMIN);
    }

    /** Wraps the real {@link InstanceGovernance}: the step-2 pre-check lies ("room"), everything else is real. */
    @TestConfiguration
    static class PreCheckAlwaysSaysRoom {
        @Bean
        static BeanPostProcessor preCheckAlwaysSaysRoom() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof InstanceGovernance real)) {
                        return bean;
                    }
                    return new InstanceGovernance() {
                        @Override
                        public GovernanceRegistration register(PrincipalRef actor, RequestOrigin origin,
                                UUID organizationId, UUID environmentId, UUID catalogSolutionId, UUID instanceId) {
                            return real.register(actor, origin, organizationId, environmentId, catalogSolutionId,
                                    instanceId);
                        }

                        @Override
                        public Set<UUID> registered(UUID organizationId, Collection<UUID> instanceIds) {
                            return real.registered(organizationId, instanceIds);
                        }

                        @Override
                        public boolean withinQuota(UUID organizationId, String solution) {
                            return true;
                        }
                    };
                }
            };
        }
    }

    @Test
    @Order(1)
    void theBindingQuotaRefusalAbandonsTheReservedRowSynchronouslyAndFreesEverythingItHeld() throws Exception {
        Client http = new Client(port);
        ObjectNode load = DeclareFixtures.MAPPER.createObjectNode();
        load.set("package", DeclareFixtures.cachePackage());
        HttpResponse<String> loaded = http.post("/api/cp/solutions/load", http.login(DeclareFixtures.ADMIN), load);
        assertEquals(201, loaded.statusCode(), loaded.body());
        solutionId = UUID.fromString(DeclareFixtures.MAPPER.readTree(loaded.body()).get("solutionId").asText());
        Login operator = http.login(DeclareFixtures.OPERATOR_ONE);
        String path = "/api/dp/environments/" + world.envOne() + "/instances";

        HttpResponse<String> first = http.post(path, operator, body("first", "sm-first", 9101));
        assertEquals(201, first.statusCode(), "quota 1 admits the first: " + first.body());
        long cpInstances = count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :t");

        HttpResponse<String> second = http.post(path, operator, body("second", "sm-second", 9102));

        assertEquals(409, second.statusCode(), second.body());
        assertEquals("ENTITLEMENT_REFUSED", DeclareFixtures.MAPPER.readTree(second.body()).get("code").asText());
        assertEquals(1L, count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t AND name = 'second'"
                + " AND lifecycle = 'abandoned'"), "the row WAS reserved, then abandoned by the orchestrator itself");
        assertEquals(0L, count("SELECT count(*) FROM sm_data.instance_state WHERE tenant_id = :t AND lifecycle ="
                + " 'pending'"), "no pending row is left for a later sweep");
        assertEquals(0L, count("SELECT count(*) FROM sm_data.placement p JOIN sm_data.instance_state i ON"
                + " i.tenant_id = p.tenant_id AND i.instance_id = p.instance_id WHERE p.tenant_id = :t AND"
                + " i.name = 'second' AND p.retired_at IS NULL"), "its placements are retired");
        assertEquals(cpInstances, count("SELECT count(*) FROM sm_control.instance WHERE organization_id = :t"),
                "the CP gained no instance node");

        // and the same name, container and port are reusable AT ONCE once the quota is raised
        EntitlementMain.run(db.jdbcUrl, "sm_cp_runtime", cpRuntimePassword, world.organization(), "cache", 2,
                DeclareFixtures.ADMIN);
        HttpResponse<String> retried = http.post(path, operator, body("second", "sm-second", 9102));
        assertEquals(201, retried.statusCode(), retried.body());
    }

    private ObjectNode body(String name, String container, int healthPort) {
        return DeclareFixtures.declareBody(solutionId, world.targetOne(), name, container, healthPort);
    }

    private static long count(String sql) {
        Long n = ownerJdbc.queryForObject(sql, Map.of("t", world.organization()), Long.class);
        return n == null ? 0 : n;
    }
}
