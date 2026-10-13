package com.solace.sm.gateway.http;

import com.solace.sm.dp.api.apply.ApplyRefusedException;
import com.solace.sm.dp.api.apply.ApplySchedulePort;
import com.solace.sm.dp.api.apply.DueSchedule;
import com.solace.sm.dp.api.apply.ScheduleAcceptedView;
import com.solace.sm.dp.api.apply.ScheduleCommand;
import com.solace.sm.dp.api.apply.ScheduleFireResult;
import com.solace.sm.dp.api.apply.ScheduleReason;
import com.solace.sm.dp.api.apply.ScheduleView;
import com.solace.sm.gateway.http.InstanceControllerTest.Calls;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The data plane's scheduled-apply port, faked (plan 012 T7): records every call and every organization it saw, hands out
 * what a test sets, and can be told to refuse. {@code tenantsWithDueSchedules}/{@code due}/{@code admit}/{@code refuse}/
 * {@code miss} are the firer's, and {@code ScheduleFirerTest} drives them; the controller tests use the first three.
 */
class FakeSchedulePort implements ApplySchedulePort {

    final Calls calls;
    final List<UUID> organizationsSeen = new ArrayList<>();
    final List<ScheduleCommand> scheduled = new ArrayList<>();
    final List<UUID> cancelled = new ArrayList<>();
    final List<UUID> cancelActors = new ArrayList<>();
    final List<Integer> listLimits = new ArrayList<>();
    RuntimeException failure;
    UUID nextScheduleId = UUID.randomUUID();
    long nextStateVersion = 91;
    List<ScheduleView> listing = List.of();
    ScheduleView cancelResult;

    // the firer's side
    List<UUID> dueTenants = List.of();
    java.util.Map<UUID, List<DueSchedule>> due = new java.util.LinkedHashMap<>();
    final List<String> fired = new ArrayList<>();
    java.util.function.Function<UUID, ScheduleFireResult> admitResult = id -> ScheduleFireResult.admitted(UUID.randomUUID());
    RuntimeException enumerationFailure;

    FakeSchedulePort(Calls calls) {
        this.calls = calls;
    }

    @Override
    public ScheduleAcceptedView schedule(UUID organizationId, UUID instanceId, ScheduleCommand command) {
        calls.add("dp.schedule");
        organizationsSeen.add(organizationId);
        if (failure != null) {
            throw failure;
        }
        scheduled.add(command);
        return new ScheduleAcceptedView(nextScheduleId, nextStateVersion);
    }

    @Override
    public List<ScheduleView> list(UUID organizationId, UUID instanceId, int limit) {
        calls.add("dp.listSchedules");
        organizationsSeen.add(organizationId);
        listLimits.add(limit);
        if (failure != null) {
            throw failure;
        }
        return listing;
    }

    @Override
    public ScheduleView cancel(UUID organizationId, UUID instanceId, UUID scheduleId, UUID actor) {
        calls.add("dp.cancelSchedule");
        organizationsSeen.add(organizationId);
        if (failure != null) {
            throw failure;
        }
        cancelled.add(scheduleId);
        cancelActors.add(actor);
        if (cancelResult == null) {
            throw new ApplyRefusedException(ApplyRefusedException.Reason.NOT_FOUND);
        }
        return cancelResult;
    }

    @Override
    public List<UUID> tenantsWithDueSchedules(Instant now) {
        calls.add("dp.tenantsWithDueSchedules");
        if (enumerationFailure != null) {
            throw enumerationFailure;
        }
        return dueTenants;
    }

    @Override
    public List<DueSchedule> due(UUID organizationId, Instant now) {
        calls.add("dp.due");
        return due.getOrDefault(organizationId, List.of());
    }

    @Override
    public ScheduleFireResult admit(UUID organizationId, UUID scheduleId) {
        calls.add("dp.admit");
        fired.add("admit:" + scheduleId);
        return admitResult.apply(scheduleId);
    }

    @Override
    public ScheduleFireResult refuse(UUID organizationId, UUID scheduleId, ScheduleReason reason) {
        calls.add("dp.refuse");
        fired.add("refuse:" + scheduleId + ":" + reason.wire());
        return ScheduleFireResult.refused(reason);
    }

    @Override
    public ScheduleFireResult miss(UUID organizationId, UUID scheduleId) {
        calls.add("dp.miss");
        fired.add("miss:" + scheduleId);
        return ScheduleFireResult.missed();
    }
}
