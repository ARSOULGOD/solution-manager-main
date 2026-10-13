package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.cp.config.ControlPlaneConfiguration;
import com.solace.sm.dp.DataPlaneConfiguration;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

class SolutionManagerConfigurationTest {

    /**
     * Plan 010 T2.6: the launcher composes the two PLANES, nothing finer. A bean added to the data plane reaches
     * production through {@code DataPlaneConfiguration}, never through a new line here (single-owner file). Plan 015 T3.2 adds the
     * agent-facing gateway beside them: the gateway's own configuration, the three control-plane adapters that feed its
     * data-plane ports, and the boot guard that refuses a half-configured listener.
     */
    @Test
    void theLauncherImportsTheTwoPlaneAggregatorsAndTheAgentGatewayComposition() {
        Import imported = SolutionManagerConfiguration.class.getAnnotation(Import.class);

        assertEquals(Set.of(ControlPlaneConfiguration.class, DataPlaneConfiguration.class,
                        com.solace.sm.agw.AgentGatewayConfiguration.class, AgentGatewayControlPlaneAdapterConfiguration.class,
                        AgentGatewayBootGuard.class),
                Set.copyOf(Arrays.asList(imported.value())),
                "the launcher imports the two plane aggregators and the agent gateway composition, and nothing else");
        assertEquals(5, imported.value().length);
    }

    @Test
    void applicationDisablesBootDatasourceAutoConfiguration() {
        assertTrue(Arrays.asList(SolutionManagerApplication.class
                        .getAnnotation(SpringBootApplication.class)
                        .exclude())
                        .contains(DataSourceAutoConfiguration.class));
        assertTrue(Arrays.asList(SolutionManagerApplication.class
                        .getAnnotation(SpringBootApplication.class)
                        .exclude())
                        .contains(FlywayAutoConfiguration.class));
    }

    /**
     * {@code ErrorMvcAutoConfiguration} registers {@code BasicErrorController} on
     * {@code RequestMappingHandlerMapping} with no {@code @ConsoleOperation} — measured (booting
     * {@code SolutionManagerApplication} for real in {@code ProductionAuthenticationIT} without
     * this exclusion) to fail {@code OperationCoverageValidator} at startup, since that handler
     * would otherwise be a live, unauthorizable endpoint. See the class javadoc.
     */
    @Test
    void applicationDisablesTheDefaultUnauthorizedErrorController() {
        assertTrue(Arrays.asList(SolutionManagerApplication.class
                        .getAnnotation(SpringBootApplication.class)
                        .exclude())
                        .contains(ErrorMvcAutoConfiguration.class));
    }

    @Test
    void launcherDeclaresNoDatasourceOrBusinessExecutionBean() {
        for (Method method : SolutionManagerConfiguration.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Bean.class),
                    () -> "launcher configuration must only import configurations, not declare " + method);
        }
    }

    @Test
    void launcherHasServletWebServerCapabilityWithoutStartingIt() {
        SpringApplication application = new SpringApplication(SolutionManagerApplication.class);

        assertTrue(application.getWebApplicationType() == WebApplicationType.SERVLET);
        assertTrue(ServletWebServerFactory.class.isInterface());
    }
}
