package com.solace.sm.dp.api.logs;

import java.util.UUID;

/**
 * The data plane's bulk export of one agent placement's collected log (plan 016 task 10). Like {@link LogService} it is
 * tenant-scoped: the placement is resolved from {@code (organization, instance, placement)} together on the data plane's own
 * view (I5), so another organization's, another instance's or a retired placement is simply unresolvable.
 *
 * <p>Nothing here throws for an unavailable placement: a refusal is the stream's {@link LogExportStream#refusal()} (a kind and a
 * fixed reason), known before the first byte so the caller can answer with a real status; only what happens after the first byte is
 * an in-band marker line (see {@link LogExportStream}). Only agent placements are served; the caller decides SSH ones are refused before it gets here.
 */
public interface LogExportService {

    /** Opens the export from the oldest retained line. Never null; the caller must {@link LogExportStream#close()} it. */
    default LogExportStream open(UUID organizationId, UUID instanceId, UUID placementId) {
        return open(organizationId, instanceId, placementId, 0L);
    }

    /**
     * Opens the export from {@code fromSeq}, the resume point an earlier abnormal end named in its marker ({@code resume from seq
     * N}); 0, or a point older than the ring keeps, starts at the oldest retained line. A negative value throws
     * {@link IllegalArgumentException}. Never null; the caller must {@link LogExportStream#close()} it.
     */
    LogExportStream open(UUID organizationId, UUID instanceId, UUID placementId, long fromSeq);
}
