package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.WriterAccess;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SshDockerPlacementTest {

    @Test
    void remoteConfigLocationNeverPretendsTheRemoteDirectoryIsLocal() {
        SshDockerPlacement placement = valid();
        assertInstanceOf(WriterAccess.RemoteSftp.class, placement.location().writerAccess());
    }

    @Test
    void shellBearingContainerNameAndTraversalPathAreRefusedAtTheBinding() {
        assertThrows(IllegalArgumentException.class, () -> new SshDockerPlacement(
                "cache-demo.example", 22, "ec2-user", Path.of("/run/key"),
                "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache;id",
                "/srv/cache/config", "/app/external/spring/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10));
        assertThrows(IllegalArgumentException.class, () -> new SshDockerPlacement(
                "cache-demo.example", 22, "ec2-user", Path.of("/run/key"),
                "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution",
                "/srv/cache/../secrets", "/app/external/spring/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10));
    }

    private static SshDockerPlacement valid() {
        return new SshDockerPlacement("cache-demo.example", 22, "ec2-user", Path.of("/run/key"),
                "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution",
                "/srv/cache/config", "/app/external/spring/config", "application.yml",
                ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
    }
}
