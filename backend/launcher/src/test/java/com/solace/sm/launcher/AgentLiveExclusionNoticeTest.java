package com.solace.sm.launcher;

import org.junit.jupiter.api.Test;

/**
 * Says, in the build log of an ordinary build, that the real-agent suites were not run, so their absence is never silent
 * (plan 015 T4.4 step 2). The {@code agent-live} profile hands surefire the same {@code sm.agent.live} property it hands failsafe, so
 * under {@code -Pagent-live} this test prints that the suites are INCLUDED, which is how the profile reaching the JVM shows in the log.
 * It asserts nothing: the live classes' own {@code AgentLive#require} is what fails closed.
 */
class AgentLiveExclusionNoticeTest {

    @Test
    void theOrdinaryBuildSaysThatTheRealAgentSuitesAreExcluded() {
        if (AgentLive.profileActive()) {
            System.out.println("[agent-live] INCLUDED in this build: the real-agent suites run under failsafe and fail, never skip, on a missing prerequisite.");
        } else {
            System.out.println("[agent-live] EXCLUDED from this build: RealAgentDockerE2EIT, AgentFailureMatrixIT, AgentLeaseExpiryIT, "
                    + "AgentFleetDrainIT, ExtraCommandUnderCpuPressureIT, AgentCapacityIT, ContainerPackagingIT, "
                    + "AgentRuntimeAdapterRealAgentContractIT. They need go, Docker, " + AgentLive.ALPINE + " and "
                    + AgentLive.CACHE_IMAGE + "; run them with: mvn -o -f backend/pom.xml -pl launcher -am -Pagent-live verify");
        }
    }
}
