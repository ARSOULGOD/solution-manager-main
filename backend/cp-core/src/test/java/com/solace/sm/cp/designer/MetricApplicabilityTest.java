package com.solace.sm.cp.designer;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.designer.MetricApplicability;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plan 011 P11-4.1, M-Q15 ({@code OR-74}): the active metric-name list is {@code when:} evaluated by the control plane's own
 * grammar over the DEFAULTS-APPLIED, NORMALIZED DRAFT, the evaluation {@code project()} uses, extracted.
 */
class MetricApplicabilityTest {

    private static final MetricApplicability APPLICABILITY = new MetricApplicabilityImpl();

    private static final List<String> CACHE_FIVE = List.of("cache.eviction_count", "cache.hit_count", "cache.hit_rate",
            "cache.miss_count", "cache.size");

    private static StateModel.ComponentSelection one(String key, String type) {
        return new StateModel.ComponentSelection(List.of(new StateModel.ComponentInstance(key, type, null)));
    }

    // ---- the Cache fixture: the real package, not a stand-in --------------------------------------------------------

    private static PackageModel.SolutionPackage cache() {
        try {
            return PackageJsonReader.readFile(contractsRoot().resolve("validation/fixtures/cache-2.4.package.json"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void activeNamesFollowWhenOverTheDraftNotTheRunningValues() {
        PackageModel.SolutionPackage cache = cache();
        StateModel.ComponentSelection selection = one("i1", "cache-engine");

        // provider defaults to caffeine: the five caffeine-only metrics are active for an empty draft
        assertThat(APPLICABILITY.activeMetricNames(cache, 1, selection, Map.of())).isEqualTo(CACHE_FIVE);
        assertThat(APPLICABILITY.activeMetricNames(cache, 1, selection, Map.of("i1", Map.of("provider", "caffeine"))))
                .isEqualTo(CACHE_FIVE);
        // the DRAFT says redis: none apply, whatever is running now
        assertThat(APPLICABILITY.activeMetricNames(cache, 1, selection, Map.of("i1", Map.of("provider", "redis"))))
                .isEmpty();
    }

    @Test
    void theDraftIsReadAndTheRunningValuesAreNotEvenAnInput() {
        // project() compares running with draft, so a caffeine -> redis edit reads "pending-config-change" for each of
        // the five metrics, the same word it uses for redis -> caffeine. That cannot say whether a metric is active for
        // the DRAFT, which is why the list is not derived from it.
        PackageModel.SolutionPackage cache = cache();
        StateModel.ComponentSelection selection = one("i1", "cache-engine");
        Map<String, Map<String, Object>> draft = Map.of("i1", Map.of("provider", "redis"));
        StateModel.ObservedState runningCaffeine = new StateModel.ObservedState(
                new StateModel.VersionObservation("1", "1", "VERSION_MATCH"), StateModel.ConfigState.EMPTY, List.of(),
                Map.of("i1", Map.of("provider", "caffeine")));

        var projected = new DesignerProjectorImpl().project(cache, selection, draft, runningCaffeine, Map.of(), 1);
        assertThat(projected.metrics()).extracting(m -> m.collectionState()).containsOnly("pending-config-change");
        assertThat(APPLICABILITY.activeMetricNames(cache, 1, selection, draft)).isEmpty();
    }

    // ---- the draft resolver: defaults, then normalization, exactly as project() does ------------------------------

    private static PackageModel.PackageField field(String key, String type, Object defaultValue) {
        return new PackageModel.PackageField(key, type, null, null, null, null, null, defaultValue, null, null, null,
                null, null, null, null, null, null);
    }

    private static PackageModel.PackageField versioned(String key, String type, List<Object> version) {
        return new PackageModel.PackageField(key, type, version, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static PackageModel.PackageMetric metric(String name, Map<String, Object> when) {
        return new PackageModel.PackageMetric(name, "gauge", "count", "package-baseline", when,
                Map.of("kind", "http_json", "path", "/m", "port", 8081, "onUnavailable", "degrade"), null);
    }

    private static PackageModel.PackageComponent component(String type, List<Object> version,
            List<PackageModel.PackageField> fields, List<PackageModel.PackageMetric> metrics) {
        return new PackageModel.PackageComponent(type, "exactly-one", version, "none", type, null, null, fields, null,
                metrics, null, null, null);
    }

    private static PackageModel.SolutionPackage pkg(List<Object> versions, PackageModel.PackageComponent... components) {
        return new PackageModel.SolutionPackage("fixture", versions, ">=1.0", null, List.of(components));
    }

    private static final PackageModel.SolutionPackage GATED = pkg(List.of(1),
            component("widget", null,
                    List.of(field("enabled", "bool", false), field("port", "int", 8081), field("mode", "string", "a")),
                    List.of(metric("w.on", Map.of("enabled", true)), metric("w.port", Map.of("port", 8081)),
                            metric("w.both", Map.of("enabled", true, "mode", "a")), metric("w.always", null))));

    @Test
    void theExtractedDraftResolverAppliesDefaultsAndNormalizesLikeProject() {
        StateModel.ComponentSelection selection = one("w1", "widget");

        // defaults only: enabled=false, port=8081, mode=a
        assertThat(APPLICABILITY.activeMetricNames(GATED, 1, selection, Map.of())).containsExactly("w.always", "w.port");

        // a form control emits the STRING "true" for a bool, and "8081" for an int: normalized before predicates run
        Map<String, Map<String, Object>> strings = Map.of("w1", Map.of("enabled", "true", "port", "9000"));
        assertThat(APPLICABILITY.activeMetricNames(GATED, 1, selection, strings)).containsExactly("w.always", "w.both",
                "w.on");

        // an empty string is "no value": the DEFAULT applies, so enabled is false again and port is 8081 again
        Map<String, Map<String, Object>> empty = Map.of("w1", Map.of("enabled", "", "port", ""));
        assertThat(APPLICABILITY.activeMetricNames(GATED, 1, selection, empty)).containsExactly("w.always", "w.port");

        // "yes" is NOT a bool (UI-D2's grammar is strict equality): it is a bind error, and a bind error leaves the
        // value unset (no default is applied behind it), so nothing gated on it holds
        Map<String, Map<String, Object>> yes = Map.of("w1", Map.of("enabled", "yes"));
        assertThat(APPLICABILITY.activeMetricNames(GATED, 1, selection, yes)).containsExactly("w.always", "w.port");
    }

    @Test
    void anIntegerWrittenAsANumberOrAStringMatchesTheSameLiteral() {
        // JS === on 8081 and 8081: a Long out of one JSON binder equals an Integer out of another
        StateModel.ComponentSelection selection = one("w1", "widget");
        assertThat(APPLICABILITY.activeMetricNames(GATED, 1, selection, Map.of("w1", Map.of("port", 8081L))))
                .contains("w.port");
        assertThat(APPLICABILITY.activeMetricNames(GATED, 1, selection, Map.of("w1", Map.of("port", 9000L))))
                .doesNotContain("w.port");
    }

    // ---- selection, versions, ordering --------------------------------------------------------------------------

    @Test
    void eachInstanceIsEvaluatedOverItsOwnValuesAndTheNamesAreUnioned() {
        StateModel.ComponentSelection two = new StateModel.ComponentSelection(List.of(
                new StateModel.ComponentInstance("a", "widget", null),
                new StateModel.ComponentInstance("b", "widget", null)));
        Map<String, Map<String, Object>> draft = Map.of("a", Map.of("enabled", true), "b", Map.of("enabled", false));
        // a enables w.on and w.both, b enables neither, and w.always / w.port come from both: each name once
        assertThat(APPLICABILITY.activeMetricNames(GATED, 1, two, draft)).containsExactly("w.always", "w.both", "w.on",
                "w.port");
    }

    @Test
    void anInstanceOfAnUndeclaredTypeContributesNothingAndAMissingSelectionIsEmpty() {
        assertThat(APPLICABILITY.activeMetricNames(GATED, 1, one("x", "no-such-type"), Map.of())).isEmpty();
        assertThat(APPLICABILITY.activeMetricNames(GATED, 1, new StateModel.ComponentSelection(List.of()), Map.of()))
                .isEmpty();
    }

    @Test
    void aComponentAndAFieldOutsideTheDeclaredVersionAreOutsideTheEvaluation() {
        PackageModel.SolutionPackage scoped = pkg(List.of(1, 2),
                component("widget", List.of(1, 2),
                        List.of(versioned("enabled", "bool", List.of(2))),
                        List.of(metric("w.on", Map.of("enabled", true)), metric("w.always", null))),
                component("only-v2", List.of(2), List.of(), List.of(metric("v2.only", null))));
        Map<String, Map<String, Object>> draft = Map.of("w1", Map.of("enabled", true));
        StateModel.ComponentSelection both = new StateModel.ComponentSelection(List.of(
                new StateModel.ComponentInstance("w1", "widget", null),
                new StateModel.ComponentInstance("o1", "only-v2", null)));

        // version 2: the field exists, the gate holds, and the v2-only component is in
        assertThat(APPLICABILITY.activeMetricNames(scoped, 2, both, draft)).containsExactly("v2.only", "w.always", "w.on");
        // version 1: "enabled" is not declared for it, so the draft value is an orphan and the gate cannot hold; the
        // v2-only component is not declared for version 1 at all
        assertThat(APPLICABILITY.activeMetricNames(scoped, 1, both, draft)).containsExactly("w.always");
        // null reads the package's LAST declared version, as project() does
        assertThat(APPLICABILITY.activeMetricNames(scoped, null, both, draft)).containsExactly("v2.only", "w.always",
                "w.on");
    }

    @Test
    void theResultIsSortedInOrdinalOrderWhateverTheDeclarationOrder() {
        PackageModel.SolutionPackage p = pkg(List.of(1), component("widget", null, List.of(),
                List.of(metric("z.last", null), metric("A.upper", null), metric("m.mid", null), metric("a.first", null))));
        // String.compareTo, never locale: an upper-case letter sorts before every lower-case one
        assertThat(APPLICABILITY.activeMetricNames(p, 1, one("w", "widget"), Map.of())).containsExactly("A.upper",
                "a.first", "m.mid", "z.last");
    }

    @Test
    void aComponentWithNoMetricsContributesNothing() {
        PackageModel.SolutionPackage p = pkg(List.of(1), component("widget", null, List.of(), null));
        assertThat(APPLICABILITY.activeMetricNames(p, 1, one("w", "widget"), Map.of())).isEmpty();
    }

    @Test
    void theInputsAreNotMutated() {
        Map<String, Object> inner = new LinkedHashMap<>(Map.of("enabled", "true"));
        Map<String, Map<String, Object>> draft = new LinkedHashMap<>(Map.of("w1", inner));
        APPLICABILITY.activeMetricNames(GATED, 1, one("w1", "widget"), draft);
        assertThat(inner).containsExactly(Map.entry("enabled", "true"));
        assertThat(draft).containsOnlyKeys("w1");
    }

    // ---- wiring: by its cp-api interface type, reachable from the one aggregate the launcher composes through ----------

    @Test
    void theConfigurationExposesTheCpCoreEvaluatorByItsPortTypeAndTheAggregateImportsIt() {
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext(
                MetricApplicabilityConfiguration.class)) {
            assertThat(ctx.getBeansOfType(MetricApplicability.class)).hasSize(1);
            assertThat(ctx.getBean(MetricApplicability.class)).isInstanceOf(MetricApplicabilityImpl.class);
        }
        var imports = com.solace.sm.cp.config.ControlPlaneConfiguration.class
                .getAnnotation(org.springframework.context.annotation.Import.class);
        assertThat(imports.value()).contains(MetricApplicabilityConfiguration.class);
    }

    private static Path contractsRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("contracts/validation/fixtures"))) {
                return dir.resolve("contracts");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate contracts/validation/fixtures from " + Path.of("").toAbsolutePath());
    }
}
