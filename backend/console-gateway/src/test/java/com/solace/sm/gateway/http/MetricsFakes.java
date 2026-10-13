package com.solace.sm.gateway.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan 011 P11-4.1: the two collaborators every gateway test that builds a {@code DeclareOrchestrator} now needs.
 * The gateway may not reach {@code cp-core}, so the control plane's evaluator is faked here and its own behavior is
 * proved where it lives ({@code MetricApplicabilityTest}); what the gateway owns, and these record, is WHAT it asks the
 * evaluator over.
 */
final class MetricsFakes {

    private MetricsFakes() {
    }

    /** One ask of the evaluator, as the gateway made it. */
    record Ask(PackageModel.SolutionPackage pkg, Object packageVersion, StateModel.ComponentSelection selection,
            Map<String, Map<String, Object>> draft) {
    }

    /** Answers {@link #next} to every ask, and remembers each. */
    static final class RecordingApplicability implements MetricApplicability {
        final List<Ask> asks = new ArrayList<>();
        List<String> next = List.of();

        @Override
        public List<String> activeMetricNames(PackageModel.SolutionPackage pkg, Object packageVersion,
                StateModel.ComponentSelection selection, Map<String, Map<String, Object>> draft) {
            asks.add(new Ask(pkg, packageVersion, selection, draft));
            return next;
        }

        Ask last() {
            return asks.get(asks.size() - 1);
        }
    }

    /** Binds a stored document to a package with Jackson alone (a test of the gateway does not need the one default rename). */
    static final class JacksonPackageCodec implements DesignerCodec {
        private static final ObjectMapper MAPPER = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        @Override
        public PackageModel.SolutionPackage readPackage(String packageDocumentJson) {
            try {
                return MAPPER.readValue(packageDocumentJson, PackageModel.SolutionPackage.class);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("not a package");
            }
        }

        @Override
        public Map<String, Object> viewModelTree(ViewModel viewModel) {
            throw new AssertionError("a declare never serializes a view model");
        }
    }
}
