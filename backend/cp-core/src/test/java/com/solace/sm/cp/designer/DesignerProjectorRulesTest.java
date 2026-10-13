package com.solace.sm.cp.designer;

import static org.assertj.core.api.Assertions.assertThat;

import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.StateModel;
import com.solace.sm.cp.api.designer.ViewModel;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests for {@code DesignerProjectorImpl} rules that no fixture under {@code
 * contracts/view-models/fixtures/viewmodel/} exercises — {@code
 * DesignerProjectorFixtureTest} is locked to the 33 shared fixtures and always calls {@code
 * project()} with a {@code null} {@code requestedPackageVersion}, so several of the engine's own
 * branches (the semantic-tier warn codes below, {@code pkg == null}, {@code ui.readOnly}, and
 * every call site that actually passes a non-null version) are otherwise untested in Java.
 * {@link #projectsOnlyFieldsBelongingToPackageVersion(int, List)} and {@link
 * #failsClosedWhenTheRequestedPackageVersionIsNotDeclared()} mirror the two {@code
 * describe('multi-version projection', ...)} tests in {@code
 * contracts/view-models/src/project.test.ts} line for line (same package shape, same
 * assertions), since the Java port has no equivalent of its own.
 */
class DesignerProjectorRulesTest {

    private static final DesignerProjectorImpl PROJECTOR = new DesignerProjectorImpl();

    private static final StateModel.ObservedState OBSERVED = new StateModel.ObservedState(
            new StateModel.VersionObservation(null, null, "OK"), null, List.of(), null);

    /** All optional {@code PackageField} components left {@code null} except key/type. */
    private static PackageModel.PackageField field(String key, String type) {
        return new PackageModel.PackageField(
                key, type,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
    }

    private static PackageModel.PackageField fieldWithMinMax(String key, String type, Double min, Double max) {
        return new PackageModel.PackageField(
                key, type,
                null, null, null, null, null,
                null, null, null, null, null,
                min, max, null, null, null);
    }

    private static PackageModel.PackageField fieldWithPattern(String key, String type, String pattern) {
        return new PackageModel.PackageField(
                key, type,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, pattern, null, null);
    }

    private static PackageModel.PackageField fieldWithOptions(String key, String type, List<Object> options) {
        return new PackageModel.PackageField(
                key, type,
                null, null, options, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
    }

    private static PackageModel.PackageField fieldWithReadOnly(String key, String type, boolean readOnly) {
        PackageModel.UiHints ui = new PackageModel.UiHints(null, null, null, null, null, null, readOnly);
        return new PackageModel.PackageField(
                key, type,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, ui);
    }

    private static PackageModel.PackageField fieldWithVersion(String key, String type, List<Object> version) {
        return new PackageModel.PackageField(
                key, type,
                version, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
    }

    private static PackageModel.PackageField fieldWithBinding(String key, String type, String ref, String attribute) {
        return new PackageModel.PackageField(
                key, type,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                new PackageModel.ValueFrom(ref, attribute));
    }

    private static PackageModel.PackageField connectionSelector(String key) {
        return new PackageModel.PackageField(
                key, "string",
                null, null, null, new PackageModel.OptionsSource("catalog", "connection", "solace-broker"), null,
                null, null, null, null, null,
                null, null, null, null, null);
    }

    private static PackageModel.PackageField secretWithDefault(String key, String defaultValue) {
        return new PackageModel.PackageField(
                key, "secret",
                null, null, null, null, null,
                defaultValue, null, null, null, null,
                null, null, null, null, null);
    }

    private static PackageModel.SolutionPackage singleComponentPackage(PackageModel.PackageField... fields) {
        return new PackageModel.SolutionPackage(
                "rules-fixture",
                List.of(1),
                ">=1.0",
                null,
                List.of(new PackageModel.PackageComponent(
                        "widget", "exactly-one", null, "none", "widget", null, null,
                        List.of(fields), null, null, null, null, null)));
    }

    private static final StateModel.ComponentSelection SELECTION =
            new StateModel.ComponentSelection(List.of(new StateModel.ComponentInstance("w1", "widget", null)));

    private static ViewModel.FieldView fieldView(ViewModel vm, String instanceKey, String key) {
        return vm.components().stream()
                .filter(c -> c.instanceKey().equals(instanceKey))
                .flatMap(c -> c.sections().stream())
                .flatMap(s -> s.fields().stream())
                .filter(f -> f.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field " + key + " on instance " + instanceKey));
    }

    @Test
    void belowRecommendedWarnsWithoutBlocking() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(fieldWithMinMax("workers", "int", 10.0, 100.0));
        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of("workers", 5)), OBSERVED, Map.of(), null);

        ViewModel.FieldView view = fieldView(vm, "w1", "workers");
        assertThat(view.validations()).contains(new ViewModel.Validation(
                "warn", "BELOW_RECOMMENDED", "Below the range Solace recommends (10–100)."));
    }

    @Test
    void resolvesBrowserConnectionReferenceAgainstSelectedCatalogEntry() {
        PackageModel.PackageField selector = connectionSelector("broker");
        PackageModel.PackageField host = fieldWithBinding("host", "string", "broker", "host");
        Map<String, Object> draft = Map.of(
                "broker", "broker-1",
                "host", Map.of("$from", "broker-1#host"));
        Map<String, List<Object>> catalog = Map.of("connection", List.of(Map.of(
                "id", "broker-1",
                "attributes", Map.of("host", "broker.example"))));

        DraftValueResolver.Resolved resolved = DraftValueResolver.resolve(List.of(selector, host), draft, catalog);

        assertThat(resolved.values()).containsEntry("host", "broker.example");
        assertThat(resolved.sources()).containsEntry("host", "connection");
        assertThat(resolved.bindErrors()).doesNotContainKey("host");
    }

    @Test
    void doesNotProjectPackageSecretDefaultAsLiteral() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(secretWithDefault("password", "placeholder"));

        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of()), OBSERVED, Map.of(), null);

        ViewModel.FieldView password = fieldView(vm, "w1", "password");
        assertThat(password.value()).isInstanceOf(ViewModel.FieldValue.SecretUnset.class);
        assertThat(password.validations()).isEmpty();
    }

    @Test
    void patternMismatchWarnsWithoutBlocking() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(fieldWithPattern("name", "string", "^[a-z]+$"));
        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of("name", "ABC")), OBSERVED, Map.of(), null);

        ViewModel.FieldView view = fieldView(vm, "w1", "name");
        assertThat(view.validations()).contains(new ViewModel.Validation(
                "warn", "PATTERN", "Does not match the pattern Solace recommends (^[a-z]+$)."));
    }

    @Test
    void yamlLookingLikeACredentialWarnsWithoutBlocking() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(field("config", "yaml"));
        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of("config", "password: hunter2")), OBSERVED, Map.of(), null);

        ViewModel.FieldView view = fieldView(vm, "w1", "config");
        assertThat(view.validations()).anySatisfy(v -> {
            assertThat(v.severity()).isEqualTo("warn");
            assertThat(v.code()).isEqualTo("YAML_SECRET_SCAN");
        });
    }

    @Test
    void valueNotAmongDeclaredOptionsBlocks() {
        PackageModel.SolutionPackage pkg =
                singleComponentPackage(fieldWithOptions("mode", "enum", List.of("a", "b")));
        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of("mode", "c")), OBSERVED, Map.of(), null);

        ViewModel.FieldView view = fieldView(vm, "w1", "mode");
        assertThat(view.validations()).anySatisfy(v -> {
            assertThat(v.severity()).isEqualTo("block");
            assertThat(v.code()).isEqualTo("NOT_AN_OPTION");
        });
    }

    @Test
    void readOnlyFieldIsNotEnabledEvenWhenVersionDoesNotBlock() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(
                fieldWithReadOnly("locked", "string", true),
                field("editable", "string"));
        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of()), OBSERVED, Map.of(), null);

        assertThat(fieldView(vm, "w1", "locked").enabled()).isFalse();
        assertThat(fieldView(vm, "w1", "editable").enabled()).isTrue();
    }

    @Test
    void nullPackageFailsClosedWithVersionUnknown() {
        ViewModel vm = PROJECTOR.project(null, SELECTION, Map.of(), OBSERVED, Map.of(), null);

        assertThat(vm.solution()).isEqualTo("");
        assertThat(vm.packageVersion()).isNull();
        assertThat(vm.components()).isEmpty();
        assertThat(vm.diagnostics()).hasSize(1);
        assertThat(vm.diagnostics().get(0).code()).isEqualTo("VERSION_UNKNOWN");
    }

    // ---- mirrors contracts/view-models/src/project.test.ts's describe('multi-version projection', ...)

    private static final PackageModel.SolutionPackage VERSIONED_PKG = new PackageModel.SolutionPackage(
            "versioned-example",
            List.of(1, 2),
            ">=1.0 <2.0",
            null,
            List.of(new PackageModel.PackageComponent(
                    "worker", "exactly-one", List.of(1, 2), null, "worker", null, null,
                    List.of(
                            field("COMMON", "string"),
                            fieldWithVersion("V1_ONLY", "string", List.of(1)),
                            fieldWithVersion("V2_ONLY", "string", List.of(2))),
                    null, null, null, null, null)));

    private static final StateModel.ComponentSelection WORKER_SELECTION =
            new StateModel.ComponentSelection(List.of(new StateModel.ComponentInstance("worker", "worker", null)));

    static Stream<org.junit.jupiter.params.provider.Arguments> versionsAndExpectedKeys() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(1, List.of("COMMON", "V1_ONLY")),
                org.junit.jupiter.params.provider.Arguments.of(2, List.of("COMMON", "V2_ONLY")));
    }

    @ParameterizedTest(name = "package version {0}")
    @MethodSource("versionsAndExpectedKeys")
    void projectsOnlyFieldsBelongingToPackageVersion(int version, List<String> expectedKeys) {
        ViewModel vm = PROJECTOR.project(
                VERSIONED_PKG, WORKER_SELECTION, Map.of("worker", Map.of()), OBSERVED, Map.of(), version);

        assertThat(vm.packageVersion()).isEqualTo(version);
        List<String> actualKeys = vm.components().get(0).sections().stream()
                .flatMap(s -> s.fields().stream())
                .map(ViewModel.FieldView::key)
                .toList();
        assertThat(actualKeys).isEqualTo(expectedKeys);
    }

    @Test
    void failsClosedWhenTheRequestedPackageVersionIsNotDeclared() {
        ViewModel vm = PROJECTOR.project(
                VERSIONED_PKG, WORKER_SELECTION, Map.of("worker", Map.of()), OBSERVED, Map.of(), 3);

        assertThat(vm.components()).isEmpty();
        assertThat(vm.diagnostics()).hasSize(1);
        assertThat(vm.diagnostics().get(0).code()).isEqualTo("PACKAGE_VERSION_NOT_DECLARED");
    }

    private static PackageModel.PackageField fieldWhen(String key, String type, Map<String, Object> when) {
        return new PackageModel.PackageField(
                key, type,
                null, null, null, null, null,
                null, null, null, null, when,
                null, null, null, null, null);
    }

    /**
     * {@code UI-D2} is strict equality. No shared fixture drives a predicate against a value whose
     * type differs from the predicate literal while its text matches (they all gate on a bool the
     * engine already normalized), so a {@code String.valueOf} fallback in {@code evaluate} would
     * pass all 33 — measured by mutation (T1.2 Step 5). This pins it, Java-only, until a reviewed
     * {@code contracts/view-models} change adds such a fixture.
     */
    @Test
    void aPredicateDoesNotCoerceAStringToTheLiteralItsTextSpells() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(
                field("flag", "string"),
                fieldWhen("dependent", "string", Map.of("flag", true)));

        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of("flag", "true")), OBSERVED, Map.of(), null);

        ViewModel.FieldView dependent = fieldView(vm, "w1", "dependent");
        assertThat(dependent.visible()).as("the string \"true\" is not the boolean true").isFalse();
        assertThat(dependent.hiddenBy()).containsExactly("flag");
    }

    /**
     * The second coercion shape (S1 review MAJOR): JS loose {@code ==}, where a numeric string
     * equals a number and {@code true}/{@code false} equal {@code 1}/{@code 0}. The
     * {@code String.valueOf} pin above cannot see it ({@code "true" == true} is false in JS), and
     * the shared fixtures cannot tell coercion apart from strict equality. Java-only; the
     * contracts-side fixture is a separate reviewed {@code contracts/view-models} change.
     */
    @Test
    void aNumericStringDoesNotSatisfyANumericLiteral() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(
                field("k", "string"),
                fieldWhen("dependent", "string", Map.of("k", 8080)));

        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of("k", "8080")), OBSERVED, Map.of(), null);

        ViewModel.FieldView dependent = fieldView(vm, "w1", "dependent");
        assertThat(dependent.visible()).as("the string \"8080\" is not the number 8080").isFalse();
        assertThat(dependent.hiddenBy()).containsExactly("k");
    }

    @Test
    void aNumberDoesNotSatisfyABooleanLiteral() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(
                field("k", "int"),
                fieldWhen("dependent", "string", Map.of("k", true)));

        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of("k", 1)), OBSERVED, Map.of(), null);

        ViewModel.FieldView dependent = fieldView(vm, "w1", "dependent");
        assertThat(dependent.visible()).as("the number 1 is not the boolean true").isFalse();
        assertThat(dependent.hiddenBy()).containsExactly("k");
    }

    @Test
    void aBooleanDoesNotSatisfyANumericLiteral() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(
                field("k", "bool"),
                fieldWhen("dependent", "string", Map.of("k", 1)));

        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of("k", true)), OBSERVED, Map.of(), null);

        ViewModel.FieldView dependent = fieldView(vm, "w1", "dependent");
        assertThat(dependent.visible()).as("the boolean true is not the number 1").isFalse();
        assertThat(dependent.hiddenBy()).containsExactly("k");
    }

    @Test
    void aPredicateOnABoolFieldIsMetByItsNormalizedValue() {
        PackageModel.SolutionPackage pkg = singleComponentPackage(
                field("flag", "bool"),
                fieldWhen("dependent", "string", Map.of("flag", true)));

        ViewModel vm = PROJECTOR.project(
                pkg, SELECTION, Map.of("w1", Map.of("flag", "true")), OBSERVED, Map.of(), null);

        assertThat(fieldView(vm, "w1", "dependent").visible())
                .as("a bool control's \"true\" normalizes to true BEFORE predicates run")
                .isTrue();
    }
}
