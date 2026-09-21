package com.solace.sm.dp.apply.persistence;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Closes the external review's Finding A: without this class, {@code mvn verify} was a false green
 * on any host Testcontainers cannot reach a Docker daemon on.
 *
 * <p>All sixteen {@code sm_data} IT classes in this package carry
 * {@code @Testcontainers(disabledWithoutDocker = true)}. That annotation's {@code ExecutionCondition}
 * calls exactly the check this class calls directly — {@link DockerClientFactory#isDockerAvailable()}
 * — and when it is {@code false}, JUnit reports every {@code @Test} in those sixteen classes as
 * {@code SKIPPED}, not failed. Failsafe's {@code verify} goal only fails the build on a test
 * <i>failure</i>; a skip is not a failure. The measured consequence (external review, 2026-09):
 * {@code mvn -f backend/pom.xml clean verify} exits {@code 0} with every migration, DCL, tenant
 * isolation, fence and transaction-boundary proof this plan rests on silently unexercised — and
 * nothing else in the build says so.
 *
 * <p>This class carries no such annotation, so it always runs, and it turns the missing-Docker
 * condition into a reported <b>failure</b> with an actionable message, rather than a silent skip.
 * It does not touch a container itself — {@link SmDataPostgres} still owns bringing one up — so it
 * costs nothing beyond the one availability probe Testcontainers would have made anyway.
 *
 * <p>The two broker-gated ITs ({@code JcsmpEventPublisherIT}, {@code MeshLifecycleIT}) are
 * deliberately untouched: they gate on {@code @EnabledIfSystemProperty(named = "broker.host", ...)},
 * a live-broker condition this class has nothing to say about, and they must keep reporting
 * {@code SKIPPED} when no broker is configured.
 */
class DockerAvailabilityPreflightIT {

    @Test
    void dockerMustBeReachableOrTheSixteenSmDataItsCannotProveAnything() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            fail(
                    "Docker is not reachable by Testcontainers, so the 16 sm_data integration tests "
                            + "in com.solace.sm.dp.apply.persistence (*IT) are about to report SKIPPED "
                            + "rather than PASSED -- every migration, DCL, tenant-isolation, fence and "
                            + "transaction-boundary proof this module rests on would go unverified while "
                            + "`mvn verify` still reported BUILD SUCCESS. Start a Docker daemon (Docker "
                            + "Desktop, Colima, ...) that Testcontainers can reach -- see "
                            + "https://java.testcontainers.org/on_failure.html and, on a host where "
                            + "~/.testcontainers.properties disagrees with /var/run/docker.sock, "
                            + "backend/README.md's TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE note -- then "
                            + "re-run `mvn -f backend/pom.xml clean verify`. Do not treat a green build "
                            + "as proof of the sm_data layer without Docker present.");
        }
    }
}
