package com.solace.sm.dp.api.inventory;

import com.solace.sm.dp.api.metrics.declare.MetricsDeclaration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What the data plane cannot read for itself: the loaded package's declared health probe and metric definitions (plan 015 T4.1,
 * {@code OR-27}). The data plane reads no package ({@code I7}; {@code MetricsDeclaration}), yet an instance an agent registers
 * must be declared exactly as an operator's is, so the gateway, the one module permitted both {@code -api}s, answers this for
 * the {@code (organization, catalog solution, version)} the agent was ENROLLED into. The caller is the data plane's discovery
 * intake and the key is its own enrolment record - never a value the agent sent ({@code I5}).
 *
 * <p>The answer is what {@code DeclareOrchestrator} resolves for an operator's declare, with one difference: the host-side port
 * of each source and of the probe is not in a request body; the data plane maps the package's container ports to the ports
 * Docker published (the agent's facts). {@code metrics.metricsPorts()} therefore carries each called port mapped to itself, to be
 * re-mapped by the caller.
 */
public interface PackageDeclarationSource {

    /** @return empty when the version is not loaded for the organization (or cannot be declared): the item is then refused */
    Optional<PackageDeclaration> resolve(UUID organizationId, UUID catalogSolutionId, String packageVersion);

    /**
     * @param probe   empty when the package declares no HTTP probe (a {@code broker-heartbeat} only): the placement is then
     *                recorded without one and reads {@code NOT_APPLICABLE} ({@code OR-101})
     * @param metrics the version's definitions, restart signals and generation-0 list; never {@link MetricsDeclaration#none()}
     */
    record PackageDeclaration(Optional<Probe> probe, MetricsDeclaration metrics) {
        public PackageDeclaration {
            Objects.requireNonNull(probe, "probe");
            Objects.requireNonNull(metrics, "metrics");
        }
    }

    /**
     * The package's {@code health.probe}. {@code containerPort} is the port INSIDE the container the probe addresses, or
     * {@code null} when the package names none (the host port then comes from the label {@code com.solace.sm.health-port}).
     */
    record Probe(String kind, String path, Integer containerPort, ProbeStrength strength) {
        public Probe {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(strength, "strength");
        }
    }
}
