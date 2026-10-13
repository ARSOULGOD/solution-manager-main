package com.solace.sm.dp.apply.runtime.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DockerPropertiesTest {

    private static DockerProperties at(String host) {
        return new DockerProperties(URI.create(host), Duration.ofSeconds(5), Duration.ofSeconds(20), 10);
    }

    @Test
    void theThreeTransportSchemesAreAccepted() {
        assertEquals("unix", at("unix:///var/run/docker.sock").host().getScheme());
        assertEquals("tcp", at("tcp://dockerhost:2375").host().getScheme());
        assertEquals("npipe", at("npipe:////./pipe/docker_engine").host().getScheme());
    }

    @Test
    void anUnknownSchemeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> at("https://dockerhost:2376"));
        assertThrows(IllegalArgumentException.class, () -> at("/var/run/docker.sock"));
    }

    /**
     * I6, the JdbcUrls posture applied to the second location string this backend holds: Docker logs
     * the host it connected to, so a credential in a userinfo segment would reach a log line.
     */
    @Test
    void aHostCarryingACredentialIsRefusedWithoutEchoingIt() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> at("tcp://operator:SENTINELPW@dockerhost:2376"));
        assertFalse(e.getMessage().contains("SENTINELPW"), "the refusal echoed the credential");
        assertFalse(e.getMessage().contains("operator"), "the refusal echoed the user");
    }

    @Test
    void aHostCarryingAQueryOrFragmentIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> at("tcp://dockerhost:2376?tls=0"));
        assertThrows(IllegalArgumentException.class, () -> at("tcp://dockerhost:2376#frag"));
    }
}
