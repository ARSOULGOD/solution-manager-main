package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.EnrollmentResult;
import com.solace.sm.dp.api.agw.GatewayCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentEnrollmentPort} must do (plan 015 T2.1b, {@code BE-D71}): the issuer is asked first so a refusal writes
 * nothing; the data plane then records the agent's target, identity and one {@code enrolled} event in one transaction; a replay
 * (the agent's retry after a crash between the planes) completes or repeats it without a second row; and the organization is the
 * trusted registration's, since the request names none.
 */
public abstract class AbstractAgentEnrollmentPortContractTest {

    protected abstract AgentKitWorld world();

    @Test
    void anEnrolmentRecordsTheTargetTheIdentityAndOneEnrolledEvent() {
        AgentKitWorld.Enrollment e = world().newEnrollment();
        EnrollmentResult result = world().enrollments().enroll(e.request());
        assertEquals(e.agentId(), result.agentId());
        assertEquals(1, world().targetRows(e));
        assertEquals(1, world().identityRows(e));
        assertEquals(1, world().enrolledEvents(e));
        assertEquals(List.of(result.envelope().allowedConfigRoot(), result.envelope().allowedContainerPrefix()),
                world().storedEnvelope(e), "the envelope is the issuer's (the token's), never the agent's");
    }

    @Test
    void aReplayedEnrolmentReturnsTheSameAnswerAndWritesNoSecondRow() {
        AgentKitWorld.Enrollment e = world().newEnrollment();
        EnrollmentResult first = world().enrollments().enroll(e.request());
        // the agent's retry after a lost reply is a NEW request id carrying the same token and CSR
        EnrollmentResult again = world().enrollments().enroll(world().replayOf(e));
        assertEquals(first.agentId(), again.agentId());
        assertEquals(first.targetId(), again.targetId());
        assertEquals(1, world().targetRows(e));
        assertEquals(1, world().identityRows(e));
        assertEquals(1, world().enrolledEvents(e));
    }

    @Test
    void aRefusedEnrolmentWritesNothing() {
        AgentKitWorld.Enrollment e = world().newEnrollment();
        world().issuerRefuses(e);
        refused(GatewayCode.TOKEN_CONSUMED, () -> world().enrollments().enroll(e.request()));
        assertEquals(0, world().targetRows(e));
        assertEquals(0, world().identityRows(e));
        assertEquals(0, world().enrolledEvents(e));
    }

    @Test
    void theOrganizationIsTheTrustedRegistrationsNeverTheRequests() {
        AgentKitWorld.Enrollment e = world().newEnrollment();
        world().enrollments().enroll(e.request());
        // EnrollmentRequest has no organization field; the stored one can only have come from the registration
        assertEquals(e.tenantId(), world().enrolledTenant(e));
    }

    @Test
    void theEnrolledAgentCanClaimASessionWithItsCertificate() {
        AgentKitWorld.Enrollment e = world().newEnrollment();
        world().enrollments().enroll(e.request());
        world().controlPlaneKnows(e);
        AgentSessionRef session = world().sessions().claim(world().leafThumbprint(e), world().opening(), world().origin());
        assertEquals(e.agentId(), session.agentId());
    }

    @Test
    void whenTheDpHalfFailsNothingIsWrittenAndAReplayCompletesIt() {
        AgentKitWorld.Enrollment e = world().newEnrollment();
        world().occupyThumbprint(e);
        try {
            assertThrows(RuntimeException.class, () -> world().enrollments().enroll(e.request()));
        } finally {
            world().releaseThumbprint(e);
        }
        assertEquals(0, world().targetRows(e), "the target insert was rolled back with the identity's failure");
        assertEquals(0, world().identityRows(e));
        assertEquals(0, world().enrolledEvents(e));
        EnrollmentResult done = world().enrollments().enroll(world().replayOf(e));
        assertEquals(e.agentId(), done.agentId());
        assertEquals(1, world().targetRows(e));
        assertEquals(1, world().identityRows(e));
        assertEquals(1, world().enrolledEvents(e));
    }
}
