package com.solace.sm.gateway.http;

import com.solace.sm.cp.api.authz.AccessDeniedException;
import com.solace.sm.cp.api.authz.AuthorizationService;
import com.solace.sm.cp.api.authz.Permission;
import com.solace.sm.cp.api.authz.PrincipalRef;
import com.solace.sm.cp.api.authz.ReachAuthorization;
import com.solace.sm.cp.api.authz.ResourceKind;
import com.solace.sm.cp.api.authz.ResourceNotFoundException;
import com.solace.sm.cp.api.authz.ResourceRef;
import com.solace.sm.cp.api.authz.TenantId;
import com.solace.sm.cp.api.session.Session;
import com.solace.sm.dp.api.apply.NotImplementedStrategyException;
import com.solace.sm.dp.api.fleet.FleetAcceptedView;
import com.solace.sm.dp.api.fleet.FleetApplyCommand;
import com.solace.sm.dp.api.fleet.FleetApplyService;
import com.solace.sm.dp.api.fleet.FleetMemberView;
import com.solace.sm.dp.api.fleet.FleetObservationView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code submitFleetApply} and {@code observeFleetApply} (plan 010 T11.2; {@code REQ-013}, {@code OR-57};
 * {@code fleet-apply.md} sections 2, 3 and 7), composed here because the gateway is the one module permitted both
 * {@code -api}s. A translator and an authorizer; the fleet, its batches and its scheduler are the data plane's.
 *
 * <h2>One enforcement point, member by member</h2>
 * The interceptor has passed only the reach pre-check (the operation's verb held at one or more grants in the caller's
 * organization): it authorizes NO member. Then, for {@code submitFleetApply}, in this order:
 * <ol>
 *   <li>{@code strategy: "scheduled"} is {@code 422} - never {@code 403}, whatever the caller lacks (as
 *       {@code submitApply}); a schedule is a window on {@code submitApply} / {@code rollbackApply}, and a scheduled
 *       FLEET is out of scope (plan 012 T7);</li>
 *   <li>every member is authorized by {@link AuthorizationService#require} at ITS OWN INSTANCE ({@code OR-16}: never a
 *       placement), for {@code config.apply}, for {@code instance.restart} too when the strategy restarts anything, and
 *       for {@code instance.restart.overrideSafety} as well when it is {@code rolling-no-validation} or
 *       {@code restart-all} ({@code OPS-Q6} B-1; a member refused on it is {@code FORBIDDEN}, {@code OR-65} Q-F5).
 *       There is no fleet-level {@code require} and no second authorization path. A refused member carries exactly the
 *       code its own single call would have answered, {@code FORBIDDEN} or {@code NOT_FOUND}, for an id the caller named;
 *   <li>a fleet with any refused member is refused WHOLE ({@code 403}, listing them) unless
 *       {@code proceedWithAuthorized}, and is refused whole regardless when nothing is left to apply;</li>
 *   <li>only then is the data plane called, and it validates AUTHORIZED members only: a {@code 422} saying "instance X has
 *       no generation 9" would otherwise confirm that X exists to a caller who cannot see it.</li>
 * </ol>
 * Authorization is decided here, at submit; the data plane cannot call the control plane ({@code I7}), so a later batch
 * does not re-authorize (Q-F1 (a)).
 *
 * <p>{@code I5}: the organization and the actor are the session's, on every call. Nothing in a body names either.
 */
@Component
public class FleetOrchestrator {

    private final FleetApplyService fleets;
    private final AuthorizationService authorization;
    private final ReachAuthorization reach;

    public FleetOrchestrator(FleetApplyService fleets, AuthorizationService authorization, ReachAuthorization reach) {
        this.fleets = Objects.requireNonNull(fleets, "fleets");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.reach = Objects.requireNonNull(reach, "reach");
    }

    public FleetAccepted submit(Session session, UUID correlationId, FleetApplyRequest request) {
        if (!ApplyOrchestrator.SERVED_STRATEGIES.contains(request.strategy())) {
            // `scheduled` is a window on submitApply / rollbackApply, and a scheduled FLEET is a different design (plan 012 T7)
            if ("scheduled".equals(request.strategy())) {
                throw new ScheduledTokenException(ScheduledTokenException.Where.FLEET);
            }
            throw new NotImplementedStrategyException(request.strategy());
        }
        boolean restarts = !"manual".equals(request.strategy());
        boolean overridesSafety = ApplyOrchestrator.OVERRIDES_SAFETY.contains(request.strategy());
        PrincipalRef principal = session.principal();

        List<FleetApplyCommand.Member> members = new ArrayList<>();
        List<FleetRefusedException.RefusedMember> refused = new ArrayList<>();
        for (int ordinal = 0; ordinal < request.members().size(); ordinal++) {
            FleetApplyRequest.Member named = request.members().get(ordinal);
            FleetApplyCommand.RefusalCode verdict = verdict(session, named.instanceId(), restarts, overridesSafety);
            members.add(new FleetApplyCommand.Member(named.instanceId(), named.placementId(), named.generationNumber(),
                    verdict));
            if (verdict != null) {
                refused.add(new FleetRefusedException.RefusedMember(ordinal, named.instanceId(), named.placementId(),
                        verdict.name()));
            }
        }
        if (!refused.isEmpty() && (!request.proceedWithAuthorized() || refused.size() == members.size())) {
            throw new FleetRefusedException(refused);
        }

        FleetAcceptedView accepted = fleets.submitFleet(session.organizationId(),
                new FleetApplyCommand(request.solutionId(), members, request.strategy(),
                        SubmitApplyRequest.confirmation(request.strategy(), principal.id()),
                        request.batchSize(), request.onFailure(), principal.id(), correlationId));
        return new FleetAccepted(accepted.stateVersion(), null, accepted.fleetId(), accepted.state(),
                accepted.members().stream().map(FleetOrchestrator::toWire).toList());
    }

    public FleetObservation observe(Session session, UUID fleetId) {
        FleetObservationView view = fleets.observeFleet(session.organizationId(), fleetId)
                .orElseThrow(() -> notFound());
        // The interceptor's pre-check authorized NO item: filter by the operation's own verb at each member's instance.
        // A refused member is filtered like any other (Q-F8 (a)): it is shown only to a viewer who can see its instance,
        // so nothing about a hidden instance reaches a third viewer who never named it.
        Set<UUID> visibleInstances = reach.authorizedSubset(session.principal(), Permission.CONFIG_VIEW,
                ResourceKind.INSTANCE, view.members().stream().map(FleetMemberView::instanceId).distinct().toList());
        List<FleetMember> visible = view.members().stream().filter(m -> visibleInstances.contains(m.instanceId()))
                .map(FleetOrchestrator::toWire).toList();
        if (visible.isEmpty()) {
            // absent, another organization's, or none of its members visible: the three are not distinguished (Q-F6 (a))
            throw notFound();
        }
        return new FleetObservation(view.stateVersion(), new Availability(AvailabilityState.LIVE, null, null, null),
                view.fleetId(), view.solutionId(), view.state(), view.strategy(), view.batchSize(), view.onFailure(),
                view.currentBatch(), view.requestedAt().toString(),
                view.terminalAt() == null ? null : view.terminalAt().toString(), visible);
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /**
     * What a single {@code submitApply} at this instance would have answered, or {@code null} when it would have passed.
     * {@code require} is the ONLY authority: not-found when no held grant is visible from the instance, forbidden when one
     * is but none authorizes the verb. {@code config.apply} first; a member refused on it is not asked the second verb,
     * and one refused on {@code instance.restart} is not asked the third ({@code instance.restart.overrideSafety}, only
     * for the two ungated strategies).
     */
    private FleetApplyCommand.RefusalCode verdict(Session session, UUID instanceId, boolean restarts,
            boolean overridesSafety) {
        ResourceRef instance = ResourceRef.of(ResourceKind.INSTANCE, instanceId, new TenantId(session.organizationId()));
        try {
            authorization.require(session.principal(), Permission.CONFIG_APPLY, instance);
            if (restarts) {
                authorization.require(session.principal(), Permission.INSTANCE_RESTART, instance);
            }
            if (overridesSafety) {
                authorization.require(session.principal(), Permission.INSTANCE_RESTART_OVERRIDE_SAFETY, instance);
            }
            return null;
        } catch (ResourceNotFoundException e) {
            return FleetApplyCommand.RefusalCode.NOT_FOUND;
        } catch (AccessDeniedException e) {
            return FleetApplyCommand.RefusalCode.FORBIDDEN;
        }
    }

    private static ResourceNotFoundException notFound() {
        // No fleet resource kind exists in the verb catalog; the response is the fixed NOT_FOUND problem whatever the kind.
        return new ResourceNotFoundException(ResourceKind.INSTANCE);
    }

    private static FleetMember toWire(FleetMemberView m) {
        return new FleetMember(m.ordinal(), m.batch(), m.instanceId(), m.placementId(), m.generation(), m.disposition(),
                m.applyId(), m.settled(), m.refusalCode(), m.admissionRefusal(), m.requestState(),
                m.requestStateVersion());
    }
}
