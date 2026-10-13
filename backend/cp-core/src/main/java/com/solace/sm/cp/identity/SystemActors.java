package com.solace.sm.cp.identity;

import java.util.UUID;

/**
 * The system actor of {@code V022} (plan 015 T4.6, {@code OR-140}): the identity a scheduled job's control-plane writes are
 * audited to, in place of a principal. One seeded row of {@code sm_control.system_actor}; the database refuses it on any
 * operation or table beyond the two named job writes ({@code AGENT_REVOKED} on {@code agent_registration}, {@code TARGET_RETIRED}
 * on an agent {@code target}).
 */
public final class SystemActors {

    /** {@code sm_control.system_actor.system_actor_id} of {@code agent-jobs}, seeded by {@code V022}. */
    public static final UUID AGENT_JOBS = UUID.fromString("00000000-0000-4000-8000-000000000a01");

    /**
     * The client address a system actor's write carries (the audit function admits a system actor only from exactly this
     * literal, as a no-actor operator write is admitted only from {@code operator-cli}). There is no client: it is a job.
     */
    public static final String CLIENT_ADDRESS = "system-job";

    private SystemActors() {
    }
}
