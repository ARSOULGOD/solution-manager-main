package com.solace.sm.gateway.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.catalog.LoadedPackage;
import com.solace.sm.cp.api.catalog.SolutionCatalog;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.dp.api.inventory.HealthProbeSpec;
import com.solace.sm.dp.api.inventory.PackageDeclarationSource;
import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What the data plane cannot read for an instance an agent registers (plan 015 T4.1): the package's declared probe and metric
 * definitions, resolved here because the gateway is the one module permitted the control plane's catalog ({@code I7}). It is the
 * resolution {@link DeclareOrchestrator} makes for an operator's declare - the same probe rule ({@code probeOf}), the same
 * definitions, signals and generation-0 list ({@link MetricsDeclarationBuilder}) - with one difference: the host-side ports. An
 * operator names them in the request body; for an agent the data plane maps the package's container ports to the ports Docker
 * published, so {@code metricsPorts} here maps each called port to itself and {@link Probe#containerPort()} carries the probe's.
 *
 * <p>Keyed by the agent's ENROLMENT (the organization, the catalog entry and the version it was enrolled with), never by anything in
 * a report. A version that is not loaded, or that cannot be declared (a grammar the data plane's CHECKs would refuse, an empty metric
 * unit), answers empty and the item is refused {@code agent.discovery.incomplete}: the same package an operator's declare would
 * answer {@code 422 declaredPackageVersion}.
 */
@Component
public class AgentPackageDeclarations implements PackageDeclarationSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SolutionCatalog catalog;
    private final DesignerCodec codec;
    private final MetricApplicability applicability;

    @Autowired
    public AgentPackageDeclarations(SolutionCatalog catalog, DesignerCodec codec, MetricApplicability applicability) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.applicability = Objects.requireNonNull(applicability, "applicability");
    }

    @Override
    public Optional<PackageDeclaration> resolve(UUID organizationId, UUID catalogSolutionId, String packageVersion) {
        Optional<LoadedPackage> declared = catalog.find(organizationId, catalogSolutionId, packageVersion);
        Optional<String> document = catalog.documentOf(organizationId, catalogSolutionId, packageVersion);
        if (declared.isEmpty() || document.isEmpty()) {
            return Optional.empty();
        }
        try {
            HealthProbeSpec probe = DeclareOrchestrator.probeOf(document.get(), 1);    // the host port is the data plane's to choose
            PackageModel.SolutionPackage pkg = codec.readPackage(document.get());
            Object onTheWire = InstanceController.packageVersionOnTheWire(packageVersion);
            List<String> active = applicability.activeMetricNames(pkg, onTheWire,
                    MetricsDeclarationBuilder.declareTimeSelection(pkg, onTheWire), Map.of());
            MetricsDeclaration metrics = MetricsDeclarationBuilder.build(declared.get().digest(), pkg, onTheWire, Map.of(), active);
            Optional<Probe> declaredProbe = Optional.ofNullable(probe).map(p ->
                    new Probe(p.kind(), p.path(), probeContainerPort(document.get()), p.strength()));
            return Optional.of(new PackageDeclaration(declaredProbe, metrics));
        } catch (DeclareValidationException | IllegalArgumentException | IllegalStateException cannotBeDeclared) {
            return Optional.empty();              // the message could quote the document (I6): the class is all that matters
        }
    }

    /** The container port the same component's probe names (the first one {@code probeOf} would pick), or {@code null}. */
    private static Integer probeContainerPort(String document) {
        JsonNode components;
        try {
            components = MAPPER.readTree(document).path("components");
        } catch (JsonProcessingException e) {
            return null;
        }
        for (JsonNode component : components) {
            JsonNode probe = component.path("health").path("probe");
            String kind = probe.path("kind").asText("");
            if (kind.equals("actuator-health") || kind.equals("http-status")) {
                JsonNode port = probe.path("port");
                return port.isInt() && port.intValue() >= 1 && port.intValue() <= 65_535 ? port.intValue() : null;
            }
        }
        return null;
    }
}
