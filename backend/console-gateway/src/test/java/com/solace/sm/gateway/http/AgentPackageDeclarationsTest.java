package com.solace.sm.gateway.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.solace.sm.dp.api.inventory.PackageDeclarationSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The gateway's answer to the data plane's discovery intake (plan 015 T4.1): what the enrolled package declares, resolved the way an
 * operator's declare resolves it, with the host ports left to the data plane. (A shared contract kit for
 * {@link PackageDeclarationSource} would need the dp-api test-jar on this module's test classpath, a pom change HF13 reserves; these are
 * the contract's cases for this implementation.)
 */
class AgentPackageDeclarationsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();

    private static ObjectNode cache() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("contracts/validation/fixtures/cache-2.4.package.json");
            if (Files.isRegularFile(candidate)) {
                return (ObjectNode) JSON.readTree(Files.readString(candidate));
            }
        }
        throw new IllegalStateException("fixture not found");
    }

    private static AgentPackageDeclarations source(String document) {
        InstanceControllerTest.FakeCatalog catalog = new InstanceControllerTest.FakeCatalog();
        if (document != null) {
            catalog.serve(ENTRY, "1", document);
        }
        return new AgentPackageDeclarations(catalog, new MetricsFakes.JacksonPackageCodec(), new MetricsFakes.RecordingApplicability());
    }

    @Test
    void aLoadedVersionAnswersItsProbeAndItsMetricsWithEachPortMappedToItself() throws Exception {
        PackageDeclarationSource.PackageDeclaration declared = source(cache().toString()).resolve(ORG, ENTRY, "1").orElseThrow();

        PackageDeclarationSource.Probe probe = declared.probe().orElseThrow();
        assertEquals("actuator-health", probe.kind());
        assertEquals("/actuator/health", probe.path());
        assertEquals(8081, probe.containerPort(), "the container port the probe names: the DP maps it to the published host port");
        assertFalse(declared.metrics().isNone());
        assertTrue(declared.metrics().definitions().size() > 0);
        declared.metrics().metricsPorts().forEach((source, host) -> assertEquals(source, host));
        assertEquals("sha256:" + "a".repeat(64), declared.metrics().packageDigest());
    }

    @Test
    void aVersionThatIsNotLoadedAnswersEmpty() throws Exception {
        assertTrue(source(null).resolve(ORG, ENTRY, "1").isEmpty());
        assertTrue(source(cache().toString()).resolve(ORG, UUID.randomUUID(), "1").isEmpty(), "another catalog entry");
        assertTrue(source(cache().toString()).resolve(ORG, ENTRY, "2").isEmpty(), "another version");
    }

    @Test
    void aProbeNamingNoPortLeavesTheContainerPortNullSoTheLabelIsUsed() throws Exception {
        ObjectNode doc = cache();
        ((ObjectNode) doc.get("components").get(0).get("health").get("probe")).remove("port");

        PackageDeclarationSource.Probe probe = source(doc.toString()).resolve(ORG, ENTRY, "1").orElseThrow().probe().orElseThrow();

        assertNull(probe.containerPort());
    }

    @Test
    void aPackageDeclaringNoHttpProbeAnswersNoProbe() throws Exception {
        ObjectNode doc = cache();
        ((ObjectNode) doc.get("components").get(0).get("health").get("probe")).put("kind", "broker-heartbeat");

        assertEquals(Optional.empty(), source(doc.toString()).resolve(ORG, ENTRY, "1").orElseThrow().probe());
    }

    @Test
    void aDocumentThatCannotBeDeclaredAnswersEmptyAndAnEmptyMetricUnitIsRefusedAtTheSource() throws Exception {
        ObjectNode doc = cache();
        JsonNode metric = doc.get("components").get(0).get("metrics").get(0);
        ((ObjectNode) metric).put("unit", "");

        assertTrue(source(doc.toString()).resolve(ORG, ENTRY, "1").isEmpty(), "the builder's grammar refuses it, as for an operator");
        assertTrue(source("{not json").resolve(ORG, ENTRY, "1").isEmpty());
    }
}
