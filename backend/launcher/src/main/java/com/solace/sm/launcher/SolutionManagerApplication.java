package com.solace.sm.launcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Production composition entry point; migrations remain an external deployment concern.
 *
 * <p><b>{@link ErrorMvcAutoConfiguration} is excluded, and this is load-bearing, not
 * tidying.</b> Measured booting this class for real (as {@code ProductionAuthenticationIT} does):
 * with it present, Boot registers {@code BasicErrorController} on {@code RequestMappingHandlerMapping},
 * and {@code OperationCoverageValidator} — console-gateway's own fail-closed startup check —
 * correctly refuses to start, because that handler carries no {@code @ConsoleOperation} and would
 * otherwise be a live, unauthorizable endpoint. The console's error rendering is already complete
 * without it: {@code GatewayErrorAdvice} renders every failure as the one {@code Problem} shape,
 * and unmapped routes are {@code NoRouteHandler}'s own {@code HandlerMapping} — deliberately NOT a
 * {@code RequestMappingHandlerMapping} entry (see its javadoc), so excluding this autoconfiguration
 * removes a redundant, unauthorized error path rather than any real behaviour.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        ErrorMvcAutoConfiguration.class
})
@Import(SolutionManagerConfiguration.class)
public class SolutionManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolutionManagerApplication.class, args);
    }
}
