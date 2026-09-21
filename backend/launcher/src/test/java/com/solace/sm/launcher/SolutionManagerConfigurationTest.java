package com.solace.sm.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.apply.engine.ApplyEngineConfiguration;
import com.solace.sm.dp.apply.persistence.ApplyPersistenceConfiguration;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

class SolutionManagerConfigurationTest {

    @Test
    void launcherImportsOnlyTheActualDpConfigurations() {
        Import imported = SolutionManagerConfiguration.class.getAnnotation(Import.class);

        assertTrue(Arrays.asList(imported.value()).contains(ApplyPersistenceConfiguration.class));
        assertTrue(Arrays.asList(imported.value()).contains(ApplyEngineConfiguration.class));
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
