package com.solace.sm.dp.api.logs;

import java.time.Instant;
import java.util.UUID;

/**
 * The data plane's bounded read of one placement's container logs (plan 010 T9.1, {@code OR-43}). A read, legal on the
 * request thread (the single mutating apply path is untouched): it opens one SSH session to the placement's target, runs
 * one closed {@code docker logs}, and closes it.
 *
 * <p>Every call is tenant-scoped by {@code organizationId}, which is {@code Session.organizationId()}, never a request
 * field (I5), and the placement is resolved from {@code (organization, instance, placement)} together, so a placement
 * that is another instance's or another organization's is simply unresolvable. The caller has already authorized
 * {@code logs.read} at the instance and resolved which placement is meant.
 *
 * <p><b>A host that cannot be read is a declared answer, not an exception</b>: the page is empty and
 * {@link LogPageView#unavailableReason()} says why in fixed text that names no host, path or container (I6). An empty
 * page with no reason means the container wrote nothing in the window.
 *
 * <p>A {@code before} with no {@code after} pages backwards in bounded time windows (default 15 minutes, {@code OR-108}
 * (5)): the newest {@code limit} lines before it, found by stepping back window by window, never "unavailable" merely
 * because the whole log is large. A page shorter than {@code limit} means the bounded look-back ended, not that the log did.
 *
 * <p>The lines are the managed integration's own output; SM does not redact them.
 */
public interface LogService {

    /**
     * @param limit  1..{@value #MAX_LIMIT}; the page is the NEWEST {@code limit} lines inside the window, oldest first
     * @param after  exclusive lower bound, or {@code null}
     * @param before exclusive upper bound, or {@code null}
     * @throws IllegalArgumentException a limit outside its bounds, or a window with no interior
     */
    LogPageView read(UUID organizationId, UUID instanceId, UUID placementId, int limit, Instant after, Instant before);

    /** The contract's {@code limit} maximum. */
    int MAX_LIMIT = 1000;
}
