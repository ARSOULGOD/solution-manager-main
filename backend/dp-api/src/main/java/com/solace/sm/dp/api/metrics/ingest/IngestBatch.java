package com.solace.sm.dp.api.metrics.ingest;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One cycle's samples for one placement. The instance is deliberately not here: the data plane reads it from the
 * placement row, so a batch cannot name an instance it does not belong to.
 *
 * @param organizationId the tenant (the external name, {@code tenant_id} inside)
 * @param bindingId      M-Q7: on dp-remote, the placement's id
 * @param placementId    the placement the samples were read from
 * @param sourceKey      M-Q7: on dp-remote, the placement's id as text
 */
public record IngestBatch(UUID organizationId, UUID bindingId, UUID placementId, String sourceKey,
        SourceDiscriminator discriminator, List<IngestSample> samples, List<GapMarker> gapMarkers) {

    public IngestBatch {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(placementId, "placementId");
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(discriminator, "discriminator");
        samples = List.copyOf(samples);
        gapMarkers = gapMarkers == null ? List.of() : List.copyOf(gapMarkers);
    }

    /** The dp-remote shape (M-Q7): binding and source key are the placement. */
    public static IngestBatch polled(UUID organizationId, UUID placementId, List<IngestSample> samples) {
        return new IngestBatch(organizationId, placementId, placementId, placementId.toString(),
                SourceDiscriminator.DP_POLL, samples, List.of());
    }
}
