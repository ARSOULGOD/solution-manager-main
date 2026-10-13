package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DockerErrorsTest {

    @Test
    void everyMappedStatusYieldsTheClassifierTheAdapterBranchesOn() {
        assertEquals("docker.container.absent", DockerErrors.classify(404, "inspect").token());
        assertEquals("docker.api.rejected", DockerErrors.classify(400, "inspect").token());
        assertEquals("docker.api.conflict", DockerErrors.classify(409, "restart").token());
        assertEquals("docker.api.failed", DockerErrors.classify(500, "restart").token());
        assertEquals("docker.api.forbidden", DockerErrors.classify(403, "stop").token());
        assertEquals("docker.api.unexpected", DockerErrors.classify(418, "stop").token());
    }

    /**
     * The classifier is the whole message. A Docker error body echoes container names, mount paths
     * and sometimes the command line; none of it may reach a column, a log line or an exception.
     */
    @Test
    void noClassifierCarriesAnythingButLowercaseDottedTokens() {
        for (int status : new int[] {304, 400, 401, 403, 404, 409, 500, 503, 418}) {
            String token = DockerErrors.classify(status, "inspect").token();
            assertTrue(token.matches("^[a-z][a-z0-9]*(\\.[a-z0-9]+)*$"), token);
            assertTrue(token.startsWith("docker."), token);
        }
    }
}
