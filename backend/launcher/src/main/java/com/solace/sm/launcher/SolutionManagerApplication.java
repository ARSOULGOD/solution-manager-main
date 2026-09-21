package com.solace.sm.launcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Production composition entry point; migrations remain an external deployment concern. */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        FlywayAutoConfiguration.class
})
@Import(SolutionManagerConfiguration.class)
public class SolutionManagerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SolutionManagerApplication.class, args);
    }
}
