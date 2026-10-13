package com.solace.sm.dp.api.agw.kit;

import com.solace.sm.dp.api.agw.ActualStatePort;
import com.solace.sm.dp.api.agw.AgentAuditPort;
import com.solace.sm.dp.api.agw.AgentCapability;
import com.solace.sm.dp.api.agw.AgentCommandIntent;
import com.solace.sm.dp.api.agw.AgentCommandPort;
import com.solace.sm.dp.api.agw.AgentDiscoveryPort;
import com.solace.sm.dp.api.agw.AgentEnrollmentPort;
import com.solace.sm.dp.api.agw.AgentIdentity;
import com.solace.sm.dp.api.agw.AgentIdentityPort;
import com.solace.sm.dp.api.agw.AgentLeasePort;
import com.solace.sm.dp.api.agw.AgentOperation;
import com.solace.sm.dp.api.agw.AgentPlacementRef;
import com.solace.sm.dp.api.agw.AgentRequestOrigin;
import com.solace.sm.dp.api.agw.AgentSessionPort;
import com.solace.sm.dp.api.agw.AgentSessionRef;
import com.solace.sm.dp.api.agw.AgentTelemetryDeclarationsPort;
import com.solace.sm.dp.api.agw.AgentTelemetryPort;
import com.solace.sm.dp.api.agw.CommandId;
import com.solace.sm.dp.api.agw.EnrollmentRequest;
import com.solace.sm.dp.api.agw.LeaseGrant;
import com.solace.sm.dp.api.agw.SessionOpening;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * The world the agent-gateway port contract kits (plan 015 T2.1b) run in: an implementation of the ports, wired for real, and the
 * handful of ways a kit has to set a scenario up or look at what was stored. A kit says what a port must do; the module that
 * implements the port supplies the world. {@code dp-core}'s {@code AgentJdbcWorld} is the first (PostgreSQL 14, the runtime role).
 *
 * <p>Every observation method is the OWNER's view of the stored truth, not the port's: a kit must be able to assert that
 * nothing was written, which the port under test cannot honestly report about itself.
 */
public interface AgentKitWorld {

    /** An enrolled agent: its organization (a fresh one per call), identity, agent target and one live placement. */
    record Agent(UUID tenantId, UUID agentId, String thumbprint, UUID targetId, UUID instanceId, UUID placementId) {

        public AgentPlacementRef placement() {
            return new AgentPlacementRef(tenantId, instanceId, placementId);
        }

        /** The live placement's {@code container_name}: the world seeds it as {@code sm-<placement id>} (the envelope's prefix). */
        public String containerName() {
            return "sm-" + placementId;
        }
    }

    /** An enrolment the world's issuer will answer for. */
    record Enrollment(EnrollmentRequest request, UUID agentId, UUID tenantId, UUID targetId) {
    }

    // ---- the ports under test, wired for real ---------------------------------------------------------------------

    AgentSessionPort sessions();

    AgentLeasePort leases();

    AgentCommandPort commands();

    ActualStatePort reports();

    AgentAuditPort audits();

    AgentTelemetryPort telemetry();

    /**
     * The telemetry-declarations port, wired for real, including the change signal's path from the data plane's own change to its
     * subscribers. Plan 015 W4 lane D (HF18 test-jar addition, test sources only): a world that runs the declarations kit overrides it.
     */
    default AgentTelemetryDeclarationsPort declarations() {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement declarations()");
    }

    AgentEnrollmentPort enrollments();

    AgentDiscoveryPort discovery();

    AgentIdentityPort identities();

    // ---- scenario construction -------------------------------------------------------------------------------------

    Agent enrol();

    /** Another live placement (of a new instance) on the agent's own target. */
    UUID addPlacement(Agent agent);

    /** Retires a placement, so the lease the DP lets the issuer sign no longer holds it. */
    void retirePlacement(Agent agent, UUID placementId);

    /**
     * Gives the agent's own instance a package version that defines each of these names as an {@code http_json} gauge on container
     * port 8081, and makes ALL of them active (generation 0's list, which is written once: call it once per agent). Plan 015 W4 lane D
     * (test-jar addition): the collection, ingest and placement rows the declarations are read from.
     */
    default void declareMetrics(Agent agent, String... names) {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement declareMetrics");
    }

    /** The agent's instance leaves {@code active} (it declares nothing from then on). Plan 015 W4 lane D (test-jar addition). */
    default void deactivateInstance(Agent agent) {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement deactivateInstance");
    }

    /**
     * A data-plane change that alters what the agent is declared, made the way the data plane makes it: the placement is retired and
     * its instance's configuration ladder settles IN THE SAME TRANSACTION, which is what signals the declarations subscribers once it
     * commits (a notice that fires before the commit is therefore visible to a reader on another thread as the old set). Plan 015 W4
     * lane D (test-jar addition).
     */
    default void changeDeclarations(Agent agent, UUID placementId) {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement changeDeclarations");
    }

    /**
     * A NEW instance with a live placement on the agent's own target, added the way the data plane adds one (inserted and its ladder
     * settled in one transaction). The agent's stored lease does not hold it. Returns the placement id. Plan 015 W4 lane D (test-jar
     * addition).
     */
    default UUID addPlacementThroughTheDataPlane(Agent agent) {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement addPlacementThroughTheDataPlane");
    }

    /** An agent of ANOTHER organization whose instance has the SAME instance id as {@code instanceId} (keys are per organization). Plan 015 W4 lane D (test-jar addition). */
    default Agent enrolWithInstance(UUID instanceId) {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement enrolWithInstance");
    }

    /** The gateway-minted origin of a call. */
    AgentRequestOrigin origin();

    /** {@code sessions().claim} for the agent, with the world's ordinary opening. */
    AgentSessionRef open(Agent agent);

    /** As above with an opening of the kit's choosing (features, versions, a skewed agent clock). */
    AgentSessionRef open(Agent agent, SessionOpening opening);

    /** An ordinary opening: protocol 1, no features, the agent clock equal to the world's. */
    SessionOpening opening();

    /** The agent's recorded clock offset in milliseconds (DP clock minus the agent's), null when never measured. */
    Long clockOffsetMs(Agent agent);

    /** {@code leases().issue} for the session. */
    LeaseGrant lease(Agent agent, AgentSessionRef session);

    /** Renews the lease the session holds (a fresh request id), so a change the data plane made since is in the stored lease. */
    default LeaseGrant renewLease(Agent agent, AgentSessionRef session, LeaseGrant current) {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement renewLease (plan 015 T3.1; HF18 test-jar"
                + " addition accepted by the integrator): a world that runs the command kit must override it");
    }

    /** A session with a lease: the state every command, report and telemetry call needs. */
    default AgentSessionRef ready(Agent agent) {
        AgentSessionRef session = open(agent);
        lease(agent, session);
        return session;
    }

    /** What the issuer was last asked to sign for (the identity as the DP derived it). */
    AgentIdentity lastIssuedFor();

    /** The issuer's grant is altered by this function before the DP sees it (null restores it). */
    void issuerCorrupts(java.util.function.UnaryOperator<LeaseGrant> corruption);

    /** The lease issuer will also sign this placement, which the data plane never supplied. */
    void issuerAlsoNames(UUID placementId);

    // ---- the control plane, and time -------------------------------------------------------------------------------

    /** The control plane's registration for the agent. */
    void controlPlane(Agent agent, boolean active, long agentEpoch, long fleetEpoch, List<AgentCapability> capabilities);

    /** The control plane no longer knows the agent at all. */
    void controlPlaneForgets(Agent agent);

    /** The control plane cannot be read at all. */
    void controlPlaneDown(boolean down);

    /** How many times the control plane's registration was read. */
    int controlPlaneReads();

    void advance(Duration by);

    Instant now();

    // ---- commands -------------------------------------------------------------------------------------------------

    /** The journal rows (apply request, step intent) the intent's foreign keys need, and the intent over them. */
    AgentCommandIntent intent(Agent agent, String intentKey, AgentOperation operation);

    /** The same, on another placement of the agent. */
    AgentCommandIntent intentOn(Agent agent, UUID placementId, String intentKey, AgentOperation operation);

    /** What the (fake) payload source hands over for the command. */
    void payload(CommandId id, byte[] bytes, String plainSha256);

    // ---- observation (the stored truth) -----------------------------------------------------------------------------

    /** Rows of {@code table} (a bare {@code agent_*} name) for this agent, whatever tenant they sit under. */
    long rows(String table, Agent agent);

    /** The organization the agent's rows in {@code table} sit under (exactly one). */
    UUID storedTenant(String table, Agent agent);

    /** Every row of the table for the agent as text (JSON), for "never stored" assertions. */
    String rowText(String table, Agent agent);

    /** True if any {@code agent_*} row of the agent's organization contains the text. */
    boolean anyAgentRowContains(Agent agent, String needle);

    /**
     * The container start the data plane holds for the agent's own placement ({@code placement_start_observation}); empty when none.
     * Plan 015 W3 integration (AGW-D28 item 2, OR-139): HF18 test-jar addition. A world that runs the command kit overrides it.
     */
    default java.util.Optional<Instant> placementStart(Agent agent) {
        throw new UnsupportedOperationException(getClass().getName() + " does not implement placementStart");
    }

    String commandState(CommandId id);

    /** All of the command row's column values and its physical size: equal before and after means nothing was written. */
    String commandFingerprint(CommandId id);

    Instant sessionLastSeen(Agent agent);

    boolean sessionClosed(Agent agent);

    boolean identityRevoked(Agent agent);

    String activeLeaseNonceDigest(Agent agent);

    OptionalLong reportWatermark(Agent agent, UUID placementId);

    OptionalLong telemetryWatermark(Agent agent, String streamId);

    // ---- enrolment ------------------------------------------------------------------------------------------------

    /** A new enrolment: the world's issuer and facts source answer for it. */
    Enrollment newEnrollment();

    /** The control plane's registration for the enrolled agent exists and is active (as it is after a real enrolment). */
    void controlPlaneKnows(Enrollment enrollment);

    /** The same token and CSR under a FRESH request id (the agent's retry after a lost reply). */
    EnrollmentRequest replayOf(Enrollment enrollment);

    /** Another identity takes the leaf's thumbprint, so the DP half of the enrolment fails (rolls back) at the identity insert. */
    void occupyThumbprint(Enrollment enrollment);

    /** The squatter gives it up again. */
    void releaseThumbprint(Enrollment enrollment);

    /** The issuer refuses this enrolment. */
    void issuerRefuses(Enrollment enrollment);

    /** The SHA-256 (lower hex) of the leaf certificate the issuer returns for the enrolment. */
    String leafThumbprint(Enrollment enrollment);

    /** {@code target_connection} rows of the enrolment's target, and the {@code enrolled} events among its events. */
    long targetRows(Enrollment enrollment);

    long enrolledEvents(Enrollment enrollment);

    long identityRows(Enrollment enrollment);

    /** The organization the enrolled identity sits under. */
    UUID enrolledTenant(Enrollment enrollment);

    /** The envelope the DP stored for the enrolled target: root and prefix. */
    List<String> storedEnvelope(Enrollment enrollment);
}
