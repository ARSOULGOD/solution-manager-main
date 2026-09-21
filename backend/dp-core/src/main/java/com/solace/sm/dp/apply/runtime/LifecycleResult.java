package com.solace.sm.dp.apply.runtime;

import com.solace.sm.dp.apply.model.RuntimeRef;

/**
 * @param issued whether the runtime was actually asked to act. {@code false} is the idempotent case
 *               §2.2 rule 2 requires an adapter to report as success — a replica already at the
 *               desired state, measured on Docker as HTTP {@code 304} from {@code /stop}.
 */
public record LifecycleResult(boolean issued, RuntimeRef runtimeRef) {
}
