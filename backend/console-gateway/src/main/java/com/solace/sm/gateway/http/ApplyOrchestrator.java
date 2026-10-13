package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.apply.ApplyAcceptedView;
import com.solace.sm.dp.api.apply.ApplyObservationView;
import com.solace.sm.dp.api.apply.ApplySchedulePort;
import com.solace.sm.dp.api.apply.ApplyService;
import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.apply.ObserveQuery;
import com.solace.sm.dp.api.apply.RestoreRuntimeCommand;
import com.solace.sm.dp.api.apply.RollbackCommand;
import com.solace.sm.dp.api.apply.ScheduleAcceptedView;
import com.solace.sm.dp.api.apply.ScheduleCommand;
import com.solace.sm.dp.api.apply.ScheduleView;
import com.solace.sm.dp.api.apply.StopCommand;
import com.solace.sm.dp.api.apply.SubmitApplyCommand;
import com.solace.sm.dp.api.inventory.InstanceView;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code submitApply}, {@code observeApply}, {@code stopInstance} (plan 010 T6.3), {@code rollbackApply} (T8.1) and {@code restoreRuntimeConfig} (T10.2), composed here because the gateway
 * is the one module permitted both {@code -api}s. A translator and an authorizer; the engine, the journal and the
 * fences are the data plane's.
 *
 * <p><b>The order of the checks is part of the contract.</b> The interceptor has already passed the operation's own
 * verb at the instance named by the path ({@code config.apply}, {@code config.view}, {@code instance.stop}) - that is
 * also what makes an out-of-scope instance a {@code 404} before anything below runs. Then, for {@code submitApply}:
 * <ol>
 *   <li>{@code strategy: "scheduled"} is {@code 422} naming the {@code schedule} field - never {@code 403}, whatever else
 *       the caller lacks (the contract: the value is read as a string FIRST). A schedule is a WINDOW on this operation
 *       (plan 012 T7, {@code OPS-Q9} Sch-2), not a fifth strategy;</li>
 *   <li>a strategy that restarts anything also needs {@code instance.restart} at the same instance ({@code D10}'s
 *       two-sided rule), naming the verb on denial;</li>
 *   <li>a strategy that overrides a safety - {@code rolling-no-validation} and {@code restart-all} - also needs
 *       {@code instance.restart.overrideSafety} ({@code OPS-Q6} B-1), asked AFTER {@code instance.restart};</li>
 *   <li>the instance must be ACTIVE and the placement one of its live placements - otherwise the same {@code 404} as an
 *       out-of-scope instance.</li>
 * </ol>
 *
 * <p>⛔ <b>Everything is authorized at the OWNING INSTANCE (OR-16).</b> A placement is an operational child identity,
 * not a grantable level: there is no placement-scoped binding path here.
 *
 * <p>{@code I5}: the organization and the actor are the session's, on every call. Nothing in a body or a query names
 * either.
 */
@Component
public class ApplyOrchestrator {

    /**
     * The strategies that RUN. Plan 012 T2 ({@code OR-121}) serves the two ungated ones. {@code scheduled} is in the
     * contract's enum but is not one of these: plan 012 T7 builds the schedule as a window on {@code submitApply} and
     * {@code rollbackApply}, and the bare token is {@code 422} ({@link #requireRuns}).
     */
    static final Set<String> SERVED_STRATEGIES = Set.of("manual", "rolling-health-gated", "rolling-no-validation",
            "restart-all");

    /** The only strategy that restarts nothing, so the only one for which {@code config.apply} alone is enough. */
    private static final String RESTARTS_NOTHING = "manual";

    /**
     * The strategies that restart without the health gate's protection, and so need the second, deliberate verb
     * ({@code OPS-Q6} B-1: {@code instance.restart.overrideSafety}, Administrator and Operator).
     */
    static final Set<String> OVERRIDES_SAFETY = Set.of("rolling-no-validation", "restart-all");

    private final DeclareOrchestrator instances;
    private final ApplyService apply;
    private final AuthorizationService authorization;
    private final ApplySchedulePort schedules;

    public ApplyOrchestrator(DeclareOrchestrator instances, ApplyService apply, AuthorizationService authorization,
            ApplySchedulePort schedules) {
        this.instances = Objects.requireNonNull(instances, "instances");
        this.apply = Objects.requireNonNull(apply, "apply");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.schedules = Objects.requireNonNull(schedules, "schedules");
    }

    public ApplyAccepted submit(Session session, UUID instanceId, SubmitApplyRequest request) {
        requireRuns(request.strategy(), ScheduledTokenException.Where.APPLY);
        requireRestartVerbs(session, instanceId, request.strategy());
        InstanceView view = requireInstance(session, instanceId);
        requirePlacement(view, request.placementId());
        ApplyAcceptedView accepted = apply.submitApply(session.organizationId(), instanceId,
                new SubmitApplyCommand(request.placementId(), request.generationNumber(), request.strategy(),
                        SubmitApplyRequest.confirmation(request.strategy(), session.principal().id()),
                        session.principal().id()));
        return new ApplyAccepted(accepted.stateVersion(), null, accepted.applyId());
    }

    /**
     * Operator rollback (OR-42): {@code config.rollback} at the instance has already passed in the interceptor. The same
     * order as {@link #submit}: a strategy this API does not serve is {@code 501} before any other verb is asked, a
     * strategy that restarts anything also needs {@code instance.restart}, then the instance and placement must be the
     * caller's. Which generation is eligible, and the copy-forward, are the data plane's.
     */
    public ApplyAccepted rollback(Session session, UUID instanceId, RollbackRequest request) {
        requireRuns(request.strategy(), ScheduledTokenException.Where.ROLLBACK);
        requireRestartVerbs(session, instanceId, request.strategy());
        InstanceView view = requireInstance(session, instanceId);
        requirePlacement(view, request.placementId());
        AgentPlacements.refuseIfAgent(view, request.placementId());      // plan 015 T3.3 (D-C2): refused at admission
        ApplyAcceptedView accepted = apply.rollbackApply(session.organizationId(), instanceId,
                new RollbackCommand(request.placementId(), request.applyId(), request.generationNumber(),
                        request.strategy(), SubmitApplyRequest.confirmation(request.strategy(), session.principal().id()),
                        session.principal().id()));
        return new ApplyAccepted(accepted.stateVersion(), null, accepted.applyId());
    }

    /**
     * Restore-runtime (OR-45): {@code config.apply} at the instance has already passed in the interceptor. The same
     * order as {@link #submit}: a strategy this API does not serve is {@code 501} before any other verb is asked, a
     * strategy that restarts anything also needs {@code instance.restart} (the default, {@code manual}, never asks) and
     * the two ungated ones {@code instance.restart.overrideSafety}, then the instance and placement must be the caller's.
     * Which generation is "desired", and the copy-forward, are the data plane's.
     */
    public ApplyAccepted restoreRuntime(Session session, UUID instanceId, RestoreRuntimeRequest request) {
        requireRuns(request.strategy(), ScheduledTokenException.Where.RESTORE);
        requireRestartVerbs(session, instanceId, request.strategy());
        InstanceView view = requireInstance(session, instanceId);
        requirePlacement(view, request.placementId());
        AgentPlacements.refuseIfAgent(view, request.placementId());      // plan 015 T3.3 (D-C2): refused at admission
        ApplyAcceptedView accepted = apply.restoreRuntime(session.organizationId(), instanceId,
                new RestoreRuntimeCommand(request.placementId(), request.strategy(),
                        SubmitApplyRequest.confirmation(request.strategy(), session.principal().id()),
                        session.principal().id()));
        return new ApplyAccepted(accepted.stateVersion(), null, accepted.applyId());
    }

    /**
     * A scheduled apply (plan 012 T7, {@code OPS-Q9} Sch-2): the SAME checks as {@link #submit} in the SAME order - the
     * submitter must hold the verbs NOW, so a mistake is a {@code 403} at 14:00 and not a refusal at 02:00 - and then the
     * request is recorded instead of admitted. Nothing is admitted, no fence is taken and no generation is minted; the
     * submitter's verbs are required AGAIN when the window opens ({@code ScheduleFirer}).
     */
    public ScheduleAccepted scheduleApply(Session session, UUID instanceId, SubmitApplyRequest request) {
        requireRuns(request.strategy(), ScheduledTokenException.Where.APPLY);
        requireRestartVerbs(session, instanceId, request.strategy());
        InstanceView view = requireInstance(session, instanceId);
        requirePlacement(view, request.placementId());
        ScheduleWindow window = request.scheduleWindow();
        ScheduleAcceptedView accepted = schedules.schedule(session.organizationId(), instanceId,
                ScheduleCommand.apply(request.placementId(), request.generationNumber(), request.strategy(),
                        SubmitApplyRequest.confirmation(request.strategy(), session.principal().id()),
                        window.notBefore(), window.notAfter(), session.principal().id()));
        return new ScheduleAccepted(accepted.stateVersion(), accepted.scheduleId());
    }

    /**
     * A scheduled rollback (plan 012 T7): as {@link #rollback} up to the point where it would copy forward, which happens
     * when the window opens and not now (a copy at 14:00 would make old content the instance's desired head twelve hours
     * early, design 6.3).
     */
    public ScheduleAccepted scheduleRollback(Session session, UUID instanceId, RollbackRequest request) {
        requireRuns(request.strategy(), ScheduledTokenException.Where.ROLLBACK);
        requireRestartVerbs(session, instanceId, request.strategy());
        InstanceView view = requireInstance(session, instanceId);
        requirePlacement(view, request.placementId());
        AgentPlacements.refuseIfAgent(view, request.placementId());      // plan 015 T3.3 (D-C2): refused at admission
        ScheduleWindow window = request.scheduleWindow();
        ScheduleAcceptedView accepted = schedules.schedule(session.organizationId(), instanceId,
                ScheduleCommand.rollback(request.placementId(), request.applyId(), request.generationNumber(),
                        request.strategy(),
                        SubmitApplyRequest.confirmation(request.strategy(), session.principal().id()),
                        window.notBefore(), window.notAfter(), session.principal().id()));
        return new ScheduleAccepted(accepted.stateVersion(), accepted.scheduleId());
    }

    /** {@code listApplySchedules}: {@code config.view} at the instance has passed; only an ACTIVE instance is listed. */
    public ApplyScheduleList listSchedules(Session session, UUID instanceId, int limit) {
        requireInstance(session, instanceId);
        return new ApplyScheduleList(schedules.list(session.organizationId(), instanceId, limit).stream()
                .map(ApplyOrchestrator::toWire).toList());
    }

    /**
     * {@code cancelApplySchedule}: {@code config.apply} at the instance has passed - ANY holder, not only the submitter, so
     * a colleague can stop a change the submitter scheduled and then went off shift. The cancelling principal is the
     * session's and is recorded; it is never a request field.
     */
    public ApplySchedule cancelSchedule(Session session, UUID instanceId, UUID scheduleId) {
        requireInstance(session, instanceId);
        return toWire(schedules.cancel(session.organizationId(), instanceId, scheduleId, session.principal().id()));
    }

    public ApplyAccepted stop(Session session, UUID instanceId, StopRequest request) {
        InstanceView view = requireInstance(session, instanceId);
        UUID placementId = request == null ? null : request.placementId();
        if (placementId != null) {
            requirePlacement(view, placementId);
        }
        ApplyAcceptedView accepted = apply.stopInstance(session.organizationId(), instanceId,
                new StopCommand(placementId, session.principal().id()));
        return new ApplyAccepted(accepted.stateVersion(), null, accepted.applyId());
    }

    public ApplyObservationList observe(Session session, UUID instanceId, UUID applyId, Long sinceStateVersion,
            int limit) {
        requireInstance(session, instanceId);
        List<ApplyObservationView> views = apply.observeApply(session.organizationId(), instanceId,
                new ObserveQuery(applyId, sinceStateVersion, limit));
        return new ApplyObservationList(null, views.stream().map(ApplyOrchestrator::toWire).toList());
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /**
     * {@code strategy} must be one the engine RUNS. The only token that is neither is {@code scheduled} (the contract keeps it
     * in its enum so an old client is told what to do instead): {@code 422}, before any secondary verb is asked.
     */
    private static void requireRuns(String strategy, ScheduledTokenException.Where where) {
        if (SERVED_STRATEGIES.contains(strategy)) {
            return;
        }
        if ("scheduled".equals(strategy)) {
            throw new ScheduledTokenException(where);
        }
        throw new NotImplementedStrategyException(strategy);
    }

    /**
     * The secondary verbs a strategy asks for, in the contract's order: {@code instance.restart} for anything that
     * restarts, then {@code instance.restart.overrideSafety} for the two that override a safety (a denial names the verb).
     */
    private void requireRestartVerbs(Session session, UUID instanceId, String strategy) {
        if (RESTARTS_NOTHING.equals(strategy)) {
            return;
        }
        requireAlso(session, Permission.INSTANCE_RESTART, instanceId);
        if (OVERRIDES_SAFETY.contains(strategy)) {
            requireAlso(session, Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, instanceId);
        }
    }

    /** A secondary verb: a denial names WHICH verb; a missing or out-of-scope resource stays an indistinguishable 404. */
    private void requireAlso(Session session, Permission permission, UUID instanceId) {
        try {
            authorization.require(session.principal(), permission,
                    ResourceRef.of(ResourceKind.INSTANCE, instanceId, new TenantId(session.organizationId())));
        } catch (AccessDeniedException e) {
            throw new SecondaryVerbDeniedException(permission);
        }
    }

    /** Authorization at the instance has passed; only an ACTIVE row is actionable, else the same 404 as out of scope. */
    private InstanceView requireInstance(Session session, UUID instanceId) {
        return instances.get(session, instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(ResourceKind.INSTANCE));
    }

    private static void requirePlacement(InstanceView view, UUID placementId) {
        if (view.placements().stream().noneMatch(p -> p.placementId().equals(placementId))) {
            throw new ResourceNotFoundException(ResourceKind.INSTANCE);
        }
    }

    private static ApplySchedule toWire(ScheduleView v) {
        return new ApplySchedule(v.scheduleId(), v.placementId(), v.operation(), v.generation(), v.rollbackApplyId(),
                v.rollbackGeneration(), v.strategy(), v.notBefore().toString(), v.notAfter().toString(),
                v.requestedAt().toString(), v.state(), v.applyId(), v.targetGeneration(), v.admittedGeneration(),
                v.reason(), v.resolvedAt() == null ? null : v.resolvedAt().toString(), v.stateVersion());
    }

    private static ApplyObservation toWire(ApplyObservationView view) {
        List<ApplyObservation.Step> steps = view.steps().stream()
                .map(s -> new ApplyObservation.Step(s.step(), s.placementId(), s.outcome(), s.reason(), s.gateProved(),
                        s.changedPaths(), s.changedPathsTruncated(), s.otherContentChanged()))
                .toList();
        return new ApplyObservation(view.stateVersion(), null, view.applyId(), view.operation(), view.placementId(),
                view.generation(), view.requestedAt() == null ? null : view.requestedAt().toString(), view.state(),
                view.terminal(), view.reconcileReason(), steps);
    }
}
