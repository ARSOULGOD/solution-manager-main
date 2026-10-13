package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.solace.sm.dp.meshbridge.JcsmpEventPublisher;
import com.solace.sm.dp.meshbridge.MeshProperties;
import com.solace.sm.events.publisher.EventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Plan 010 T3.3 / measured fact M4: the production data plane imports the mesh bridge, so EVERY boot reaches
 * {@code MeshProperties}. Booted with no {@code sm.mesh.*} at all - which is what every other launcher IT does, and
 * what a fresh install is - the application must start, with the mesh in its declared degraded state (a dropping
 * publisher, counted), never a refusal. The control plane's catalog emitter must still be fully wired over it.
 */
@SpringBootTest(classes = SolutionManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeshDisabledBootIT {

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void provision(DynamicPropertyRegistry registry) {
        TwoSchemaTestDatabase.Provisioned provisioned = TwoSchemaTestDatabase.provisionStandalone();
        provisioned.properties().forEach((key, value) -> registry.add(key, () -> value));
        registry.add("sm.gateway.auth-rate.capacity", () -> 1000);
        registry.add("sm.gateway.auth-rate.refill-per-minute", () -> 6000);
    }

    @Test
    void everyExistingLauncherBootStillStartsWithNoMeshProperties() {
        MeshProperties props = context.getBean(MeshProperties.class);
        assertFalse(props.enabled(), "sm.mesh.enabled defaults to false");

        JcsmpEventPublisher raw = context.getBean(JcsmpEventPublisher.class);
        assertEquals(0L, raw.droppedCount(), "nothing has been offered yet");
        assertNotNull(context.getBean("cpEventPublisher", EventPublisher.class),
                "the control plane's wrapper is wired over the disabled transport, not absent");
        assertNotNull(context.getBean(com.solace.sm.cp.events.CatalogEventEmitter.class));
    }
}
