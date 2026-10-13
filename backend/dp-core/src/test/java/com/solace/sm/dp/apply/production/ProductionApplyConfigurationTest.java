package com.solace.sm.dp.apply.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyExecutor;
import com.solace.sm.dp.apply.runtime.RuntimeAdapter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Plan 010 T6.2: the one switch, declared the way the seven worker ITs and production both rely on. */
class ProductionApplyConfigurationTest {

    @Test
    void theSwitchDefaultsToOnSoProductionSetsNothing() {
        ConditionalOnProperty switchDeclaration =
                ProductionApplyConfiguration.class.getAnnotation(ConditionalOnProperty.class);

        assertNotNull(switchDeclaration);
        assertEquals("sm.dp.apply.production.enabled", switchDeclaration.name()[0]);
        assertEquals("true", switchDeclaration.havingValue());
        assertTrue(switchDeclaration.matchIfMissing(), "absent property means ON");
    }

    @Test
    void withTheSwitchOffNothingOfItIsRegistered() {
        new ApplicationContextRunner().withUserConfiguration(ProductionApplyConfiguration.class)
                .withPropertyValues("sm.dp.apply.production.enabled=false").run(ctx -> {
                    assertNull(ctx.getStartupFailure(), "off needs none of the collaborators it would otherwise wire");
                    assertEquals(0, ctx.getBeansOfType(ApplyExecutor.class).size());
                    assertEquals(0, ctx.getBeansOfType(RuntimeAdapter.class).size());
                });
    }

    @Test
    void withTheSwitchAbsentTheConfigurationIsRegisteredAndAsksForItsCollaborators() {
        new ApplicationContextRunner().withUserConfiguration(ProductionApplyConfiguration.class).run(ctx -> {
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure, "registered: its beans were instantiated and found their collaborators missing");
            Throwable root = failure;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            assertTrue(root instanceof NoSuchBeanDefinitionException, root.toString());
        });
    }

    private static List<Method> beanMethods() {
        return Arrays.stream(ProductionApplyConfiguration.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class)).toList();
    }

    /**
     * Plan 015 T3.1, global constraint 5: dp-remote and agent placements run in ONE process through ONE {@code ApplyExecutor} and
     * ONE {@code RuntimeAdapter} bean (the router). A second executor would make the worker's {@code ObjectProvider} ambiguous; a
     * second adapter bean would let something inject "the SSH adapter" and bypass the routing.
     */
    @Test
    void exactlyOneApplyExecutorAndOneRuntimeAdapterBeanAreDeclared() {
        assertEquals(1, beanMethods().stream().filter(m -> ApplyExecutor.class.isAssignableFrom(m.getReturnType())).count());
        List<Method> adapters = beanMethods().stream()
                .filter(m -> RuntimeAdapter.class.isAssignableFrom(m.getReturnType())).toList();
        assertEquals(1, adapters.size(), "every adapter is built inside the one router bean: " + adapters);
        assertEquals(RuntimeAdapter.class, adapters.get(0).getReturnType(),
                "the bean's declared type is the seam, never a concrete adapter");
    }

    @Test
    void thereIsNoGlobalAgentSwitch() {
        // the branch's sm.dp.agent.runtime.enabled and its second executor are not ported (global constraint 5)
        for (Method m : beanMethods()) {
            assertNull(m.getAnnotation(ConditionalOnProperty.class), "no per-bean switch: " + m);
        }
        assertEquals(ProductionApplyConfiguration.ENABLED,
                ProductionApplyConfiguration.class.getAnnotation(ConditionalOnProperty.class).name()[0]);
    }
}
