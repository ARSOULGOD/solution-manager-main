package com.solace.sm.dp.api.readback;

import java.util.UUID;

/**
 * The data plane's read-back of a placement's config file, for {@code accept-runtime} (plan 010 T10.4, D-ACCEPT).
 * Tenant-scoped by {@code organizationId}, which is {@code Session.organizationId()}, never a request field ({@code I5}).
 *
 * <p>⛔ <b>{@code I6}: nothing this interface returns can hold a secret value</b> ({@link ReadBackCandidate}'s contract).
 * The value of a secret node is compared in memory with the stored secret inside the data plane and reported only as
 * {@code same} or {@code differs}; it is never stored, returned, logged or published.
 *
 * <p>One bounded read of the placement's OWN declared file, in the calling thread, holding no database transaction. The
 * request names no host, path or command.
 */
public interface RuntimeReadBackService {

    /**
     * Reads the file back, parses it, maps its declared paths and reports what could not be adopted.
     *
     * @throws ReadBackRefusedException the placement is not a live placement of this instance
     *         ({@code PLACEMENT_NOT_FOUND}), the host could not be read ({@code HOST_UNREADABLE}), or the file is absent,
     *         unreadable as configuration, or uses a construct SM refuses to read (D-ACCEPT §3.2)
     */
    ReadBackCandidate readBack(UUID organizationId, UUID instanceId, UUID placementId, FieldPathTable table);

    /**
     * Whether the file that was read back differs, byte for byte, from SM's own rendering of {@code generation}: after an
     * accept the host file still differs until that generation is applied (D-ACCEPT §3.8). Re-renders the generation in the
     * data plane (secrets resolved in memory) and compares keyed hashes; no byte of either side is returned.
     *
     * @return true when they differ, or when they cannot be compared (a hash key that can no longer be loaded)
     */
    boolean fileDiffersFromRendering(UUID organizationId, UUID instanceId, UUID placementId, long generation,
            ReadBackCandidate candidate);
}
