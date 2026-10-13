package com.solace.sm.dp.apply.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;
import com.solace.sm.dp.config.LadderRecorder;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Plan 015 W4 lane D: the ladder settle is the one funnel that tells the declarations sink, moved or not, and never fails the caller. */
class DeclarationsChangeSinkTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final InstanceId instance = new InstanceId(UUID.randomUUID());

    private ApplyEventEmitter emitter() {
        return new ApplyEventEmitter(event -> { }, Clock.systemUTC(), UUID::randomUUID);
    }

    @Test
    void everySettleTellsTheSinkEvenWhenTheRungDidNotMove() {
        ApplyEventEmitter emitter = emitter();
        List<String> told = new ArrayList<>();
        emitter.declarationsSink((t, i) -> told.add(t.value() + "/" + i.value()));

        boolean moved = emitter.settleLadder(LadderRecorder.none(), tenant, instance);
        emitter.settleLadder(LadderRecorder.none(), tenant, instance);

        assertFalse(moved, "the none recorder never sees a movement");
        assertEquals(List.of(tenant.value() + "/" + instance.value(), tenant.value() + "/" + instance.value()), told,
                "a placement add or retire changes the declared set without moving the rung");
    }

    @Test
    void aFailingSinkNeverReachesTheSettlingCaller() {
        ApplyEventEmitter emitter = emitter();
        emitter.declarationsSink((t, i) -> {
            throw new IllegalStateException("the sink broke");
        });

        assertFalse(emitter.settleLadder(LadderRecorder.none(), tenant, instance));
    }

    @Test
    void withNoSinkWiredNothingHappens() {
        assertFalse(emitter().settleLadder(LadderRecorder.none(), tenant, instance));
    }
}
