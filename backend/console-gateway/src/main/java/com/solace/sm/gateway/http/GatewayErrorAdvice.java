package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.connection.ConnectionConflictException;
import com.solace.sm.cp.api.catalog.EntitlementRefusedException;
import com.solace.sm.cp.api.catalog.PackageRejectedException;
import com.solace.sm.cp.api.governance.QuotaExceededException;
import com.solace.sm.cp.api.identity.AuthenticationUnavailableException;
import com.solace.sm.cp.api.identity.CredentialRotationRefusedException;
import com.solace.sm.cp.api.identity.CredentialRotationRequiredException;
import com.solace.sm.cp.api.identity.LoginOrganizationRequiredException;
import com.solace.sm.cp.api.identity.LoginRefusedException;
import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.apply.ScheduleRefusedException;
import com.solace.sm.dp.api.config.StaleStateVersionException;
import com.solace.sm.dp.api.fleet.FleetConflictException;
import com.solace.sm.dp.api.fleet.FleetValidationException;
import com.solace.sm.dp.api.inventory.AgentStillEnrolledException;
import com.solace.sm.dp.api.inventory.InstanceNameTakenException;
import com.solace.sm.dp.api.inventory.InstanceRequestInFlightException;
import com.solace.sm.dp.api.inventory.LastLivePlacementException;
import com.solace.sm.dp.api.inventory.PlacementCollisionException;
import com.solace.sm.dp.api.inventory.PlacementInFlightException;
import com.solace.sm.dp.api.inventory.PlacementSchedulePendingException;
import com.solace.sm.dp.api.inventory.PushMetricsPlacementRefusedException;
import com.solace.sm.dp.api.inventory.RetirementRefusedException;
import com.solace.sm.dp.api.inventory.TargetIsAgentManagedException;
import com.solace.sm.dp.api.inventory.TargetTransportUnsupportedException;
import com.solace.sm.dp.api.inventory.PlacementOutsideEnvelopeException;
import com.solace.sm.dp.api.metrics.declare.MetricsPortCollisionException;
import com.solace.sm.dp.api.metrics.declare.PackageVersionMismatchException;
import com.solace.sm.dp.api.readback.ReadBackRefusedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The ONE place an HTTP response is built for anything that goes wrong THROUGH A HandlerMethod.
 * No controller formats its own error body.
 *
 * <p>⚠️ This {@code @ExceptionHandler} advice, plain {@code @RestControllerAdvice} restriction or
 * not, NEVER applies to {@link NoRouteHandler} or to any other non-{@code HandlerMethod} handler
 * (measured — see {@link NoRouteHandler}'s javadoc): {@code
 * ExceptionHandlerExceptionResolver.shouldApplyTo} refuses those handler shapes outright, so an
 * exception thrown for one of them would propagate here unhandled. {@link NoRouteHandler} and
 * {@link AuthenticationInterceptor}'s own non-{@code HandlerMethod} branch therefore render the
 * same {@link ProblemBody} shape directly instead of throwing — see {@link NoRouteHandler#render}.
 */
@RestControllerAdvice
public class GatewayErrorAdvice {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayErrorAdvice.class);

    @ExceptionHandler(GatewayRejectionException.class)
    public ResponseEntity<ProblemBody> handleRejection(GatewayRejectionException exception,
            HttpServletRequest request) {
        ErrorCode code = exception.code();
        UUID correlationId = correlationId(request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        exception.headers().forEach(builder::header);
        return builder.body(ProblemBody.of(code, correlationId));
    }

    /**
     * Plan 015 T3.5: the data plane's three refusals about an AGENT target, each a coded {@code 409} with a fixed body (the
     * exceptions name no target, host, agent or placement). The edit, rotation and test of an SSH record
     * ({@code TARGET_TRANSPORT_UNSUPPORTED}), the retirement before the agent is revoked ({@code AGENT_STILL_ENROLLED}), and a
     * placement or declare naming an agent target ({@code TARGET_IS_AGENT_MANAGED}).
     */
    @ExceptionHandler(TargetTransportUnsupportedException.class)
    public ResponseEntity<ProblemBody> handleTargetTransportUnsupported(TargetTransportUnsupportedException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.TARGET_TRANSPORT_UNSUPPORTED, request);
    }

    @ExceptionHandler(AgentStillEnrolledException.class)
    public ResponseEntity<ProblemBody> handleAgentStillEnrolled(AgentStillEnrolledException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.AGENT_STILL_ENROLLED, request);
    }

    /** The control plane's own refusal: an ACTIVE registration still holds the agent target (plan 015 T3.5 fix round). */
    @ExceptionHandler(com.solace.sm.cp.api.target.TargetHeldByAgentException.class)
    public ResponseEntity<ProblemBody> handleTargetHeldByAgent(com.solace.sm.cp.api.target.TargetHeldByAgentException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.AGENT_STILL_ENROLLED, request);
    }

    @ExceptionHandler(TargetIsAgentManagedException.class)
    public ResponseEntity<ProblemBody> handleTargetIsAgentManaged(TargetIsAgentManagedException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.TARGET_IS_AGENT_MANAGED, request);
    }

    @ExceptionHandler(LoginRefusedException.class)
    public ResponseEntity<ProblemBody> handleLoginRefused(LoginRefusedException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.AUTHENTICATION_FAILED, request);
    }

    /** OR-131 Q3: the login omitted its organization and the install does not hold exactly one: a request-shape 400 (BAD_REQUEST). */
    @ExceptionHandler(LoginOrganizationRequiredException.class)
    public ResponseEntity<ProblemBody> handleLoginOrganizationRequired(LoginOrganizationRequiredException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.BAD_REQUEST, request,
                "organization is required: this install does not hold exactly one organization");
    }

    @ExceptionHandler(CredentialRotationRequiredException.class)
    public ResponseEntity<ProblemBody> handleMustRotate(CredentialRotationRequiredException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.MUST_ROTATE, request);
    }

    @ExceptionHandler(CredentialRotationRefusedException.class)
    public ResponseEntity<ProblemBody> handleRotationRefused(CredentialRotationRefusedException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.CREDENTIAL_ROTATION_REFUSED, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemBody> handleAccessDenied(AccessDeniedException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.FORBIDDEN, request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemBody> handleResourceNotFound(ResourceNotFoundException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.NOT_FOUND, request);
    }

    /** The package catalog's refusals (plan 010 T3.2). Not a 403: the verb is held; the entitlement is what is missing. */
    @ExceptionHandler(EntitlementRefusedException.class)
    public ResponseEntity<ProblemBody> handleEntitlementRefused(EntitlementRefusedException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.ENTITLEMENT_REFUSED, request);
    }

    /**
     * The organization's instance quota is exhausted ({@code OR-40}; plan 010 T4.4). Not a 403: the verb is held.
     */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ProblemBody> handleQuotaExceeded(QuotaExceededException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.ENTITLEMENT_REFUSED, request);
    }

    /** {@code D10}'s two-sided rule: names WHICH verb is missing (a closed catalog id, never request content). */
    @ExceptionHandler(SecondaryVerbDeniedException.class)
    public ResponseEntity<ProblemBody> handleSecondaryVerbDenied(SecondaryVerbDeniedException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.FORBIDDEN, request, "this operation also requires " + exception.permission().id());
    }

    /** {@code OR-29}: names the collision CLASS only - never the value, never the other instance. */
    @ExceptionHandler(PlacementCollisionException.class)
    public ResponseEntity<ProblemBody> handlePlacementCollision(PlacementCollisionException exception,
            HttpServletRequest request) {
        String what = switch (exception.kind()) {
            case CONTAINER_NAME -> "container name";
            case CONFIG_DIRECTORY -> "config directory";
            case HEALTH_PORT -> "health port";
        };
        return detailed(ErrorCode.CONFLICT, request, "a live placement on this target already uses that " + what);
    }

    /**
     * Plan 011 M-Q14: names the collision CLASS only, never the port and never the other instance (the same rule as
     * {@code OR-29}). Its own code, beside {@code CONFLICT}, so a client can tell "your port map" from "your placement".
     */
    @ExceptionHandler(MetricsPortCollisionException.class)
    public ResponseEntity<ProblemBody> handleMetricsPortCollision(MetricsPortCollisionException exception,
            HttpServletRequest request) {
        String what = switch (exception.kind()) {
            case METRICS_HOST_PORT_IN_USE -> "a live placement on this target already maps a metrics host port this "
                    + "declare names";
            case METRICS_HOST_PORT_IS_ANOTHER_PLACEMENTS_HEALTH_PORT -> "a metrics host port this declare names is "
                    + "another live placement's health port";
            case HEALTH_PORT_IS_ANOTHER_PLACEMENTS_METRICS_PORT -> "this placement's health port is a metrics host port "
                    + "another live placement already maps";
            case TWO_SOURCE_PORTS_ONE_HOST_PORT -> "two source ports are mapped to one host port";
        };
        return detailed(ErrorCode.METRICS_PORT_CONFLICT, request, what);
    }

    /** Plan 011 M-Q5: a loaded package version is immutable; the digest is never echoed. */
    @ExceptionHandler(PackageVersionMismatchException.class)
    public ResponseEntity<ProblemBody> handlePackageVersionMismatch(PackageVersionMismatchException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.PACKAGE_VERSION_MISMATCH, request,
                "the declared package version's content differs from the one already recorded");
    }

    /** {@code OR-88}: a pending or active instance of this environment already holds the name. The name is not echoed. */
    @ExceptionHandler(InstanceNameTakenException.class)
    public ResponseEntity<ProblemBody> handleInstanceNameTaken(InstanceNameTakenException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.CONFLICT, request, "an instance in this environment already has that name");
    }

    /** Plan 008 OQ-8.9 (a): a push-metrics package cannot be attributed to one of several placements. Names no metric. */
    @ExceptionHandler(PushMetricsPlacementRefusedException.class)
    public ResponseEntity<ProblemBody> handlePushMetricsPlacementRefused(PushMetricsPlacementRefusedException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.CONFLICT, request,
                "this package declares push metrics, which cannot be attributed to one of several placements");
    }

    /** {@code BE-D53}: the last live placement is released with its instance (undeclare), not retired alone. */
    @ExceptionHandler(LastLivePlacementException.class)
    public ResponseEntity<ProblemBody> handleLastLivePlacement(LastLivePlacementException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.CONFLICT, request,
                "this is the instance's last live placement; undeclare the instance instead");
    }

    /** {@code BE-D53}: names neither the request nor the fleet that holds the placement. */
    @ExceptionHandler(PlacementInFlightException.class)
    public ResponseEntity<ProblemBody> handlePlacementInFlight(PlacementInFlightException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.CONFLICT, request, "work is in flight on this placement; let it land, then retire it");
    }

    /** Fable F-3: a pending scheduled apply names the placement; names neither the schedule nor its submitter. */
    @ExceptionHandler(PlacementSchedulePendingException.class)
    public ResponseEntity<ProblemBody> handlePlacementSchedulePending(PlacementSchedulePendingException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.CONFLICT, request,
                "a scheduled apply is still pending on this placement; cancel it, then retire the placement");
    }

    /**
     * Plan 012 T5, {@code ops-501.md} section 4.5: the data plane refused a step of an undeclare. One status per reason, a
     * FIXED detail per reason, naming the kind of work and never a request, a fleet or an id. {@code NOT_RETIRING} is the one
     * that should be unreachable (the half-window guard exists to keep it so): reaching it after the control plane was written
     * is an inconsistency to investigate, so it is an internal error, never a claim of success.
     */
    @ExceptionHandler(RetirementRefusedException.class)
    public ResponseEntity<ProblemBody> handleRetirementRefused(RetirementRefusedException exception,
            HttpServletRequest request) {
        return switch (exception.reason()) {
            case NOT_FOUND -> problem(ErrorCode.NOT_FOUND, request);
            case REQUEST_IN_FLIGHT -> detailed(ErrorCode.CONFLICT, request,
                    "a request is in flight on this instance; let it land, then undeclare it");
            case FLEET_IN_FLIGHT -> detailed(ErrorCode.CONFLICT, request,
                    "a running fleet still reserves this instance; let it finish, then undeclare it");
            case SCHEDULE_PENDING -> detailed(ErrorCode.CONFLICT, request,
                    "a scheduled apply is still pending on this instance; cancel the instance's pending schedules, then undeclare it");
            case NOT_RETIRING -> {
                LOG.error("undeclare: step 5 found no undeclare in progress after the control plane was written");
                yield problem(ErrorCode.INTERNAL_ERROR, request);
            }
        };
    }

    /** Plan 012 T5: the half-window guard. The control plane was not written; retry. */
    @ExceptionHandler(UndeclareWindowException.class)
    public ResponseEntity<ProblemBody> handleUndeclareWindow(UndeclareWindowException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.SERVICE_UNAVAILABLE, request,
                "this undeclare started too long ago to continue safely; retry after the roll-back window has passed");
    }

    /** {@code BE-D47} (W2 review W2A-2): names no request. */
    @ExceptionHandler(InstanceRequestInFlightException.class)
    public ResponseEntity<ProblemBody> handleInstanceRequestInFlight(InstanceRequestInFlightException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.CONFLICT, request,
                "an instance-wide request is in flight on this instance; let it land, then add the placement");
    }

    /** B2: names the FIELD outside the target's envelope, never the value. */
    @ExceptionHandler(PlacementOutsideEnvelopeException.class)
    public ResponseEntity<ProblemBody> handleOutsideEnvelope(PlacementOutsideEnvelopeException exception,
            HttpServletRequest request) {
        String field = DeclareValidationException.FIELD.matcher(exception.field()).matches()
                ? "placement." + exception.field() : "placement";
        return detailed(ErrorCode.VALIDATION_FAILED, request, "field '" + field + "' is outside the target's envelope");
    }

    /** OR-33: the second of two operators staging from one base loses. The current version is not echoed. */
    @ExceptionHandler(StaleStateVersionException.class)
    public ResponseEntity<ProblemBody> handleStaleStateVersion(StaleStateVersionException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.CONFLICT, request, "a newer stateVersion exists; refetch and stage again");
    }

    /** OR-34: names the RULE from a closed set, never a key, a value or a reference the request carried. */
    @ExceptionHandler(StageRefusedException.class)
    public ResponseEntity<ProblemBody> handleStageRefused(StageRefusedException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.VALIDATION_FAILED, request, exception.reason().detail());
    }

    /**
     * {@code acceptRuntimeConfig}'s read-back (plan 010 T10.4, {@code OR-104}). The placement is not this instance's: the same
     * {@code 404} as out of scope. The host did not answer: {@code 503}, "a dependency did not answer; retry" (Q-A5 (a)).
     * Anything else is a refusal of the FILE, named by a closed reason and never by a line of it ({@code I6}).
     */
    @ExceptionHandler(ReadBackRefusedException.class)
    public ResponseEntity<ProblemBody> handleReadBackRefused(ReadBackRefusedException exception,
            HttpServletRequest request) {
        return switch (exception.reason()) {
            case PLACEMENT_NOT_FOUND -> problem(ErrorCode.NOT_FOUND, request);
            case HOST_UNREADABLE -> detailed(ErrorCode.SERVICE_UNAVAILABLE, request,
                    "a dependency did not answer; retry");
            default -> detailed(ErrorCode.VALIDATION_FAILED, request,
                    "the host's config file was refused: " + exception.reason().wire());
        };
    }

    /** D-ACCEPT §3.7: names the package's field key and a closed rule code, never the value. */
    @ExceptionHandler(AcceptRefusedException.class)
    public ResponseEntity<ProblemBody> handleAcceptRefused(AcceptRefusedException exception, HttpServletRequest request) {
        return detailed(ErrorCode.VALIDATION_FAILED, request, exception.detail());
    }

    /**
     * The data plane refused an apply, a stop or an observation (plan 010 T6.3). One status per reason, a FIXED detail
     * per reason: nothing the request carried (a placement, a generation, an actor) and no id is echoed.
     */
    @ExceptionHandler(ApplyRefusedException.class)
    public ResponseEntity<ProblemBody> handleApplyRefused(ApplyRefusedException exception,
            HttpServletRequest request) {
        return switch (exception.reason()) {
            case NOT_FOUND -> problem(ErrorCode.NOT_FOUND, request);
            case GENERATION_NOT_STAGED -> detailed(ErrorCode.VALIDATION_FAILED, request,
                    "the generation was never staged for this instance");
            case SCOPE_BUSY -> detailed(ErrorCode.CONFLICT, request,
                    "an apply or restart is already in flight on this placement");
            case STOP_IN_FLIGHT -> detailed(ErrorCode.CONFLICT, request,
                    "a stop is already in flight on this scope");
            case GENERATION_NOT_ABOVE_WATERMARK -> detailed(ErrorCode.CONFLICT, request,
                    "a generation at or above this one was already staged or applied here");
            case CONCURRENT_ADMISSION -> detailed(ErrorCode.CONFLICT, request,
                    "another request for this scope was admitted first; observe it and retry");
            case ROLLBACK_TARGET_NOT_ELIGIBLE -> detailed(ErrorCode.CONFLICT, request,
                    "that generation was never written to this placement, so it is not a rollback target");
            case UNAPPLIED_DRAFT_PENDING -> detailed(ErrorCode.CONFLICT, request,
                    "a configuration edit is staged and not yet applied here; apply or discard it before changing a log level");
        };
    }

    /**
     * A fleet refused whole ({@code OR-57}; plan 010 T11.2): a {@code 403 FORBIDDEN} that carries the refused members.
     * Not a {@link ProblemBody}: the contract's {@code FleetRefused} extends {@code Problem} with
     * {@code refusedMembers[]}.
     */
    @ExceptionHandler(FleetRefusedException.class)
    public ResponseEntity<FleetRefusedBody> handleFleetRefused(FleetRefusedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(FleetRefusedBody.of(correlationId(request), exception.refused()));
    }

    /** An authorized member failed a semantic check: names its ordinal and the rule from a closed set (fixed text). */
    @ExceptionHandler(FleetValidationException.class)
    public ResponseEntity<ProblemBody> handleFleetValidation(FleetValidationException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.VALIDATION_FAILED, request, exception.getMessage());
    }

    /** A member's placement is reserved by another running fleet: names the member's ordinal, never the other fleet. */
    @ExceptionHandler(FleetConflictException.class)
    public ResponseEntity<ProblemBody> handleFleetConflict(FleetConflictException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.CONFLICT, request, exception.getMessage());
    }

    /**
     * A strategy the data plane does not run. Every strategy the API accepts is served since plan 012 T2/T7
     * ({@code OR-121}), and {@code scheduled} is answered {@code 422} before the data plane is asked
     * ({@link #handleScheduledToken}), so this is the data plane's own defence and is not expected from this gateway.
     */
    @ExceptionHandler(NotImplementedStrategyException.class)
    public ResponseEntity<ProblemBody> handleNotImplementedStrategy(NotImplementedStrategyException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.NOT_IMPLEMENTED, request, "this restart strategy is not available in this release");
    }

    /** {@code strategy: "scheduled"} (plan 012 T7, {@code OR-121} naming {@code OR-53}/{@code OR-60}): a fixed detail per operation. */
    @ExceptionHandler(ScheduledTokenException.class)
    public ResponseEntity<ProblemBody> handleScheduledToken(ScheduledTokenException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.VALIDATION_FAILED, request, exception.where().detail());
    }

    /**
     * A schedule the data plane refused to record or to cancel (plan 012 T7): the window is the caller's to fix (422), and a
     * schedule that is no longer pending is a conflict. FIXED text per reason: the request's instants and the schedule's
     * ids are never echoed.
     */
    @ExceptionHandler(ScheduleRefusedException.class)
    public ResponseEntity<ProblemBody> handleScheduleRefused(ScheduleRefusedException exception,
            HttpServletRequest request) {
        return switch (exception.reason()) {
            case NOT_BEFORE_NOT_IN_THE_FUTURE -> detailed(ErrorCode.VALIDATION_FAILED, request,
                    "schedule.notBefore must be in the future; a change that is already due is an ordinary apply");
            case NOT_BEFORE_TOO_FAR_AHEAD -> detailed(ErrorCode.VALIDATION_FAILED, request,
                    "schedule.notBefore is further ahead than this installation allows");
            case NOT_AFTER_TOO_FAR_AHEAD -> detailed(ErrorCode.VALIDATION_FAILED, request,
                    "schedule.notAfter is further ahead than this installation allows");
            case NOT_PENDING -> detailed(ErrorCode.CONFLICT, request,
                    "that schedule is no longer pending: it was admitted, missed, refused or cancelled already");
        };
    }

    /**
     * {@code setLogLevel} is understood and refused (plan 010 T9.3/T9.4, {@code OR-103}): one status per reason and a FIXED
     * detail per reason. Nothing the request carried is echoed.
     */
    @ExceptionHandler(LogLevelChangeRefusedException.class)
    public ResponseEntity<ProblemBody> handleLogLevelChangeRefused(LogLevelChangeRefusedException exception,
            HttpServletRequest request) {
        return switch (exception.reason()) {
            case LIVE_ON_DIRECT_HTTP -> detailed(ErrorCode.VALIDATION_FAILED, request,
                    "a direct-HTTP placement can only change its log level through configuration");
            case LIVE_NOT_BUILT -> detailed(ErrorCode.NOT_IMPLEMENTED, request,
                    "a live log-level change is not available in this release; change the level through configuration");
            case NO_LOGGER_SUPPORT -> detailed(ErrorCode.VALIDATION_FAILED, request,
                    "this package does not declare logger support, so its log level cannot be changed");
        };
    }

    @ExceptionHandler(DeclareValidationException.class)
    public ResponseEntity<ProblemBody> handleDeclareValidation(DeclareValidationException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.VALIDATION_FAILED, request, "field '" + exception.field() + "' failed validation");
    }

    /** Plan 013 T4, {@code UI-D27}: an unknown {@code connectionType} or attribute key, or a missing required one. */
    @ExceptionHandler(ConnectionValidationException.class)
    public ResponseEntity<ProblemBody> handleConnectionValidation(ConnectionValidationException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.VALIDATION_FAILED, request, "field '" + exception.field() + "' failed validation");
    }

    /**
     * Plan 013 T4, {@code BE-D66}, {@code OR-130}: {@code createConnection}/{@code updateConnection}/{@code
     * deleteConnection}'s conflicts. {@code IN_USE} is {@code deleteConnection} only; the fixed message is the
     * exception's own (never a name, a count, or another connection's id).
     */
    @ExceptionHandler(ConnectionConflictException.class)
    public ResponseEntity<ProblemBody> handleConnectionConflict(ConnectionConflictException exception,
            HttpServletRequest request) {
        return detailed(ErrorCode.CONFLICT, request, exception.getMessage());
    }

    /**
     * {@code testBeforeSave: true} ran and failed (plan 013 T4, {@code BE-D66}): {@code 422
     * CONNECTION_TEST_FAILED}, the peer's own rejection reason, and the non-secret fields of the request echoed
     * back so the form can be left filled in — secret-typed attributes are stripped ({@code I6}). Not a
     * {@link ProblemBody}: the body carries {@code name} and {@code attributes} beside the usual fields.
     */
    @ExceptionHandler(ConnectionTestFailedException.class)
    public ResponseEntity<ConnectionTestFailedBody> handleConnectionTestFailed(ConnectionTestFailedException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(ErrorCode.CONNECTION_TEST_FAILED.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ConnectionTestFailedBody.of(correlationId(request), exception));
    }

    /** A gate id as the validator spells it: {@code 1}, {@code 3}, {@code 5b}, or the catalog's {@code version}. */
    private static final Pattern GATE_ID = Pattern.compile("[0-9a-z]{1,8}");

    /**
     * {@code 422 VALIDATION_FAILED}. The detail names the gate(s) - "the body names the gate" - and is built from
     * fixed text and ids that match {@link #GATE_ID}, never from the exception's message or the document: a
     * package may carry a secret-shaped default, and this reaches a response body ({@code I6}).
     */
    @ExceptionHandler(PackageRejectedException.class)
    public ResponseEntity<ProblemBody> handlePackageRejected(PackageRejectedException exception,
            HttpServletRequest request) {
        List<String> gates = exception.gates().stream().filter(g -> GATE_ID.matcher(g).matches()).toList();
        String detail = gates.contains(PackageRejectedException.VERSION_GATE)
                ? "this package version is already loaded with different content"
                : "the package was refused by load gate(s): " + String.join(", ", gates);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.withDetail(ErrorCode.VALIDATION_FAILED, correlationId(request), detail));
    }

    @ExceptionHandler(AuthenticationUnavailableException.class)
    public ResponseEntity<ProblemBody> handleUnavailable(AuthenticationUnavailableException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.SERVICE_UNAVAILABLE, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotAcceptableException.class
    })
    public ResponseEntity<ProblemBody> handleBadRequest(Exception exception, HttpServletRequest request) {
        return problem(ErrorCode.BAD_REQUEST, request);
    }

    /**
     * An unmapped route. The launcher sets {@code spring.web.resources.add-mappings=false}, so
     * Spring Boot's own static-resource handler is never registered at {@code /**} ahead of
     * {@link NoRouteHandler} in THIS application — {@code NoResourceFoundException} should
     * therefore never actually reach this advice in production. The mapping is kept as defence
     * in depth regardless: Spring 6.2 answers a resource miss with
     * {@code NoResourceFoundException}, which, unmapped, would fall to the catch-all as a
     * {@code 500} instead of the {@code 404} every other "no handler" signal renders here.
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class
    })
    public ResponseEntity<ProblemBody> handleNoRoute(Exception exception, HttpServletRequest request) {
        return problem(ErrorCode.NOT_FOUND, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemBody> handleMethodNotAllowed(HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return problem(ErrorCode.METHOD_NOT_ALLOWED, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemBody> handleUnexpected(Exception exception, HttpServletRequest request) {
        UUID correlationId = correlationId(request);
        // ⛔ Never log the request body — only the correlation id and the exception itself.
        LOG.error("unhandled exception, correlationId={}", correlationId, exception);
        return problem(ErrorCode.INTERNAL_ERROR, correlationId);
    }

    /** ⛔ {@code detail} is fixed text plus closed identifiers only - see {@link ProblemBody#withDetail}. */
    private static ResponseEntity<ProblemBody> detailed(ErrorCode code, HttpServletRequest request, String detail) {
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.withDetail(code, correlationId(request), detail));
    }

    private static ResponseEntity<ProblemBody> problem(ErrorCode code, HttpServletRequest request) {
        return problem(code, correlationId(request));
    }

    private static ResponseEntity<ProblemBody> problem(ErrorCode code, UUID correlationId) {
        return ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemBody.of(code, correlationId));
    }

    private static UUID correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(CorrelationIdFilter.CORRELATION_ID_ATTRIBUTE);
        return value instanceof UUID uuid ? uuid : UUID.randomUUID();
    }
}
