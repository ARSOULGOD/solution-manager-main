package com.solace.sm.dp.api.logs;

import java.util.UUID;

/**
 * The data plane's bulk export of one agent placement's collected log (plan 016 task 10). Like {@link LogService} it is
 * tenant-scoped: the placement is resolved from {@code (organization, instance, placement)} together on the data plane's own
 * view (I5), so another organization's, another instance's or a retired placement is simply unresolvable.
 *
 * <p>Nothing here throws for an unavailable placement: a refusal is a stream whose only content is one fixed marker line
 * (see {@link LogExportStream}), because the HTTP side may already be committed by then and an export is a file, not a page.
 * Only agent placements are served; the caller decides SSH ones are refused before it gets here.
 */
public interface LogExportService {

    /** Opens the export. Never null; the caller must {@link LogExportStream#close()} it. */
    LogExportStream open(UUID organizationId, UUID instanceId, UUID placementId);
}
