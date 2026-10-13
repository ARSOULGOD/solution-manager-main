package com.solace.sm.dp.metrics.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.solace.sm.dp.api.metrics.ingest.CollectorReason;
import com.solace.sm.dp.api.metrics.ingest.IngestBatch;
import com.solace.sm.dp.api.metrics.ingest.IngestResult;
import com.solace.sm.dp.api.metrics.ingest.IngestSample;
import com.solace.sm.dp.api.metrics.ingest.RestartDetection;
import com.solace.sm.dp.api.metrics.ingest.SourceDiscriminator;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The I1 check as code (plan 011 P11-3.1): what a producer CAN say is pinned by reflection on the port's types, so
 * adding a component that carries identity, type or unit fails here and is a reviewed change, not a convenience.
 */
class IngestApiShapeTest {

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void anIngestSampleCarriesExactlyNameTsValueReceivedAtAndRestartToken() {
        assertEquals(List.of("metricName", "ts", "value", "receivedAt", "restartToken"), components(IngestSample.class),
                "no type, no unit, no instance and no label from the producer (I1)");
    }

    @Test
    void aBatchNamesATenantAndAPlacementAndNeverAnInstance() {
        List<String> names = components(IngestBatch.class);
        assertEquals(List.of("organizationId", "bindingId", "placementId", "sourceKey", "discriminator", "samples",
                "gapMarkers"), names);
        assertFalse(names.contains("instanceId"), "the instance is read from the placement row");
        assertFalse(names.contains("detection"), "the restart detection is derived by the data plane");
    }

    @Test
    void theResultIsExactlyOneOfFourKinds() {
        Set<String> kinds = Arrays.stream(IngestResult.class.getPermittedSubclasses()).map(Class::getSimpleName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("Committed", "RetryAfter", "Rejected", "Pending"), kinds);
    }

    @Test
    void theReasonListIsTheClosedElevenIncludingTheTwoRuledAtTheSittingAndClockSkew() {
        assertEquals(List.of("scrape_failed", "parse_failed", "cap_rejected", "undeclared_dropped",
                "decrease_without_restart", "runway_degraded", "deadline_missed", "buffer_dropped", "out_of_order",
                "ambiguous_placement", "clock_skew"), Arrays.stream(CollectorReason.values()).map(CollectorReason::wire).toList());
    }

    @Test
    void theDiscriminatorWireNamesAreTheStoredOnes() {
        assertEquals(List.of("dp-poll", "broker-push", "agent"),
                Arrays.stream(SourceDiscriminator.values()).map(SourceDiscriminator::wire).toList());
        assertTrue(RestartDetection.DECLARED.hasSignal() && RestartDetection.DOCKER.hasSignal()
                && !RestartDetection.INFERRED.hasSignal());
    }

    @Test
    void theEmptyLabelFingerprintIsTheSha256OfTheEmptyCanonicalMap() {
        // sha256("{}") - computed independently of the service
        assertEquals("sha256:44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                IngestService.EMPTY_LABELS_FINGERPRINT);
    }
}
