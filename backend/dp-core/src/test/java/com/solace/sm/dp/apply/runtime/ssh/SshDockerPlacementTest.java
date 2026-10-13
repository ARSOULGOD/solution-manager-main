package com.solace.sm.dp.apply.runtime.ssh;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.apply.runtime.ConfigLocation;
import com.solace.sm.dp.apply.runtime.WriterAccess;
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
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "cache-demo.example", 22, "ec2-user", java.util.UUID.randomUUID(), "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache;id", "/srv/cache/config", "/srv/cache/config", "/app/external/spring/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10));
        assertThrows(IllegalArgumentException.class, () -> new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "cache-demo.example", 22, "ec2-user", java.util.UUID.randomUUID(), "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution", "/srv/cache/../secrets", "/srv/cache/../secrets", "/app/external/spring/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10));
    }

    @Test
    void aDirectoryOutsideTheTargetsRootIsRefusedAtTheBinding() {
        for (String directory : new String[] {"/srv/cache", "/srv/sm", "/srv/smx/config", "/home/u/.ssh"}) {
            assertThrows(IllegalArgumentException.class, () -> new SshDockerPlacement(
                    new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "cache-demo.example", 22,
                    "ec2-user", java.util.UUID.randomUUID(), "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=",
                    "cache-solution", "/srv/sm/config", directory, "/app/external/spring/config",
                    "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10), directory);
        }
    }

    @Test
    void theRootItselfAndAnythingBeneathItIsAccepted() {
        for (String directory : new String[] {"/srv/sm/config", "/srv/sm/config/a", "/srv/sm/config/a.b/c-d"}) {
            new SshDockerPlacement(
                    new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "cache-demo.example", 22,
                    "ec2-user", java.util.UUID.randomUUID(), "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=",
                    "cache-solution", "/srv/sm/config", directory, "/app/external/spring/config",
                    "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
        }
    }

    private static SshDockerPlacement valid() {
        return new SshDockerPlacement(
                new com.solace.sm.dp.apply.model.TenantId(java.util.UUID.randomUUID()), "cache-demo.example", 22, "ec2-user", java.util.UUID.randomUUID(), "SHA256:abcdefghijklmnopqrstuvwxABCDEFGH12345678=", "cache-solution", "/srv/cache/config", "/srv/cache/config", "/app/external/spring/config", "application.yml", ConfigLocation.Mechanism.ADDITIONAL_LOCATION, 10);
    }
}
