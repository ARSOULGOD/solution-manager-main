package com.solace.sm.dp.api.agw.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentDrainPort;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@link AgentDrainPort} must do (plan 015 T4.3, AGW-D18, AGW-D2's "every in-process port has contract tests a future
 * remote adapter must pass"). The port reaches only the sessions the implementation holds (single replica, AGW-D32); a remote adapter
 * must route to the replica that holds the session and pass the same cases.
 *
 * <p>The port takes no tenant (the caller resolves the agent inside the principal's organization first, I5), so there is no
 * foreign-tenant case: tenant isolation is the caller's, proven where the id is resolved ({@code AgentEndpointsIT}).
 *
 * <p>An implementation supplies the hooks below; each agent is named by a test-chosen label.
 */
public abstract class AbstractAgentDrainPortContractTest {

    /** A {@code Drain} an agent received. */
    public record DrainNotice(Duration reconnectAfter, String reason) { }

    protected abstract AgentDrainPort port();

    /** Opens a live, accepted session for a NEW agent known by {@code label}, and returns its id. */
    protected abstract UUID connect(String label);

    /** The agent {@code label} opens another session (the first one is superseded or already gone). */
    protected abstract void reconnect(String label);

    /** The agent id a never-connected agent would have. */
    protected abstract UUID unknownAgent();

    /** Every {@code Drain} the agent {@code label} has received on any of its sessions, in order. */
    protected abstract List<DrainNotice> noticesReceivedBy(String label);

    /** Whether the agent {@code label} has a live accepted session now. */
    protected abstract boolean connected(String label);

    /** Lets the drain window end (the force-close deadline passes) and any close it causes complete. */
    protected abstract void drainWindowPasses();

    /** The {@code reconnect_after} the implementation is configured to tell an agent. */
    protected abstract Duration configuredReconnectAfter();

    private static final String REASON = "operator-drain";

    @Test
    void anAgentWithNoLiveSessionSendsNothingAndIsEmpty() {
        connect("bystander");
        assertEquals(Optional.empty(), port().drain(unknownAgent(), REASON));
        assertTrue(noticesReceivedBy("bystander").isEmpty(), "nothing was sent to anyone");
    }

    @Test
    void aConnectedAgentIsToldToDrainWithReconnectAfterAndTheReasonAndNobodyElseIs() {
        UUID target = connect("target");
        connect("bystander");
        assertEquals(Optional.of(configuredReconnectAfter()), port().drain(target, REASON));
        assertEquals(List.of(new DrainNotice(configuredReconnectAfter(), REASON)), noticesReceivedBy("target"));
        assertTrue(noticesReceivedBy("bystander").isEmpty());
        assertTrue(connected("bystander"));
    }

    @Test
    void aRepeatedDrainWhileDrainingSendsNothingAndIsEmpty() {
        UUID target = connect("target");
        assertTrue(port().drain(target, REASON).isPresent());
        assertEquals(Optional.empty(), port().drain(target, REASON), "nothing was sent the second time");
        assertEquals(1, noticesReceivedBy("target").size());
    }

    @Test
    void aReasonThatIsNotAShortTokenIsRefusedAndNothingIsSent() {
        UUID target = connect("target");
        for (String bad : new String[] {null, "", "Operator Drain", "x".repeat(64), "a;b"}) {
            assertThrows(IllegalArgumentException.class, () -> port().drain(target, bad), String.valueOf(bad));
        }
        assertTrue(noticesReceivedBy("target").isEmpty());
    }

    @Test
    void aSessionThatDoesNotAnswerIsClosedWhenTheWindowEndsAndOnlyThatOne() {
        UUID target = connect("target");
        connect("bystander");
        assertTrue(port().drain(target, REASON).isPresent());
        drainWindowPasses();
        assertFalse(connected("target"), "the straggler is cut, never left hanging");
        assertTrue(connected("bystander"), "the force-close acts on the drained session only");
    }

    @Test
    void anAgentThatReconnectsInsideTheWindowKeepsItsNewSession() {
        UUID target = connect("target");
        assertTrue(port().drain(target, REASON).isPresent());
        reconnect("target");
        drainWindowPasses();
        assertTrue(connected("target"), "the old session's deadline does not close the new one");
        assertEquals(1, noticesReceivedBy("target").size(), "and the new session was never told to drain");
    }
}
