package com.solace.sm.dp.api.agw.kit;

import static com.solace.sm.dp.api.agw.kit.KitAsserts.refused;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.agw.AgentAck;
import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentCommandIntent;
import com.solace.sm.dp.api.agw.AgentCommandRef;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentOutcome;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.CommandAckResult;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.CommandNotice;
import com.solace.sm.dp.api.agw.CommandOffer;
import com.solace.sm.dp.api.agw.GatewayCode;
import com.solace.sm.dp.api.agw.LeaseGrant;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What every {@code AgentCommandPort} must do (plan 015 T2.1b, {@code BE-D66}, {@code AGW-D23}..{@code AGW-D26}):
 * a command is keyed on the attempt-free intent key and operation; {@code enqueue} returns the live or the succeeded command for
 * that key rather than minting a second; {@code supersedeOutstanding} touches queued and offered commands of OTHER keys only; a
 * stale session is refused before any read or write; the organization is the identity's; and SM stores NO rendered byte and no
 * plain digest of one, whatever it hands over at {@code pull}.
 */
public abstract class AbstractAgentCommandPortContractTest {

    private static final Duration RENOTIFY = Duration.ofSeconds(15);

    protected abstract AgentKitWorld world();

    private static String key() {
        return UUID.randomUUID() + ":APPLY:r0";
    }

    /** A key unique to the scenario: an intent's idempotency key is unique across the whole journal. */
    private static String key(String tail) {
        return UUID.randomUUID() + ":" + tail;
    }

    private AgentCommandRef enqueue(AgentKitWorld.Agent agent, String key, AgentOperation operation) {
        return world().commands().enqueue(world().intent(agent, key, operation));
    }

    private static AgentAck ack(String requestId, long generation, AgentOutcome outcome) {
        return new AgentAck(requestId, generation, outcome, "", "", null);
    }

    /** Offered, announced received, and returned as the one notice. */
    private CommandNotice offer(AgentSessionRef session) {
        List<CommandNotice> notices = world().commands().pendingFor(session, 10, RENOTIFY);
        assertEquals(1, notices.size());
        return notices.get(0);
    }

    // ---- the plan's named cases ---------------------------------------------------------------------------------

    @Test
    void enqueueReturnsTheLiveCommandForTheSameIntentKey() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentCommandIntent intent = world().intent(agent, key(), AgentOperation.APPLY);
        AgentCommandRef first = world().commands().enqueue(intent);
        AgentCommandRef again = world().commands().enqueue(intent);
        assertTrue(first.minted());
        assertFalse(again.minted());
        assertEquals(first.commandId(), again.commandId());
        assertEquals(1, world().rows("agent_command", agent));
        assertEquals(agent.tenantId(), world().storedTenant("agent_command", agent), "the placement's organization");
    }

    @Test
    void enqueueReturnsTheSucceededCommandRatherThanMintingAnother() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandIntent intent = world().intent(agent, key(), AgentOperation.APPLY);
        AgentCommandRef first = world().commands().enqueue(intent);
        offer(session);
        world().commands().received(session, "recv-" + UUID.randomUUID(), first.commandId(), first.generation());
        assertEquals(CommandAckResult.COMMITTED, world().commands().ack(session, first.commandId(),
                ack("ack-1", first.generation(), AgentOutcome.APPLIED)));
        AgentCommandRef again = world().commands().enqueue(intent);
        assertEquals(first.commandId(), again.commandId());
        assertFalse(again.minted());
        assertEquals(1, world().rows("agent_command", agent));
    }

    @Test
    void aRetryAfterAFailedAckMintsANewGeneration() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandIntent intent = world().intent(agent, key(), AgentOperation.APPLY);
        AgentCommandRef first = world().commands().enqueue(intent);
        offer(session);
        world().commands().ack(session, first.commandId(), ack("ack-f", first.generation(), AgentOutcome.FAILED_NO_EFFECT));
        AgentCommandRef retry = world().commands().enqueue(intent);
        assertTrue(retry.minted());
        assertNotEquals(first.commandId(), retry.commandId());
        assertEquals(first.generation() + 1, retry.generation(), "the agent's journal refuses any generation not above its highest");
        assertEquals(2, world().rows("agent_command", agent));
    }

    @Test
    void supersedeLeavesDeliveredCommandsAlone() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef held = enqueue(agent, key(), AgentOperation.APPLY);
        offer(session);
        world().commands().received(session, "recv-" + UUID.randomUUID(), held.commandId(), held.generation());
        String keep = key("keep");
        enqueue(agent, keep, AgentOperation.RESTART);
        world().commands().supersedeOutstanding(agent.placement(), keep);
        assertEquals("DELIVERED", world().commandState(held.commandId()));
    }

    @Test
    void pullStoresNothing() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        CommandNotice notice = offer(session);
        byte[] bytes = "spring:\n  profiles: secret-looking-rendered-config\n".getBytes(StandardCharsets.UTF_8);
        String plain = HexFormat.of().formatHex(UUID.randomUUID().toString().repeat(2).substring(0, 32).getBytes(StandardCharsets.UTF_8));
        world().payload(ref.commandId(), bytes, plain);
        String before = world().commandFingerprint(ref.commandId());
        CommandOffer offer = world().commands().pull(session, notice.commandId());
        assertEquals(new String(bytes, StandardCharsets.UTF_8), new String(offer.payload().bytes(), StandardCharsets.UTF_8));
        assertEquals(before, world().commandFingerprint(ref.commandId()), "a pull writes nothing: same values, same physical size");
        assertFalse(world().anyAgentRowContains(agent, "secret-looking-rendered-config"));
        assertFalse(world().anyAgentRowContains(agent, plain), "no plain digest of rendered bytes either (OR-32)");
    }

    // ---- identity of a command ---------------------------------------------------------------------------------------

    @Test
    void oneLiveCommandPerIntentKeyAndOperation() {
        AgentKitWorld.Agent agent = world().enrol();
        String key = key();
        AgentCommandRef preflight = enqueue(agent, key, AgentOperation.PREFLIGHT);
        AgentCommandRef apply = enqueue(agent, key, AgentOperation.APPLY);
        assertNotEquals(preflight.commandId(), apply.commandId(), "two operations on one key are two commands");
        assertEquals(preflight.commandId(), enqueue(agent, key, AgentOperation.PREFLIGHT).commandId());
        assertEquals(apply.commandId(), enqueue(agent, key, AgentOperation.APPLY).commandId());
        assertEquals(2, world().rows("agent_command", agent));
        assertEquals(preflight.generation() + 1, apply.generation(), "generations ascend per placement across operations");
    }

    @Test
    void aPlainConfigHashIsRefusedAtEnqueueAndNothingIsStored() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentCommandIntent good = world().intent(agent, key(), AgentOperation.APPLY);
        AgentCommandIntent plain = new AgentCommandIntent(good.agentId(), good.placement(), good.intentKey(), good.operation(),
                "sha256:" + "a".repeat(64), good.deadline(), good.correlation(), null);
        assertThrows(IllegalArgumentException.class, () -> world().commands().enqueue(plain));
        assertEquals(0, world().rows("agent_command", agent));
    }

    @Test
    void supersedeTouchesOnlyQueuedOrOfferedCommandsOfOtherKeys() {
        AgentKitWorld.Agent agent = world().enrol();
        UUID otherPlacement = world().addPlacement(agent);
        AgentSessionRef session = world().ready(agent);
        String keepKey = key("keep");
        AgentCommandRef offered = enqueue(agent, key("offered"), AgentOperation.APPLY);
        offer(session);                                                     // becomes OFFERED
        AgentCommandRef queued = enqueue(agent, key("queued"), AgentOperation.APPLY);
        AgentCommandRef keep = enqueue(agent, keepKey, AgentOperation.RESTART);
        AgentCommandRef elsewhere = world().commands().enqueue(
                world().intentOn(agent, otherPlacement, key("elsewhere"), AgentOperation.APPLY));
        world().commands().supersedeOutstanding(agent.placement(), keepKey);
        assertEquals("SUPERSEDED", world().commandState(offered.commandId()));
        assertEquals("SUPERSEDED", world().commandState(queued.commandId()));
        assertEquals("QUEUED", world().commandState(keep.commandId()), "the key to keep is untouched");
        assertEquals("QUEUED", world().commandState(elsewhere.commandId()), "another placement is untouched");
        refused(GatewayCode.SUPERSEDED, () -> world().commands().pull(session, offered.commandId()));
        assertEquals(AgentOutcome.SUPERSEDED, world().commands().completion(agent.placement(), offered.commandId()).orElseThrow().outcome());
    }

    // ---- delivery ----------------------------------------------------------------------------------------------------

    @Test
    void pendingForOffersEachCommandOnceAndRenotifiesOnlyAfterTheInterval() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        assertEquals(1, world().commands().pendingFor(session, 10, RENOTIFY).size());
        assertEquals("OFFERED", world().commandState(ref.commandId()));
        assertEquals(0, world().commands().pendingFor(session, 10, RENOTIFY).size(), "just told");
        world().advance(RENOTIFY.plusSeconds(1));
        assertEquals(1, world().commands().pendingFor(session, 10, RENOTIFY).size(), "reminded after the interval");
        AgentSessionRef reconnected = world().open(agent);
        assertEquals(1, world().commands().pendingFor(reconnected, 10, RENOTIFY).size(), "a new session is told at once");
    }

    @Test
    void receivedAndAckWalkTheCommandToItsOutcomeAndAckIsIdempotentByRequestId() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        offer(session);
        world().commands().received(session, "recv-" + UUID.randomUUID(), ref.commandId(), ref.generation());
        world().commands().received(session, "recv-" + UUID.randomUUID(), ref.commandId(), ref.generation());       // a duplicate announcement is not an error
        assertEquals("DELIVERED", world().commandState(ref.commandId()));
        assertFalse(world().commands().completion(agent.placement(), ref.commandId()).orElseThrow().terminal());
        assertEquals(CommandAckResult.COMMITTED, world().commands().ack(session, ref.commandId(),
                ack("ack-1", ref.generation(), AgentOutcome.APPLIED)));
        assertEquals(CommandAckResult.DUPLICATE, world().commands().ack(session, ref.commandId(),
                ack("ack-1", ref.generation(), AgentOutcome.APPLIED)));
        var completion = world().commands().completion(agent.placement(), ref.commandId()).orElseThrow();
        assertTrue(completion.terminal());
        assertEquals(AgentOutcome.APPLIED, completion.outcome());
        assertEquals("ACKED", world().commandState(ref.commandId()));
    }

    @Test
    void anAckForAnotherGenerationOrAnUnknownCommandChangesNothing() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        offer(session);
        assertEquals(CommandAckResult.UNKNOWN_COMMAND, world().commands().ack(session, ref.commandId(),
                ack("ack-g", ref.generation() + 5, AgentOutcome.APPLIED)));
        assertEquals(CommandAckResult.UNKNOWN_COMMAND, world().commands().ack(session, new CommandId(UUID.randomUUID()),
                ack("ack-u", 1, AgentOutcome.APPLIED)));
        assertEquals("OFFERED", world().commandState(ref.commandId()));
        refused(GatewayCode.STALE_GENERATION, () -> world().commands().received(session, "recv-" + UUID.randomUUID(), ref.commandId(), ref.generation() + 5));
    }

    @Test
    void aStaleSessionIsRefusedBeforeAnyReadOrWrite() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef stale = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        CommandNotice notice = offer(stale);
        world().commands().received(stale, "recv-" + UUID.randomUUID(), ref.commandId(), ref.generation());
        AgentSessionRef current = world().open(agent);
        String before = world().commandFingerprint(ref.commandId());
        refused(GatewayCode.STALE_EPOCH, () -> world().commands().pendingFor(stale, 10, RENOTIFY));
        refused(GatewayCode.STALE_EPOCH, () -> world().commands().pull(stale, notice.commandId()));
        refused(GatewayCode.STALE_EPOCH, () -> world().commands().received(stale, "recv-" + UUID.randomUUID(), ref.commandId(), ref.generation()));
        refused(GatewayCode.STALE_EPOCH, () -> world().commands().ack(stale, ref.commandId(),
                ack("ack-stale", ref.generation(), AgentOutcome.APPLIED)));
        assertEquals(before, world().commandFingerprint(ref.commandId()), "nothing was written by the superseded stream");
        assertEquals("DELIVERED", world().commandState(ref.commandId()));
        assertEquals(agent.agentId(), current.agentId());
    }

    @Test
    void pendingForAndPullNeedAnActiveLease() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);                       // no lease
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        refused(GatewayCode.LEASE_RENEW_REQUIRED, () -> world().commands().pendingFor(session, 10, RENOTIFY));
        refused(GatewayCode.LEASE_RENEW_REQUIRED, () -> world().commands().pull(session, ref.commandId()));
        assertEquals("QUEUED", world().commandState(ref.commandId()));
    }

    @Test
    void aCommandIsBoundToItsOwnAgentAndOrganization() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentKitWorld.Agent stranger = world().enrol();
        AgentSessionRef mine = world().ready(agent);
        AgentSessionRef theirs = world().ready(stranger);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        offer(mine);
        refused(GatewayCode.UNKNOWN_COMMAND, () -> world().commands().pull(theirs, ref.commandId()));
        assertEquals(CommandAckResult.UNKNOWN_COMMAND, world().commands().ack(theirs, ref.commandId(),
                ack("ack-x", ref.generation(), AgentOutcome.APPLIED)));
        assertEquals("OFFERED", world().commandState(ref.commandId()));
        assertEquals(0, world().commands().pendingFor(theirs, 10, RENOTIFY).size());
    }

    @Test
    void aPermissionChangeThatDropsTheCapabilityStopsThePull() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        offer(session);
        world().controlPlane(agent, true, 1, 1, List.of(AgentCapability.LIFECYCLE));
        world().leases().renew("renew-cap", session, first.nonce(), world().now(), world().origin());
        refused(GatewayCode.CAPABILITY_MISMATCH, () -> world().commands().pull(session, ref.commandId()));
    }

    @Test
    void aRevokedIdentityIsRefusedOnEveryCall() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        offer(session);
        world().controlPlane(agent, false, 1, 1, List.of());
        world().identities().reconcile(List.of(session));
        assertTrue(world().identityRevoked(agent));
        refused(GatewayCode.STALE_EPOCH, () -> world().commands().pendingFor(session, 10, RENOTIFY));
        refused(GatewayCode.STALE_EPOCH, () -> world().commands().ack(session, ref.commandId(),
                ack("ack-r", ref.generation(), AgentOutcome.APPLIED)));
    }

    @Test
    void agentsWithDeliverableWorkNamesOnlyAgentsThatHaveSome() {
        AgentKitWorld.Agent busy = world().enrol();
        AgentKitWorld.Agent idle = world().enrol();
        AgentSessionRef busySession = world().ready(busy);
        AgentSessionRef idleSession = world().ready(idle);
        enqueue(busy, key(), AgentOperation.APPLY);
        var named = world().commands().agentsWithDeliverableWork(List.of(busySession, idleSession), RENOTIFY);
        assertTrue(named.contains(busy.agentId()));
        assertFalse(named.contains(idle.agentId()));
    }

    @Test
    void noRenderedByteOrPlainDigestIsStoredAnywhereInTheAgentTables() throws Exception {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        CommandNotice notice = offer(session);
        String marker = "rendered-" + UUID.randomUUID();
        String plain = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new byte[] {1}));
        world().payload(ref.commandId(), marker.getBytes(StandardCharsets.UTF_8), plain);
        world().commands().pull(session, notice.commandId());
        world().commands().received(session, "recv-" + UUID.randomUUID(), ref.commandId(), ref.generation());
        world().commands().ack(session, ref.commandId(), ack("ack-z", ref.generation(), AgentOutcome.APPLIED));
        assertFalse(world().anyAgentRowContains(agent, marker));
        assertFalse(world().anyAgentRowContains(agent, plain));
        assertTrue(world().rowText("agent_command", agent).contains("hmacsha256v"), "the keyed hash is what is kept");
    }

    @Test
    void aReplayedAckWithAnotherOutcomeLeavesTheStoredOutcomeUnchanged() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        offer(session);
        assertEquals(CommandAckResult.COMMITTED, world().commands().ack(session, ref.commandId(),
                ack("ack-1", ref.generation(), AgentOutcome.APPLIED)));
        assertEquals(CommandAckResult.DUPLICATE, world().commands().ack(session, ref.commandId(),
                ack("ack-1", ref.generation(), AgentOutcome.FAILED_NO_EFFECT)));
        assertEquals(AgentOutcome.APPLIED, world().commands().completion(agent.placement(), ref.commandId()).orElseThrow().outcome());
    }

    @Test
    void receivedAndAckOnASupersededCommandAnswerSuperseded() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef old = enqueue(agent, key(), AgentOperation.APPLY);
        offer(session);                                                                  // OFFERED
        String keep = key("keep");
        enqueue(agent, keep, AgentOperation.RESTART);
        world().commands().supersedeOutstanding(agent.placement(), keep);
        refused(GatewayCode.SUPERSEDED, () -> world().commands().received(session, "recv-s", old.commandId(), old.generation()));
        refused(GatewayCode.SUPERSEDED, () -> world().commands().ack(session, old.commandId(),
                ack("ack-s", old.generation(), AgentOutcome.APPLIED)));
        assertEquals("SUPERSEDED", world().commandState(old.commandId()));
    }

    @Test
    void aReceivedReplayKeepsTheFirstRequestId() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        offer(session);
        world().commands().received(session, "recv-first", ref.commandId(), ref.generation());
        world().commands().received(session, "recv-first", ref.commandId(), ref.generation());
        world().commands().received(session, "recv-second", ref.commandId(), ref.generation());
        String row = world().rowText("agent_command", agent);
        assertTrue(row.contains("recv-first") && !row.contains("recv-second"), row);
        // plan 015 T3.3: a malformed request id is a CODED refusal (LABEL_INVALID), no longer an IllegalArgumentException
        refused(GatewayCode.LABEL_INVALID, () -> world().commands().received(session, "", ref.commandId(), ref.generation()));
        refused(GatewayCode.LABEL_INVALID,
                () -> world().commands().received(session, "x".repeat(65), ref.commandId(), ref.generation()));
    }

    @Test
    void agentsWithDeliverableWorkIgnoresAStaleEpochAndAnotherOrganizationsSession() {
        AgentKitWorld.Agent busy = world().enrol();
        AgentSessionRef stale = world().ready(busy);
        enqueue(busy, key(), AgentOperation.APPLY);
        AgentSessionRef current = world().open(busy);
        assertFalse(world().commands().agentsWithDeliverableWork(List.of(stale), RENOTIFY).contains(busy.agentId()), "stale epoch");
        assertTrue(world().commands().agentsWithDeliverableWork(List.of(current), RENOTIFY).contains(busy.agentId()));
        AgentKitWorld.Agent other = world().enrol();
        AgentSessionRef otherSession = world().ready(other);
        var named = world().commands().agentsWithDeliverableWork(List.of(otherSession), RENOTIFY);
        assertFalse(named.contains(busy.agentId()), "only the sessions asked about are named");
        assertFalse(named.contains(other.agentId()), "and the other organization has no work of its own");
    }

    @Test
    void completionIsBoundToTheOrganizationAndThePlacement() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentKitWorld.Agent stranger = world().enrol();
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        assertTrue(world().commands().completion(agent.placement(), ref.commandId()).isPresent());
        assertFalse(world().commands().completion(agent.placement(), ref.commandId()).orElseThrow().terminal());
        assertTrue(world().commands().completion(stranger.placement(), ref.commandId()).isEmpty(), "another organization");
        UUID elsewhere = world().addPlacement(agent);
        assertTrue(world().commands().completion(new com.solace.sm.dp.api.agw.AgentPlacementRef(agent.tenantId(), agent.instanceId(),
                elsewhere), ref.commandId()).isEmpty(), "another placement of the same organization");
    }

    // ---- plan 015 T3.1: authorization is a REFUSAL before any notice, a lost payload ends the command, a late ack is recorded --------

    /**
     * T2.1b fix-round note (a), design 4.6: a command the lease cannot authorize (here a capability it lacks) is moved to
     * {@code REFUSED(CAPABILITY_MISSING)} by the data plane BEFORE any notice. It is never silently skipped: the apply waiting on it
     * must learn the outcome from {@code completion}.
     */
    @Test
    void aCommandTheLeaseHasNoCapabilityForIsRefusedBeforeAnyNotice() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        world().controlPlane(agent, true, 1, 1, List.of(AgentCapability.LIFECYCLE));
        world().leases().renew("renew-cap-gate", session, first.nonce(), world().now(), world().origin());
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);

        assertEquals(0, world().commands().pendingFor(session, 10, RENOTIFY).size(), "no notice for what the lease cannot authorize");

        assertEquals("REFUSED", world().commandState(ref.commandId()));
        var completion = world().commands().completion(agent.placement(), ref.commandId()).orElseThrow();
        assertTrue(completion.terminal());
        assertEquals(AgentOutcome.REFUSED, completion.outcome());
        assertEquals("CAPABILITY_MISSING", completion.detailCode());
    }

    /** The same, for a placement the lease no longer names (design 4.6, row 29): {@code REFUSED(BINDING_NOT_IN_LEASE)}, no notice. */
    @Test
    void aCommandForAPlacementTheLeaseNoLongerNamesIsRefusedBeforeAnyNotice() {
        AgentKitWorld.Agent agent = world().enrol();
        UUID second = world().addPlacement(agent);
        AgentSessionRef session = world().open(agent);
        LeaseGrant first = world().lease(agent, session);
        AgentCommandRef ref = world().commands().enqueue(world().intentOn(agent, second, key(), AgentOperation.APPLY));
        world().retirePlacement(agent, second);
        world().renewLease(agent, session, first);

        assertEquals(0, world().commands().pendingFor(session, 10, RENOTIFY).size());

        assertEquals("REFUSED", world().commandState(ref.commandId()));
        assertEquals("BINDING_NOT_IN_LEASE",
                world().commands().completion(new com.solace.sm.dp.api.agw.AgentPlacementRef(agent.tenantId(), agent.instanceId(),
                        second), ref.commandId()).orElseThrow().detailCode());
    }

    /**
     * {@code AGW-D26}: SM persists no byte, so after a restart the payload may be gone and a re-render may differ. The pull is then
     * refused {@code agent.payload.render.changed} AND the command ends {@code REFUSED}, so the apply step fails with that code
     * instead of waiting on a command nothing can ever deliver.
     */
    @Test
    void aPullWhoseBytesAreGoneRefusesTheCommandWithTheRenderChangedCode() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef ref = enqueue(agent, key(), AgentOperation.APPLY);
        CommandNotice notice = offer(session);                    // nobody registered a payload for it

        refused(GatewayCode.AGENT_PAYLOAD_RENDER_CHANGED, () -> world().commands().pull(session, notice.commandId()));

        assertEquals("REFUSED", world().commandState(ref.commandId()));
        var completion = world().commands().completion(agent.placement(), ref.commandId()).orElseThrow();
        assertEquals(AgentOutcome.REFUSED, completion.outcome());
        assertEquals("agent.payload.render.changed", completion.detailCode());
    }

    /**
     * T2.3 integrator note, decided in T3.1 (RECONCILE): an agent that resumes a RECEIVED command from its spool runs it before the
     * data plane can say SUPERSEDED. The ack is still answered SUPERSEDED (the agent closes its journal record on it) and the
     * command stays SUPERSEDED, but the fact that the operation ran is RECORDED on the row, so it is never silent.
     */
    @Test
    void aLateAckOfASupersededCommandIsAnsweredSupersededAndRecorded() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef old = enqueue(agent, key(), AgentOperation.RESTART);
        offer(session);                                                                  // OFFERED: the agent may hold it
        String keep = key("keep");
        enqueue(agent, keep, AgentOperation.RESTART);
        world().commands().supersedeOutstanding(agent.placement(), keep);

        refused(GatewayCode.SUPERSEDED, () -> world().commands().ack(session, old.commandId(),
                ack("ack-late", old.generation(), AgentOutcome.APPLIED)));

        assertEquals("SUPERSEDED", world().commandState(old.commandId()));
        String row = world().rowText("agent_command", agent);
        assertTrue(row.contains("late.applied"), "the late result is recorded on the row: " + row);
        // a refusal says nothing ran, and is not recorded as a late result
        AgentCommandRef other = enqueue(agent, key(), AgentOperation.STOP);
        world().commands().pendingFor(session, 10, RENOTIFY);                            // OFFERED (with the kept RESTART)
        String keep2 = key("keep2");
        enqueue(agent, keep2, AgentOperation.RESTART);
        world().commands().supersedeOutstanding(agent.placement(), keep2);
        refused(GatewayCode.SUPERSEDED, () -> world().commands().ack(session, other.commandId(),
                ack("ack-late-2", other.generation(), AgentOutcome.SUPERSEDED)));
        assertFalse(world().rowText("agent_command", agent).contains("late.superseded"));
    }

    /**
     * Review finding 4 (plan 015 T3.1): a live command a retry or a resume finds later must still be deliverable. The lease stops
     * admitting a command at {@code deadline - ceiling - margin}, so handing back the original, expired-for-delivery command
     * would wait a whole ceiling and fail, forever. {@code enqueue} returns the SAME command (same id, same wire generation, so
     * the agent journal's dedupe holds) with the deadline refreshed.
     */
    @Test
    void enqueueRefreshesTheDeadlineOfTheLiveCommandItReturns() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        String key = key();
        AgentCommandRef first = enqueue(agent, key, AgentOperation.RESTART);
        world().advance(Duration.ofMinutes(9).plusSeconds(30));          // inside the deadline, outside what the lease will admit
        assertEquals(0, world().commands().pendingFor(session, 10, RENOTIFY).size(), "no longer deliverable as minted");

        AgentCommandRef again = enqueue(agent, key, AgentOperation.RESTART);

        assertEquals(first.commandId(), again.commandId());
        assertEquals(first.generation(), again.generation());
        assertFalse(again.minted());
        assertEquals(1, world().commands().pendingFor(session, 10, RENOTIFY).size(), "…and deliverable again");
    }

    /** The first late result stands; a replay with another request id or another outcome changes nothing (review finding 12). */
    @Test
    void aReplayedLateAckLeavesTheFirstRecordStanding() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        AgentCommandRef old = enqueue(agent, key(), AgentOperation.RESTART);
        offer(session);
        String keep = key("keep");
        enqueue(agent, keep, AgentOperation.RESTART);
        world().commands().supersedeOutstanding(agent.placement(), keep);

        refused(GatewayCode.SUPERSEDED, () -> world().commands().ack(session, old.commandId(),
                ack("late-1", old.generation(), AgentOutcome.APPLIED)));
        refused(GatewayCode.SUPERSEDED, () -> world().commands().ack(session, old.commandId(),
                ack("late-1", old.generation(), AgentOutcome.APPLIED)));
        refused(GatewayCode.SUPERSEDED, () -> world().commands().ack(session, old.commandId(),
                ack("late-2", old.generation(), AgentOutcome.FAILED_NO_EFFECT)));

        String row = world().rowText("agent_command", agent);
        assertTrue(row.contains("late.applied") && row.contains("late-1"), row);
        assertFalse(row.contains("late.failed_no_effect") || row.contains("late-2"), "the first record stands: " + row);
    }

    // ---- AGW-D28 item 2 (OR-139): the acknowledgement's container start is the baseline ------------------------------------------

    /** Offers, announces and acknowledges one command of the agent's own placement with {@code started} (may be null). */
    private CommandAckResult appliedWithStart(AgentKitWorld.Agent agent, AgentSessionRef session, AgentOperation operation,
            java.time.Instant started) {
        AgentCommandRef command = enqueue(agent, key(), operation);
        offer(session);
        world().commands().received(session, "recv-" + UUID.randomUUID(), command.commandId(), command.generation());
        return world().commands().ack(session, command.commandId(), new AgentAck("ack-" + UUID.randomUUID(),
                command.generation(), AgentOutcome.APPLIED, "", "", started));
    }

    @Test
    void anAcknowledgedContainerStartIsRecordedAsThePlacementsStartAndOnlyEverMovesForward() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        java.time.Instant t1 = world().now().minusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        assertEquals(java.util.Optional.empty(), world().placementStart(agent));
        assertEquals(CommandAckResult.COMMITTED, appliedWithStart(agent, session, AgentOperation.APPLY, t1));
        assertEquals(java.util.Optional.of(t1), world().placementStart(agent), "recorded in the acknowledgement's own transaction");
        appliedWithStart(agent, session, AgentOperation.REMATERIALIZE, t1.minusSeconds(60));
        assertEquals(java.util.Optional.of(t1), world().placementStart(agent), "an earlier start never lowers the baseline");
        java.time.Instant t2 = t1.plusSeconds(120);
        appliedWithStart(agent, session, AgentOperation.RESTART, t2);
        assertEquals(java.util.Optional.of(t2), world().placementStart(agent), "a later start moves it");
    }

    @Test
    void anAcknowledgementWithoutAStartRecordsNoneAndOneBeyondTheClockBoundRecordsNoneButStillAcknowledges() {
        AgentKitWorld.Agent agent = world().enrol();
        AgentSessionRef session = world().ready(agent);
        assertEquals(CommandAckResult.COMMITTED, appliedWithStart(agent, session, AgentOperation.APPLY, null));
        assertEquals(java.util.Optional.empty(), world().placementStart(agent), "fail closed: no start acknowledged, none recorded");
        assertEquals(CommandAckResult.COMMITTED,
                appliedWithStart(agent, session, AgentOperation.RESTART, world().now().plus(Duration.ofMinutes(10))));
        assertEquals(java.util.Optional.empty(), world().placementStart(agent),
                "CLOCK_SUSPECT: a start more than five minutes ahead of the data plane is not believed");
    }
}
