package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.audit.RequestOrigin;
import com.solace.sm.cp.api.audit.TargetProbeAudit;
import com.solace.sm.cp.api.audit.TargetProbeAudit.ScanResult;
import com.solace.sm.cp.api.audit.TargetProbeAudit.TestResult;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.cp.api.target.TargetRegistration;
import com.solace.sm.dp.api.inventory.CredentialInvalidException;
import com.solace.sm.dp.api.inventory.HostKeyHeldElsewhereException;
import com.solace.sm.dp.api.inventory.HostKeyNotOfferedException;
import com.solace.sm.dp.api.inventory.HostKeyScanUnavailableException;
import com.solace.sm.dp.api.inventory.ScanAccounting;
import com.solace.sm.dp.api.inventory.TargetAddressRefusedException;
import com.solace.sm.dp.api.inventory.TargetChangeOrigin;
import com.solace.sm.dp.api.inventory.TargetConnectionChangedException;
import com.solace.sm.dp.api.inventory.TargetConnectionEdit;
import com.solace.sm.dp.api.inventory.TargetConnectionManagement;
import com.solace.sm.dp.api.inventory.TargetConnectionTestResult;
import com.solace.sm.dp.api.inventory.TargetConnectionTester;
import com.solace.sm.dp.api.inventory.TargetConnectionView;
import com.solace.sm.dp.api.inventory.TargetHasLivePlacementsException;
import com.solace.sm.dp.api.inventory.TargetWorkInFlightException;
import java.util.Arrays;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The registration API's four operations on a target that already exists (plan 013 T1.5; D-REG sections 5.1 T4 to T7 and
 * 6.4; {@code BE-D54}): {@code editTargetConnection}, {@code rotateTargetCredential}, {@code testTargetConnection} and
 * {@code retireTarget}. A translator and a sequencer: every rule is the data plane's or the control plane's, behind their
 * ports. {@link TargetRegistrationOrchestrator} owns the registration's three and the per-session scan budget this class
 * shares.
 *
 * <p><b>Authorization</b> has passed in {@link AuthenticationInterceptor} before any method here runs, at the TARGET named
 * by the path ({@link TargetResolver}): {@code target.edit} for three, {@code target.use} for the test. The body never names
 * a scope, so there is no second node to disagree with.
 *
 * <p><b>{@code editTargetConnection}</b> plans first (read-only, no I/O: the grammar, and a 404), then hands the edit to
 * the data plane, which scans, locks, checks that no work is in flight and writes (OQ-12 (a)). A scan draws a token from the
 * session's scan budget and leaves one {@code target.edit} audit row, exactly as the registration's pre-check does; an edit
 * that does not scan (a change of the SSH user alone, or none) is neither charged nor recorded. It is the DATA PLANE that
 * says whether it scans, through {@code ScanAccounting} (review {@code W4C-3}), so a record that moves between the plan and
 * the write cannot make the charge or the audit disagree with what happened.
 *
 * <p><b>{@code retireTarget}</b> is data plane first (a retired connection is unusable at once), control plane second. A
 * failure between the two leaves a listed target that cannot be used, and the same call again completes it: the data
 * plane answers "already retired" and the control plane retires its row. A control-plane row the data plane never heard of
 * (a registration that stopped between its two writes) retires on its own.
 *
 * <p><b>{@code I5}.</b> The organization is {@code Session.organizationId()} on every call to both planes. <b>{@code I6}.</b>
 * A rotation's key is a {@code char[]} zeroised in a {@code finally}; no refusal here carries anything the request carried.
 */
@Component
public class TargetManagementOrchestrator {

    private final TargetRegistrationOrchestrator registrations;
    private final TargetConnectionManagement management;
    private final TargetConnectionTester tester;
    private final TargetRegistration controlPlane;
    private final TargetProbeAudit probeAudit;
    private final RequestOriginFactory origins;
    /** The targets with a connection test running now (review W4C-4): at most one test of a target at a time. */
    private final Set<UUID> testing = ConcurrentHashMap.newKeySet();

    public TargetManagementOrchestrator(TargetRegistrationOrchestrator registrations,
            TargetConnectionManagement management, TargetConnectionTester tester, TargetRegistration controlPlane,
            TargetProbeAudit probeAudit, RequestOriginFactory origins) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.management = Objects.requireNonNull(management, "management");
        this.tester = Objects.requireNonNull(tester, "tester");
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.probeAudit = Objects.requireNonNull(probeAudit, "probeAudit");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    private static TargetChangeOrigin dataPlaneOrigin(RequestOrigin origin) {
        return new TargetChangeOrigin(origin.correlationId(), origin.clientAddress());
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException(ResourceKind.DEPLOYMENT_TARGET);
    }

    // ---- editTargetConnection ---------------------------------------------------------------------------------

    public TargetConnection edit(Session session, UUID targetId, EditTargetConnectionRequest request) {
        UUID organization = session.organizationId();
        RequestOrigin origin = origins.create();
        TargetConnectionEdit edit = request.toEdit();

        try {
            management.planEdit(organization, targetId, edit);
        } catch (NoSuchElementException gone) {
            throw notFound();
        } catch (IllegalArgumentException grammar) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.INVALID_FIELD,
                    fieldOf(grammar));
        }
        // The data plane decides whether it scans, under the record it reads, and tells this ledger when it does (review
        // W4C-3): a scan is charged before it is made and audited once, whatever the plan above said and however the edit
        // ends. The plan is no longer what is accounted for.
        RescanLedger scan = new RescanLedger(session, origin, targetId);
        ScanResult outcome = null;
        try {
            return TargetRegistrationOrchestrator.connection(management.editConnection(organization, targetId, edit,
                    session.principal().id(), dataPlaneOrigin(origin), scan));
        } catch (NoSuchElementException gone) {
            throw notFound();
        } catch (TargetAddressRefusedException refused) {
            outcome = ScanResult.ADDRESS_REFUSED;
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.ADDRESS_REFUSED);
        } catch (HostKeyScanUnavailableException unavailable) {
            outcome = ScanResult.UNREACHABLE;
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.HOST_UNREACHABLE);
        } catch (HostKeyNotOfferedException notOffered) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.HOST_KEY_NOT_OFFERED);
        } catch (HostKeyHeldElsewhereException held) {
            // after the scan when the edit rescanned; the pre-write probe and the write's unique indexes both raise it
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.HOST_KEY_HELD);
        } catch (TargetWorkInFlightException busy) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.WORK_IN_FLIGHT);
        } catch (TargetConnectionChangedException changed) {         // an IllegalStateException
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.RECORD_CHANGED);
        } catch (IllegalArgumentException grammar) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.INVALID_FIELD,
                    fieldOf(grammar));
        } finally {
            scan.audit(outcome);
        }
    }

    /**
     * What the data plane tells this request about its scan (review {@code W4C-3}): charged in {@link #beforeScan} (a spent
     * budget is recorded as {@code rate_limited} there, and the edit does not go on), and audited exactly once in
     * {@link #audit}, which runs on every exit of the edit. No scan, no token and no row.
     *
     * <p>How a scan ended: by the exception that ended the edit when it names the scan ({@code address_refused},
     * {@code unreachable}); else {@code completed} if the host answered; else, when the data plane began a scan that neither
     * answered nor said why (an unclassified failure inside it), {@code unreachable}, the one fixed code that says the host
     * gave no keys.
     */
    private final class RescanLedger implements ScanAccounting {

        private final Session session;
        private final RequestOrigin origin;
        private final UUID targetId;
        private boolean charged;
        private boolean answered;

        RescanLedger(Session session, RequestOrigin origin, UUID targetId) {
            this.session = session;
            this.origin = origin;
            this.targetId = targetId;
        }

        @Override
        public void beforeScan() {
            registrations.chargeRescan(session, origin, targetId);      // throws the 429 (and records it) when spent
            charged = true;
        }

        @Override
        public void afterScan() {
            answered = true;
        }

        void audit(ScanResult named) {
            if (!charged) {
                return;                       // no scan began: no row (a refused charge recorded its own)
            }
            registrations.auditRescan(session, origin, targetId,
                    named != null ? named : answered ? ScanResult.COMPLETED : ScanResult.UNREACHABLE);
        }
    }

    // ---- rotateTargetCredential -------------------------------------------------------------------------------

    public RotatedCredential rotate(Session session, UUID targetId, RotateTargetCredentialRequest request) {
        RequestOrigin origin = origins.create();
        char[] key = request.privateKey().toCharArray();
        try {
            TargetConnectionView.CredentialSummary summary = management.rotateConnectionCredential(
                    session.organizationId(), targetId, key, session.principal().id(), dataPlaneOrigin(origin));
            return new RotatedCredential(new CredentialSummary(summary.present(), summary.publicKeyFingerprint(),
                    summary.setAt() == null ? null : summary.setAt().toString()));
        } catch (CredentialInvalidException invalid) {                // an IllegalArgumentException: caught first
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.CREDENTIAL_INVALID);
        } catch (NoSuchElementException gone) {
            throw notFound();
        } catch (TargetConnectionChangedException changed) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.RECORD_CHANGED);
        } finally {
            Arrays.fill(key, '\0');
        }
    }

    // ---- testTargetConnection ---------------------------------------------------------------------------------

    public ConnectionTestResult test(Session session, UUID targetId) {
        RequestOrigin origin = origins.create();
        registrations.requireSshTarget(session, targetId);        // plan 015 T3.5: an agent target has nothing to connect to
        // Review W4C-4. A test opens an SSH connection and runs a command on the request thread, for up to 25 seconds: one
        // test of a target at a time (a second is a 429, not a queue), and a draw on the session's scan budget.
        if (!testing.add(targetId)) {
            probeAudit.tested(session.principal(), origin, new TenantId(session.organizationId()), targetId,
                    TestResult.RATE_LIMITED);
            throw new GatewayRejectionException(ErrorCode.RATE_LIMITED, Map.of("Retry-After", "1"));
        }
        try {
            registrations.chargeTest(session, origin, targetId);
            TargetConnectionTestResult result;
            try {
                result = tester.test(session.organizationId(), targetId);
            } catch (NoSuchElementException gone) {
                throw notFound();
            }
            probeAudit.tested(session.principal(), origin, new TenantId(session.organizationId()), targetId,
                    outcomeOf(result));
            return new ConnectionTestResult(result.hostKeyPinned(), result.authenticated(),
                    new ConnectionTestResult.DockerCheck(result.dockerAvailable(), result.dockerServerVersion()),
                    result.checkedAt().toString());
        } finally {
            testing.remove(targetId);
        }
    }

    private static TestResult outcomeOf(TargetConnectionTestResult result) {
        if (!result.hostKeyPinned()) {
            return TestResult.NOT_REACHED;
        }
        if (!result.authenticated()) {
            return TestResult.NOT_AUTHENTICATED;
        }
        return result.dockerAvailable() ? TestResult.PASSED : TestResult.DOCKER_UNAVAILABLE;
    }

    // ---- retireTarget -----------------------------------------------------------------------------------------

    public Target retire(Session session, UUID targetId) {
        UUID organization = session.organizationId();
        RequestOrigin origin = origins.create();
        try {
            management.retireConnection(organization, targetId, session.principal().id(), dataPlaneOrigin(origin));
        } catch (TargetHasLivePlacementsException live) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.LIVE_PLACEMENTS);
        } catch (TargetConnectionChangedException changed) {
            throw new TargetRegistrationRefusedException(TargetRegistrationRefusedException.Reason.RECORD_CHANGED);
        }
        TargetRegistration.Retired retired = controlPlane.retire(session.principal(), organization, targetId, origin);
        var view = retired.view();
        return new Target(view.targetId(), view.name(), TargetKind.fromWire(view.kind()),
                TargetTransport.fromWire(view.transport()), toWire(view.ownershipScope()), null, null,
                retired.retiredAt().toString());
    }

    private static ScopePath toWire(com.solace.sm.cp.api.authz.ScopePath path) {
        return new ScopePath(path.organization(), path.subOrganization(), path.environment(), path.solution(),
                path.instance());
    }

    /**
     * The field a data-plane grammar refusal names. {@code TargetConnectionValidator} states "{@code <field> is not ...}"
     * and never the value; the first word is taken only if it is one of the fields this body accepts, so nothing else can
     * reach a response body.
     */
    private static String fieldOf(IllegalArgumentException refusal) {
        String message = refusal.getMessage();
        int space = message == null ? -1 : message.indexOf(' ');
        String first = space < 0 ? "" : message.substring(0, space);
        return switch (first) {
            case "host", "port", "sshUser", "hostKeyFingerprint" -> first;
            default -> "connection";
        };
    }
}
