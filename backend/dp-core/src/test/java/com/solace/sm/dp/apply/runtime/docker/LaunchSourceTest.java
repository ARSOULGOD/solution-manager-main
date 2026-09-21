package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class LaunchSourceTest {

    private static LaunchSource parse(String json) throws Exception {
        return LaunchSource.of(new ObjectMapper().readTree(json));
    }

    /** Cache's real shape: the flag is on the ENTRYPOINT, which is what SM-D21 was written about. */
    @Test
    void anAdditionalLocationOnTheEntrypointIsTheDeclaredLocation() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":["java","-jar","solace-cache-engine.jar",
                  "--spring.config.additional-location=/app/external/spring/config/"],
                  "Cmd":null,"Env":["PATH=/usr/bin"]}}""");
        assertEquals(List.of("/app/external/spring/config/"), s.declaredLocations());
        assertTrue(s.competingLocations().isEmpty());
    }

    /**
     * MEASURED 2026-09-11: Config.Entrypoint is NULL on a container started with a plain command,
     * and Cmd carries the whole line. A parser that read only Entrypoint would see nothing here.
     */
    @Test
    void aNullEntrypointDoesNotHideTheCommand() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":null,
                  "Cmd":["java","-jar","app.jar","--spring.config.additional-location=/conf/"],
                  "Env":[]}}""");
        assertEquals(List.of("/conf/"), s.declaredLocations());
    }

    @Test
    void springConfigLocationOnTheCommandLineIsACompetingSource() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":["java","-jar","app.jar",
                  "--spring.config.additional-location=/conf/","--spring.config.location=/other/"],
                  "Cmd":null,"Env":[]}}""");
        assertEquals(List.of("/conf/"), s.declaredLocations());
        assertEquals(List.of("/other/"), s.competingLocations(),
                "spring.config.location REPLACES the default set; a second writer is exactly what "
                        + "SM-D21's 'detect competing sources' means");
    }

    @Test
    void theEnvironmentFormIsAlsoACompetingSource() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":["java","-jar","app.jar",
                  "--spring.config.additional-location=/conf/"],"Cmd":null,
                  "Env":["SPRING_CONFIG_ADDITIONAL_LOCATION=/elsewhere/"]}}""");
        assertEquals(List.of("/elsewhere/"), s.competingLocations(),
                "the command line outranks the environment (architecture.md §6.1's precedence "
                        + "trap), so the env form is a source SM does not own, not a second declaration");
    }

    @Test
    void anEnvironmentValueIsNeverEchoedIntoTheParseResult() throws Exception {
        LaunchSource s = parse("""
                {"Config":{"Entrypoint":["java","-jar","app.jar"],"Cmd":null,
                  "Env":["SOLACE_PASSWORD=hunter2","PATH=/usr/bin"]}}""");
        assertTrue(s.declaredLocations().isEmpty());
        assertTrue(s.competingLocations().isEmpty(),
                "only the config-location variables are read; every other env entry is untouched "
                        + "and unreported (I6)");
    }
}
