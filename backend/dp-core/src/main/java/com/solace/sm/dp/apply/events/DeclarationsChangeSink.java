package com.solace.sm.dp.apply.events;

import com.solace.sm.dp.apply.model.InstanceId;
import com.solace.sm.dp.apply.model.TenantId;

/**
 * Told that an instance's declared collection set MAY have changed (plan 015 W4 lane D). Callers, all in the transaction that made
 * the change: {@link ApplyEventEmitter#settleLadder} (a landing, a placement add or retire, the agent report intake, the ack
 * settler), and, through {@link ApplyEventEmitter#declarationsChanged}, instance activation and the pending sweep, the dp-remote
 * start observation, and the three retirement transitions. It is a prompt, not data: an implementation re-reads what it needs, and
 * one that reads must defer to the commit.
 */
@FunctionalInterface
public interface DeclarationsChangeSink {

    /** Nobody is listening (an install without the agent gateway). */
    DeclarationsChangeSink NONE = (tenant, instance) -> { };

    void changed(TenantId tenant, InstanceId instance);
}
